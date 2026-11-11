package com.prabhat.djmix.library

import android.content.Context
import android.net.Uri
import android.os.Process
import android.util.Log
import com.prabhat.djmix.audio.BpmAnalyzer
import com.prabhat.djmix.audio.Loudness
import com.prabhat.djmix.audio.PcmTrack
import com.prabhat.djmix.instagram.IgAudio
import com.prabhat.djmix.instagram.InstagramLoginSheet
import com.prabhat.djmix.youtube.YtAudio
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background preparation queue. A YouTube or Instagram pick is resolved, downloaded, decoded to the PCM cache and
 * analysed one job at a time on a low-priority thread, so the console and the Load screen stay usable. Cloud copies
 * download three at a time on their own lane and then join that queue. A finished job is an ordinary library entry.
 *
 * [jobs] is the state every screen reads: immutable snapshots, a new list on every change, so Compose redraws exactly
 * the rows that changed. Hilt hands the one instance to whoever asks (@Singleton); [cloud] arrives the same way.
 */
@Singleton
class Prep @Inject constructor(
    @ApplicationContext private val app: Context,
    private val cloud: CloudRepository,
) {
    enum class Stage(val label: String) { QUEUED("Queued"), RESOLVING("Resolving"), DOWNLOADING("Downloading"), FETCHING("Fetching"), DECODING("Decoding"), ANALYSING("Analysing"), SEPARATING("Separating"), FAILED("Failed") }

    /**
     * YOUTUBE: a YouTube or Instagram (`ig_` id) pick: fetch, decode, analyse, back up. PREPARE: a library track that
     * was copied in: decode and analyse only. STEMS: separate. FETCH: copy from the user's cloud, then PREPARE.
     */
    enum class Kind { YOUTUBE, PREPARE, STEMS, FETCH }

    data class Job(val vid: String, val title: String, val kind: Kind, val stage: Stage = Stage.QUEUED, val progress: Int = 0, val error: String? = null) {
        val status: String get() = when (stage) {
            Stage.DOWNLOADING -> "Downloading $progress%"
            Stage.SEPARATING -> "Separating $progress%"
            Stage.FAILED -> "Failed: ${error ?: "unknown"}"
            else -> stage.label + "…"
        }
    }

    /** Unfinished jobs by vid, in the order they were added. Guarded by itself; [publish] copies it into [jobs]. */
    private val all = LinkedHashMap<String, Job>()
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    /** Jobs not finished yet (queued, running or failed), in the order they were added. */
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()
    private val queue = LinkedBlockingQueue<String>()
    private var worker: Thread? = null
    /** Cloud copies: three downloads at a time (one Firebase download uses a fraction of the line). */
    private val fetchLane = CoroutineScope(SupervisorJob() + Executors.newFixedThreadPool(3).asCoroutineDispatcher())

    fun job(vid: String): Job? = _jobs.value.firstOrNull { it.vid == vid }

    /** One line for the top bar, or null when idle. */
    fun summary(): String? {
        val a = _jobs.value.filter { it.stage != Stage.FAILED }
        if (a.isEmpty()) return null
        val cur = a.first()
        return "↓ ${a.size} · " + if (cur.stage == Stage.DOWNLOADING) "${cur.progress}%" else cur.stage.label.lowercase()
    }

    /** A YouTube or Instagram pick. With cloud backup on it is also noted as wanted until its audio is uploaded. */
    fun enqueue(vid: String, title: String): Boolean = add(Job(vid, title, Kind.YOUTUBE)).also { if (it) cloud.want(vid, title) }

    /** Decode and analyse a library track in the background, so its first load is instant. */
    fun enqueuePrepare(vid: String, title: String): Boolean = add(Job(vid, title, Kind.PREPARE))

    /** Separates a library track into vocals and instrumental (LALAL.AI, billed per minute). */
    fun enqueueStems(vid: String, title: String): Boolean = add(Job(vid, title, Kind.STEMS))

    /** Copies a track from the user's cloud, then prepares it. */
    fun enqueueFetch(vid: String, title: String): Boolean = add(Job(vid, title, Kind.FETCH))

    fun retry(vid: String) { job(vid)?.let { add(Job(vid, it.title, it.kind)) } }

    private fun add(j: Job): Boolean {
        synchronized(all) {
            val existing = all[j.vid]
            if (existing != null && existing.stage != Stage.FAILED) return false
            all[j.vid] = j
            publish()
        }
        if (j.kind == Kind.FETCH) fetchLane.launch { fetch(j) } else work(j.vid)
        return true
    }

    private fun work(vid: String) {
        queue.offer(vid)
        synchronized(this) {
            if (worker == null) worker = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) // never compete with the audio thread
                while (true) run(queue.take())
            }, "prep").apply { isDaemon = true; start() }
        }
    }

    private fun run(vid: String) {
        val j = synchronized(all) { all[vid] } ?: return
        try {
            when (j.kind) {
                Kind.STEMS -> separate(j)
                Kind.PREPARE -> decodeAndAnalyse(j, Library.read(app, Library.indexFile(app, vid)) ?: throw IOException("not in the library"))
                Kind.YOUTUBE -> {
                    set(vid) { it.copy(stage = Stage.RESOLVING) }
                    val info = if (IgAudio.isId(vid)) resolveInstagram(vid) else YtAudio.resolve(vid)
                    val title = info.title.ifBlank { j.title }
                    Log.i(TAG, "$vid: ${info.ext} ${info.kbps} kbps, ${info.durationMs / 1000} s")
                    val audio = File(Library.dir(app), "$vid.${info.ext}")
                    set(vid) { it.copy(title = title, stage = Stage.DOWNLOADING) }
                    YtAudio.download(info.url, audio) { p -> set(vid) { it.copy(progress = p) } }
                    val entry = Library.add(app, vid, title, info.durationMs, audio)
                    decodeAndAnalyse(j, entry)
                    cloud.backup(entry)
                }
                Kind.FETCH -> error("cloud copies run on the fetch lane")
            }
            done(vid)
        } catch (e: Exception) {
            fail(vid, e)
        }
    }

    /** On the fetch lane: the copy, then the same key goes to the worker as a PREPARE job. */
    private suspend fun fetch(j: Job) {
        try {
            set(j.vid) { it.copy(stage = Stage.FETCHING) }
            val e = cloud.download(j.vid)
            synchronized(all) { all[j.vid] = Job(j.vid, e.title, Kind.PREPARE); publish() }
            work(j.vid)
        } catch (e: Exception) {
            fail(j.vid, e)
        }
    }

    /** Instagram wants the WebView's cookies; a session it refuses is dropped, so the next shared link opens the login page. */
    private fun resolveInstagram(vid: String): YtAudio.Info =
        try { IgAudio.resolve(vid, InstagramLoginSheet.cookie()) } catch (e: IgAudio.LoginRequired) { InstagramLoginSheet.forget(); throw e }

    /** The PCM cache, the beat grid and the loudness gain: everything a load would otherwise do on the spot. */
    private fun decodeAndAnalyse(j: Job, entry: Library.Entry) {
        set(j.vid) { it.copy(stage = Stage.DECODING) }
        val t = PcmTrack.load(app, Uri.fromFile(entry.file), j.vid)
        val meta = TrackMeta.load(app, j.vid)
        if (meta == null) {
            set(j.vid) { it.copy(stage = Stage.ANALYSING) }
            val r = BpmAnalyzer.analyze(t)
            TrackMeta.saveAnalysis(app, j.vid, r.bpm, r.firstBeat, Loudness.gainDb(t))
        } else if (meta.gainDb.isNaN()) TrackMeta.saveGain(app, j.vid, Loudness.gainDb(t))
    }

    private fun separate(j: Job) {
        val key = Stems.key(app) ?: throw IOException("no LALAL.AI key")
        val entry = Library.read(app, Library.indexFile(app, j.vid)) ?: throw IOException("not in the library")
        set(j.vid) { it.copy(stage = Stage.DECODING) }
        val t = PcmTrack.load(app, Uri.fromFile(entry.file), j.vid) // makes sure the PCM cache exists
        set(j.vid) { it.copy(stage = Stage.SEPARATING) }
        val s = Stems.separate(key, File(app.cacheDir, "${j.vid}.pcm"), t.sampleRate, j.vid, Library.dir(app)) { p -> set(j.vid) { it.copy(progress = p) } }
        set(j.vid) { it.copy(stage = Stage.DECODING) }
        PcmTrack.load(app, Uri.fromFile(s.vocals), "${j.vid}.vox")
        PcmTrack.load(app, Uri.fromFile(s.inst), "${j.vid}.inst")
        cloud.backupStems(s)
    }

    private fun set(vid: String, change: (Job) -> Job) = synchronized(all) { all[vid]?.let { all[vid] = change(it); publish() } }
    private fun done(vid: String) = synchronized(all) { all.remove(vid); publish() }
    private fun fail(vid: String, e: Exception) {
        Log.w(TAG, "prep failed for $vid", e)
        set(vid) { it.copy(stage = Stage.FAILED, error = e.message ?: e.javaClass.simpleName) }
    }

    /** Caller holds the lock on [all]. */
    private fun publish() { _jobs.value = all.values.toList() }

    companion object {
        private const val TAG = "Prep"

        /** Ready to load without a wait: decoded cache and analysis both present. */
        fun prepared(ctx: Context, vid: String): Boolean = File(ctx.cacheDir, "$vid.pcm").length() > 8 && TrackMeta.exists(ctx, vid)
    }
}

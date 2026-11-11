package com.prabhat.djmix.library

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.StorageMetadata
import com.prabhat.djmix.auth.AuthRepository
import com.prabhat.djmix.instagram.IgAudio
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The shared cloud library in Firebase Storage: tracks/ (audio, index JSON, stems), mixes/ (recorded sets) and wanted/
 * (picks some phone queued but has not uploaded yet, so any signed-in phone can finish them). Every invited user sees
 * and fetches all of it; storage.rules keeps out accounts that are not on the invite list, and lets only a file's
 * uploader (or the admin) replace or delete it. A build without Firebase ([storage] null) never gets past sign-in.
 *
 * Signed out, nothing here touches the network: every track stays on the phone. Signed in with [autoBackup] on, new
 * tracks, stems and mixes go up by themselves; UPLOAD ALL sends what was added before. Failures are logged, never fatal.
 */
@Singleton
class CloudRepository @Inject constructor(
    @ApplicationContext private val app: Context,
    private val storage: FirebaseStorage?,
    private val auth: AuthRepository,
) {
    /** A track as the cloud index lists it. */
    data class Track(val vid: String, val title: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences("cloud", Context.MODE_PRIVATE)
    private val cacheFile = File(app.filesDir, "cloud.json")
    private val root get() = checkNotNull(storage) { "This build has no cloud" }.reference
    private val tracksDir get() = root.child("tracks")

    private val _autoBackup = MutableStateFlow(prefs.getBoolean("autoBackup", true))
    val autoBackup: StateFlow<Boolean> = _autoBackup.asStateFlow()
    private val _tracks = MutableStateFlow<List<Track>?>(null)
    /** The cloud's tracks sorted by title; null while signed out or before the first listing. */
    val tracks: StateFlow<List<Track>?> = _tracks.asStateFlow()
    private val _wanted = MutableStateFlow<List<Track>>(emptyList())
    val wanted: StateFlow<List<Track>> = _wanted.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _updatedAt = MutableStateFlow(0L)
    /** When [tracks] was last listed from the server (0 = never). */
    val updatedAt: StateFlow<Long> = _updatedAt.asStateFlow()
    private val _invited = MutableStateFlow(true)
    /** False once the rules refused this account: signed in, but not on the invite list. */
    val invited: StateFlow<Boolean> = _invited.asStateFlow()
    private val _uploading = MutableStateFlow(0)
    /** Tracks left in a running UPLOAD ALL. */
    val uploading: StateFlow<Int> = _uploading.asStateFlow()

    init {
        // Signed in: show the cached index at once, then list it afresh. Signed out: no cloud at all.
        scope.launch {
            auth.user.map { it?.uid }.distinctUntilChanged().collect { uid ->
                _tracks.value = if (uid == null) null else readCache()
                _updatedAt.value = if (uid == null) 0L else cacheFile.lastModified()
                _wanted.value = emptyList()
                _invited.value = true
                if (uid != null) refresh()
            }
        }
    }

    fun setAutoBackup(on: Boolean) {
        prefs.edit().putBoolean("autoBackup", on).apply()
        _autoBackup.value = on
    }

    /** Signed in, invited, auto backup on. Signed out this is false, so nothing ever leaves the phone. */
    val backupOn: Boolean get() = auth.uid != null && _invited.value && _autoBackup.value

    /** Every upload carries its uploader; the rules let only that user (or the admin) replace or delete the file. */
    private fun owned(uid: String) = StorageMetadata.Builder().setCustomMetadata("owner", uid).build()

    // ---- automatic backup (no-ops unless [backupOn]) ------------------------------------------------------

    /** A freshly prepared track, unless the cloud already has it (someone else picked the same song). */
    fun backup(e: Library.Entry) {
        if (!backupOn || _tracks.value?.any { it.vid == e.vid } == true) return
        scope.launch { runCatching { upload(e) }.onFailure { Log.w(TAG, "backup ${e.vid}: ${it.message}") } }
    }

    fun backupStems(s: Stems.Split) {
        val uid = auth.uid ?: return
        if (!backupOn) return
        scope.launch { for (f in listOf(s.vocals, s.inst)) runCatching { tracksDir.child(f.name).putFile(Uri.fromFile(f), owned(uid)).await() }.onFailure { Log.w(TAG, "stems ${f.name}: ${it.message}") } }
    }

    fun backupMix(file: File) {
        val uid = auth.uid ?: return
        if (!backupOn) return
        scope.launch { runCatching { root.child("mixes/${file.name}").putFile(Uri.fromFile(file), owned(uid)).await() }.onFailure { Log.w(TAG, "mix ${file.name}: ${it.message}") } }
    }

    /** Notes a pick (id, title, URL) in the cloud until its audio is uploaded, so any signed-in phone can GET it. */
    fun want(vid: String, title: String) {
        if (!backupOn) return
        val url = if (IgAudio.isId(vid)) IgAudio.url(vid) else "https://www.youtube.com/watch?v=$vid"
        val j = JSONObject().put("vid", vid).put("title", title).put("url", url).put("addedAt", System.currentTimeMillis())
        scope.launch { runCatching { root.child("wanted/$vid.json").putBytes(j.toString().toByteArray()).await() }.onFailure { Log.w(TAG, "want $vid: ${it.message}") } }
    }

    // ---- manual actions (need a signed-in user, not auto backup) -----------------------------------------

    /** UPLOAD ALL: every given track the cloud does not list yet, one after another. Needs a listing first. */
    fun uploadAll(entries: List<Library.Entry>) {
        val known = _tracks.value?.map { it.vid }?.toSet() ?: return
        if (auth.uid == null || _uploading.value > 0) return
        val todo = entries.filter { it.vid !in known }
        if (todo.isEmpty()) return
        _uploading.value = todo.size
        scope.launch {
            for (e in todo) {
                runCatching { upload(e) }.onFailure { Log.w(TAG, "upload ${e.vid}: ${it.message}") }
                _uploading.update { it - 1 }
            }
            _uploading.value = 0
        }
    }

    /** Audio, then stems, then the index entry: a track only shows up in listings once all of it is there. */
    private suspend fun upload(e: Library.Entry) {
        val uid = auth.uid ?: return
        val meta = owned(uid)
        tracksDir.child(e.file.name).putFile(Uri.fromFile(e.file), meta).await()
        Library.stemFiles(app, e.vid)?.let { (v, i) -> for (f in listOf(v, i)) tracksDir.child(f.name).putFile(Uri.fromFile(f), meta).await() }
        tracksDir.child("${e.vid}.json").putFile(Uri.fromFile(Library.indexFile(app, e.vid)), meta).await()
        runCatching { root.child("wanted/${e.vid}.json").delete().await() } // usually not there
        if (auth.uid == null) return
        _tracks.update { l -> l?.let { if (it.any { t -> t.vid == e.vid }) it else (it + Track(e.vid, e.title)).sortedBy { t -> t.title.lowercase() } } }
        _tracks.value?.let { writeCache(it) }
    }

    /**
     * Copies one track (index entry, audio, stems when the cloud has both) into the local library. The index entry is
     * written last, so a copy cut short never shows up as a track.
     */
    suspend fun download(vid: String): Library.Entry {
        check(auth.uid != null) { "Sign in with Google to use the cloud" }
        val index = Library.indexFile(app, vid)
        val part = File(index.path + ".part")
        tracksDir.child("$vid.json").getFile(part).await()
        val name = JSONObject(part.readText()).getString("file")
        tracksDir.child(name).getFile(File(Library.dir(app), name)).await()
        val vox = Stems.voxFile(Library.dir(app), vid); val inst = Stems.instFile(Library.dir(app), vid)
        runCatching { tracksDir.child(vox.name).getFile(vox).await(); tracksDir.child(inst.name).getFile(inst).await() }.onFailure { vox.delete(); inst.delete() }
        check(part.renameTo(index)) { "could not save the index for $vid" }
        return Library.read(app, index) ?: error("unreadable entry for $vid")
    }

    /**
     * Removes a track from the cloud: the index entry first (so it leaves every listing), then audio (by [fileName],
     * else every known extension), stems and any wanted note. Throws when the rules refuse: only the uploader or the
     * admin may delete.
     */
    suspend fun delete(vid: String, fileName: String?) {
        check(auth.uid != null) { "Sign in with Google to use the cloud" }
        val names = listOf("$vid.json") + (fileName?.let { listOf(it) } ?: listOf("$vid.webm", "$vid.m4a", "$vid.mp4")) + listOf("$vid.vox.flac", "$vid.inst.flac")
        for (n in names) {
            try {
                tracksDir.child(n).delete().await()
            } catch (e: StorageException) {
                if (e.errorCode == StorageException.ERROR_NOT_AUTHORIZED) throw IllegalStateException("Only the person who uploaded this song can delete it")
                if (e.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw e
            }
        }
        runCatching { root.child("wanted/$vid.json").delete().await() }
        _tracks.update { it?.filter { t -> t.vid != vid } }
        _tracks.value?.let { writeCache(it) }
    }

    /**
     * Lists tracks/ (500 names a page), reads only the index entries the cache does not know, drops the ones that are
     * gone, and lists wanted/. On failure the cached listing stays.
     */
    fun refresh() {
        if (auth.uid == null || !_refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                val names = ArrayList<String>()
                var token: String? = null
                do {
                    val page = (if (token == null) tracksDir.list(500) else tracksDir.list(500, token)).await()
                    page.items.mapTo(names) { it.name }
                    token = page.pageToken
                } while (token != null)
                val (kept, missing) = merge((_tracks.value ?: emptyList()).associate { it.vid to it.title }, names)
                val read = missing.map { vid -> async { runCatching { readTrack(tracksDir.child("$vid.json")) }.getOrNull() } }.awaitAll().filterNotNull()
                val wanted = runCatching { root.child("wanted").listAll().await().items.filter { it.name.endsWith(".json") } }.getOrDefault(emptyList())
                    .map { async { runCatching { readTrack(it) }.getOrNull() } }.awaitAll().filterNotNull()
                if (auth.uid == null) return@launch // signed out while listing
                val all = (kept.map { Track(it.first, it.second) } + read).sortedBy { it.title.lowercase() }
                _tracks.value = all
                _wanted.value = wanted.sortedBy { it.title.lowercase() }
                _invited.value = true
                writeCache(all)
            } catch (e: Exception) {
                Log.w(TAG, "refresh: ${e.message}")
                if ((e as? StorageException)?.errorCode == StorageException.ERROR_NOT_AUTHORIZED && auth.uid != null) {
                    _invited.value = false
                    _tracks.value = emptyList()
                    runCatching { cacheFile.delete() } // an uninvited account must not see the last listing either
                } else if (_tracks.value == null && auth.uid != null) _tracks.value = emptyList()
            } finally {
                _refreshing.value = false
            }
        }
    }

    private suspend fun readTrack(ref: StorageReference): Track =
        JSONObject(String(ref.getBytes(64 * 1024).await())).let { Track(it.getString("vid"), it.getString("title")) }

    // ---- index cache -------------------------------------------------------------------------------------

    private fun readCache(): List<Track>? = runCatching {
        val a = JSONArray(cacheFile.readText())
        List(a.length()) { a.getJSONObject(it).let { j -> Track(j.getString("vid"), j.getString("title")) } }
    }.getOrNull()

    private fun writeCache(tracks: List<Track>) {
        runCatching { cacheFile.writeText(JSONArray(tracks.map { JSONObject().put("vid", it.vid).put("title", it.title) }).toString()) }
        _updatedAt.value = System.currentTimeMillis()
    }

    companion object {
        private const val TAG = "Cloud"

        /** Which cached entries survive the listed [names] (an index JSON per track), and which vids still need reading. */
        fun merge(known: Map<String, String>, names: List<String>): Pair<List<Pair<String, String>>, List<String>> {
            val vids = names.filter { it.endsWith(".json") && it.count { c -> c == '.' } == 1 }.map { it.removeSuffix(".json") }
            val kept = vids.mapNotNull { vid -> known[vid]?.let { vid to it } }
            val missing = vids.filter { it !in known }
            return kept to missing
        }
    }
}

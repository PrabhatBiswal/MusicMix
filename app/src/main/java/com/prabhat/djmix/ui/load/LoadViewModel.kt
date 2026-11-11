package com.prabhat.djmix.ui.load

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prabhat.djmix.auth.AuthRepository
import com.prabhat.djmix.library.CloudRepository
import com.prabhat.djmix.library.Library
import com.prabhat.djmix.library.Prep
import com.prabhat.djmix.library.Stems
import com.prabhat.djmix.library.TrackMeta
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs

/**
 * The Load screen's state holder (unidirectional data flow): the screen draws [state] and reports taps by calling the
 * functions below; nothing in the screen changes data directly. [state] is rebuilt whenever any input changes:
 * the library on disk, the filters, the prep queue, the signed-in user or the cloud listing.
 */
@HiltViewModel
class LoadViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val prep: Prep,
    private val cloud: CloudRepository,
    auth: AuthRepository,
) : ViewModel() {

    enum class Tab { LIBRARY, CLOUD }
    enum class Sort(val label: String) { TITLE("TITLE"), BPM("BPM"), NEW("NEW"), MATCH("MATCH A") }

    /** A library track with what its row shows. */
    data class LocalTrack(val entry: Library.Entry, val bpm: Double, val prepared: Boolean)

    /** A cloud row: the cloud's track, plus the library copy when this phone has it too. */
    data class CloudItem(val track: CloudRepository.Track, val local: LocalTrack?)

    data class Filters(val tab: Tab = Tab.LIBRARY, val query: String = "", val playlist: String = ALL, val sort: Sort = Sort.TITLE, val deckABpm: Double = 0.0)

    data class UiState(
        val filters: Filters = Filters(),
        /** Queue rows on top of the library tab. */
        val jobs: List<Prep.Job> = emptyList(),
        /** Library rows: the playlist, filtered by the search, sorted. */
        val tracks: List<LocalTrack> = emptyList(),
        /** On the phone and not busy in the queue. */
        val localCount: Int = 0,
        val playlistEmpty: Boolean = false,
        val playlists: List<String> = listOf(ALL),
        /** Tracks still to decode: the PREPARE n chip. */
        val raw: List<Library.Entry> = emptyList(),
        val signedIn: Boolean = false,
        /** False when the signed-in account is not on the cloud's invite list. */
        val invited: Boolean = true,
        /** Tracks in the cloud; null while signed out or not listed yet. */
        val cloudTotal: Int? = null,
        val cloudOnPhone: Int = 0,
        /** The whole cloud library, filtered by the search: tracks not on this phone first, then the ones that are. */
        val cloudList: List<CloudItem> = emptyList(),
        /** Open picks nobody has uploaded, filtered by the search. */
        val wanted: List<CloudRepository.Track> = emptyList(),
        /** FETCH ALL: in the cloud, not here, not being copied. */
        val missing: List<CloudRepository.Track> = emptyList(),
        /** UPLOAD ALL: here, not in the cloud. */
        val notUploaded: Int = 0,
        val uploading: Int = 0,
        val refreshing: Boolean = false,
        val updatedAt: Long = 0,
        val inCloud: Set<String> = emptySet(),
        /** Cloud copies in the queue, by vid. */
        val fetches: Map<String, Prep.Job> = emptyMap(),
    )

    private data class Loaded(val all: List<LocalTrack> = emptyList(), val playlists: List<String> = emptyList())
    private data class CloudSnap(val tracks: List<CloudRepository.Track>?, val wanted: List<CloudRepository.Track>, val refreshing: Boolean, val uploading: Int, val updatedAt: Long, val invited: Boolean)

    private val loaded = MutableStateFlow(Loaded())
    private val filters = MutableStateFlow(Filters())
    private val reloadLock = Mutex()
    private val cloudSnap: Flow<CloudSnap> = combine(cloud.tracks, cloud.wanted, cloud.refreshing, combine(cloud.uploading, cloud.invited, ::Pair), cloud.updatedAt) { t, w, r, (up, inv), at ->
        CloudSnap(t, w, r, up, at, inv)
    }

    val state: StateFlow<UiState> = combine(loaded, filters, prep.jobs, auth.user, cloudSnap) { l, f, jobs, user, c -> build(l, f, jobs, user != null, c) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** One-off messages for a toast (a failed cloud delete). */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        // The library on disk changes when a job starts, ends or fails, not on every progress tick: reload only then.
        viewModelScope.launch {
            prep.jobs.map { js -> js.map { it.vid to (it.stage == Prep.Stage.FAILED) } }.distinctUntilChanged().collect { reload() }
        }
        if (System.currentTimeMillis() - cloud.updatedAt.value > 5 * 60_000) cloud.refresh()
    }

    private suspend fun reload() = withContext(Dispatchers.IO) {
        reloadLock.withLock {
            val entries = Library.list(app)
            loaded.value = Loaded(
                entries.map { LocalTrack(it, TrackMeta.load(app, it.vid)?.bpm ?: 0.0, Prep.prepared(app, it.vid)) },
                Library.playlists(app, entries),
            )
        }
    }

    private fun build(l: Loaded, f: Filters, jobs: List<Prep.Job>, signedIn: Boolean, c: CloudSnap): UiState {
        val q = f.query.trim().lowercase()
        fun hit(title: String) = q.isEmpty() || title.lowercase().contains(q)
        val busy = jobs.map { it.vid }.toSet()
        val playlists = listOf(ALL) + l.playlists
        val playlist = if (f.playlist in playlists) f.playlist else ALL
        val local = l.all.filter { it.entry.vid !in busy }
        val inList = local.filter { playlist == ALL || playlist in it.entry.lists }
        val shown = inList.filter { hit(it.entry.title) || bpmText(it.bpm).contains(q) }
        val tracks = when (f.sort) {
            Sort.TITLE -> shown.sortedBy { it.entry.title.lowercase() }
            Sort.BPM -> shown.sortedBy { it.bpm }
            Sort.NEW -> shown.sortedByDescending { it.entry.addedAt }
            Sort.MATCH -> if (f.deckABpm > 0) shown.sortedBy { abs(it.bpm - f.deckABpm) } else shown.sortedBy { it.entry.title.lowercase() }
        }
        // A copied track waiting for PREPARE is busy but already on the phone, so the cloud tab must not offer it again.
        val localByVid = l.all.associateBy { it.entry.vid }
        val localIds = localByVid.keys
        val cloudIds = c.tracks?.map { it.vid }?.toSet() ?: emptySet()
        val fetches = jobs.filter { it.kind == Prep.Kind.FETCH }.associateBy { it.vid }
        return UiState(
            filters = f.copy(playlist = playlist),
            jobs = jobs.filter { hit(it.title) },
            tracks = tracks,
            localCount = local.size,
            playlistEmpty = inList.isEmpty(),
            playlists = playlists,
            raw = l.all.filter { !it.prepared && it.entry.vid !in busy }.map { it.entry } +
                l.all.filter { !it.prepared && jobs.any { j -> j.vid == it.entry.vid && j.stage == Prep.Stage.FAILED } }.map { it.entry },
            signedIn = signedIn,
            invited = c.invited,
            cloudTotal = c.tracks?.size,
            cloudOnPhone = cloudIds.count { it in localIds },
            cloudList = c.tracks?.filter { hit(it.title) }?.map { CloudItem(it, localByVid[it.vid]) }?.sortedBy { it.local != null } ?: emptyList(),
            wanted = c.wanted.filter { it.vid !in localIds && it.vid !in cloudIds && it.vid !in busy && hit(it.title) },
            missing = c.tracks?.filter { it.vid !in localIds && fetches[it.vid]?.stage.let { s -> s == null || s == Prep.Stage.FAILED } } ?: emptyList(),
            notUploaded = if (c.tracks == null) 0 else localIds.count { it !in cloudIds },
            uploading = c.uploading,
            refreshing = c.refreshing,
            updatedAt = c.updatedAt,
            inCloud = cloudIds,
            fetches = fetches,
        )
    }

    // ---- filters ------------------------------------------------------------------------------------------

    fun setTab(t: Tab) = filters.update { it.copy(tab = t) }
    fun setQuery(q: String) = filters.update { it.copy(query = q) }
    fun setPlaylist(p: String) = filters.update { it.copy(playlist = p) }
    fun setSort(s: Sort) = filters.update { it.copy(sort = s) }
    fun setDeckABpm(bpm: Double) = filters.update { it.copy(deckABpm = bpm) }

    // ---- queue --------------------------------------------------------------------------------------------

    fun prepare(e: Library.Entry) { prep.enqueuePrepare(e.vid, e.title) }
    fun prepareAll() = state.value.raw.forEach { prepare(it) }
    fun retry(vid: String) = prep.retry(vid)
    /** An open pick from another phone: download it here (and upload it, with backup on). */
    fun get(t: CloudRepository.Track) { prep.enqueue(t.vid, t.title); setTab(Tab.LIBRARY) }
    fun separate(e: Library.Entry) { prep.enqueueStems(e.vid, e.title) }

    // ---- cloud --------------------------------------------------------------------------------------------

    fun fetch(t: CloudRepository.Track) { prep.enqueueFetch(t.vid, t.title) }
    fun fetchAll() = state.value.missing.forEach { fetch(it) }
    fun uploadAll() { viewModelScope.launch(Dispatchers.IO) { cloud.uploadAll(Library.list(app)) } }
    fun refresh() = cloud.refresh()
    fun deleteFromCloud(vid: String, fileName: String?) {
        viewModelScope.launch { runCatching { cloud.delete(vid, fileName) }.onFailure { _messages.send(it.message ?: "Could not delete from the cloud") } }
    }

    // ---- library ------------------------------------------------------------------------------------------

    fun deleteFromPhone(e: Library.Entry) { viewModelScope.launch(Dispatchers.IO) { Library.delete(app, e); reload() } }

    fun setInPlaylist(vid: String, name: String, on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = Library.read(app, Library.indexFile(app, vid))?.lists ?: emptyList()
            Library.setLists(app, vid, if (on) (now + name).distinct() else now - name)
            reload()
        }
    }

    /** A new playlist, optionally with [vid] in it; from the rail it becomes the one shown. */
    fun newPlaylist(name: String, vid: String?) {
        val n = name.trim()
        if (n.isEmpty() || n.equals(ALL, ignoreCase = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            Library.addPlaylist(app, n)
            if (vid != null) Library.setLists(app, vid, ((Library.read(app, Library.indexFile(app, vid))?.lists ?: emptyList()) + n).distinct())
            else setPlaylist(n)
            reload()
        }
    }

    // ---- stems (LALAL.AI key stays on this phone) --------------------------------------------------------

    fun stemsKey(): String? = Stems.key(app)
    fun setStemsKey(k: String) { if (k.isNotBlank()) Stems.setKey(app, k) }
    suspend fun minutesLeft(key: String): String = withContext(Dispatchers.IO) {
        runCatching { Stems.minutesLeft(key) }.fold({ String.format("%.1f minutes left on the plan.", it) }, { "Could not read the plan: ${it.message}" })
    }

    companion object {
        const val ALL = "ALL"
        fun bpmText(bpm: Double): String = if (bpm > 0) String.format("%.1f", bpm) else "–"
    }
}

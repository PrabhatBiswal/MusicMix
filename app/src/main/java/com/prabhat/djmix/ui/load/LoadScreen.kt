package com.prabhat.djmix.ui.load

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prabhat.djmix.BuildConfig
import com.prabhat.djmix.instagram.IgAudio
import com.prabhat.djmix.library.CloudRepository
import com.prabhat.djmix.library.Library
import com.prabhat.djmix.library.Prep
import com.prabhat.djmix.ui.account.AccountDialog
import com.prabhat.djmix.ui.account.NotInvitedCard
import com.prabhat.djmix.ui.account.SignInCard
import com.prabhat.djmix.ui.load.LoadViewModel.Companion.ALL
import com.prabhat.djmix.ui.load.LoadViewModel.Companion.bpmText
import com.prabhat.djmix.ui.load.LoadViewModel.LocalTrack
import com.prabhat.djmix.ui.load.LoadViewModel.Tab
import com.prabhat.djmix.ui.load.LoadViewModel.UiState
import com.prabhat.djmix.ui.theme.DJ

/** What the panel over the list shows: a library track's actions or a cloud track's. */
private sealed interface Sheet {
    data class Local(val vid: String) : Sheet
    data class Cloud(val track: CloudRepository.Track) : Sheet
}

/** The dialog a tap opens; one at a time (a newer one replaces the one up). */
private sealed interface Prompt {
    data class NewPlaylist(val vid: String?) : Prompt
    data class StemsKey(val entry: Library.Entry) : Prompt
    data class Stems(val entry: Library.Entry, val key: String) : Prompt
    data class DeletePhone(val entry: Library.Entry, val inCloud: Boolean) : Prompt
    data class DeleteCloud(val vid: String, val title: String, val fileName: String?) : Prompt
}

/**
 * The full-screen Load view: sources in a rail (Library, YouTube, Cloud, Files) with playlists or cloud actions under
 * them, search and sort on top, and dense rows that load straight to deck A or B; a tap on the title loads to the idle
 * deck. Pure UI: everything it shows comes from [LoadViewModel.state], every tap goes back to the ViewModel or [host].
 */
@Composable
fun LoadScreen(host: LoadHost, deck: String, close: () -> Unit, vm: LoadViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    var account by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val loaded = remember { host.loadedKey("A") to host.loadedKey("B") }
    fun deckOf(vid: String) = when (vid) { loaded.first -> "A"; loaded.second -> "B"; else -> null }
    fun load(e: Library.Entry, d: String) { close(); host.onLoad(e, d) }
    fun fetchLoad(vid: String, d: String) { close(); host.onFetch(vid, d) }

    LaunchedEffect(Unit) {
        vm.setDeckABpm(host.deckBpm("A"))
        vm.messages.collect { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
    }

    // A track can leave the list while its sheet is up (deleted, or busy preparing): then the sheet just is not drawn.
    val locals = s.tracks + s.cloudList.mapNotNull { it.local }
    val open = sheet?.takeIf { sh -> sh !is Sheet.Local || locals.any { it.entry.vid == sh.vid } }
    Box(Modifier.fillMaxSize().background(DJ.ground).padding(8.dp)) {
        Row(Modifier.fillMaxSize()) {
            Rail(s, vm, onYouTube = { close(); host.onYouTube(deck) }, onFiles = { close(); host.onFiles(deck) },
                onNewPlaylist = { prompt = Prompt.NewPlaylist(null) }, onAccount = { account = true })
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                TopBar(s, vm, close)
                if (s.filters.tab == Tab.CLOUD && !s.signedIn) SignInCard()
                else if (s.filters.tab == Tab.CLOUD && !s.invited) NotInvitedCard()
                else {
                    Header()
                    LazyColumn(Modifier.weight(1f).padding(top = 4.dp)) {
                        if (s.filters.tab == Tab.LIBRARY) {
                            items(s.jobs, key = { "job:" + it.vid }) { j -> JobRow(j) { vm.retry(j.vid) } }
                            items(s.tracks, key = { it.entry.vid }) { t ->
                                TrackRow(t, deckOf(t.entry.vid), onTitle = { load(t.entry, host.idleDeck()) }, onA = { load(t.entry, "A") },
                                    onB = { load(t.entry, "B") }, onMore = { sheet = Sheet.Local(t.entry.vid) })
                            }
                        } else {
                            items(s.wanted, key = { "want:" + it.vid }) { w -> WantedRow(w) { vm.get(w) } }
                            items(s.cloudList, key = { "cloud:" + it.track.vid }) { item ->
                                val t = item.local
                                val c = item.track
                                if (t != null) TrackRow(t, deckOf(c.vid), onTitle = { load(t.entry, host.idleDeck()) }, onA = { load(t.entry, "A") },
                                    onB = { load(t.entry, "B") }, onMore = { sheet = Sheet.Local(c.vid) }, extraTags = listOf("ON PHONE"))
                                else CloudRow(c, s.fetches[c.vid], onDown = { vm.fetch(c) }, onA = { fetchLoad(c.vid, "A") }, onB = { fetchLoad(c.vid, "B") },
                                    onMore = { sheet = Sheet.Cloud(c) })
                            }
                        }
                    }
                }
            }
        }
        open?.let { sh ->
            // In the screen, not a new window: the console stays immersive (no system bars) while it is up.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(remember { MutableInteractionSource() }, null) { sheet = null },
                contentAlignment = Alignment.BottomCenter) {
                Surface(Modifier.fillMaxWidth().widthIn(max = 760.dp), color = DJ.surface, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
                    when (sh) {
                        is Sheet.Local -> locals.first { it.entry.vid == sh.vid }.let { t ->
                            TrackSheet(t, deckOf(t.entry.vid), s, vm, onLoad = { d -> sheet = null; load(t.entry, d) }, onClose = { sheet = null }, onPrompt = { sheet = null; prompt = it })
                        }
                        is Sheet.Cloud -> CloudSheet(sh.track, s.signedIn,
                            onCopy = { sheet = null; vm.fetch(sh.track) }, onLoad = { d -> sheet = null; fetchLoad(sh.track.vid, d) },
                            onDelete = { sheet = null; prompt = Prompt.DeleteCloud(sh.track.vid, sh.track.title, null) })
                    }
                }
            }
        }
    }
    BackHandler(enabled = open != null) { sheet = null }
    prompt?.let { p -> PromptDialog(p, vm, dismiss = { prompt = null }, open = { prompt = it }) }
    if (account && s.signedIn) AccountDialog(onDismiss = { account = false })
}

// ---- rail and top bar -----------------------------------------------------------------------------------

@Composable
private fun Rail(s: UiState, vm: LoadViewModel, onYouTube: () -> Unit, onFiles: () -> Unit, onNewPlaylist: () -> Unit, onAccount: () -> Unit) {
    Column(Modifier.width(112.dp).fillMaxHeight()) {
        TabBtn("LIBRARY", s.filters.tab == Tab.LIBRARY) { vm.setTab(Tab.LIBRARY) }
        TabBtn("YOUTUBE", false, onYouTube)
        if (BuildConfig.CLOUD) TabBtn("CLOUD", s.filters.tab == Tab.CLOUD) { vm.setTab(Tab.CLOUD) }
        TabBtn("FILES", false, onFiles)
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = DJ.border)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (s.filters.tab == Tab.LIBRARY) {
                s.playlists.forEach { p -> RailChip(p, p == s.filters.playlist) { vm.setPlaylist(p) } }
                RailChip("+ NEW", false, onNewPlaylist)
                if (s.raw.isNotEmpty()) RailChip("PREPARE ${s.raw.size}", false) { vm.prepareAll() }
            } else if (s.signedIn && !s.invited) {
                RailChip("ACCOUNT", false, onAccount)
                RailChip(if (s.refreshing) "REFRESHING…" else "REFRESH", false) { vm.refresh() }
            } else if (s.signedIn) {
                RailChip("ACCOUNT", false, onAccount)
                if (s.cloudTotal != null) {
                    RailChip(if (s.missing.isEmpty()) "ALL FETCHED" else "FETCH ALL ${s.missing.size}", false) { vm.fetchAll() }
                    RailChip(when { s.uploading > 0 -> "UPLOADING ${s.uploading}"; s.notUploaded == 0 -> "ALL UPLOADED"; else -> "UPLOAD ALL ${s.notUploaded}" }, false) { vm.uploadAll() }
                }
                RailChip(if (s.refreshing) "REFRESHING…" else "REFRESH", false) { vm.refresh() }
                val age = (System.currentTimeMillis() - s.updatedAt) / 60_000
                if (s.updatedAt > 0 && !s.refreshing) Text("Updated " + (if (age < 1) "just now" else "$age min ago"), color = DJ.muted, fontSize = 9.sp, modifier = Modifier.padding(start = 10.dp, top = 8.dp))
            }
        }
        Text(status(s), color = DJ.muted, fontSize = 9.sp, lineHeight = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

private fun status(s: UiState): String = if (s.filters.tab == Tab.LIBRARY) when {
    s.localCount == 0 && s.jobs.isEmpty() -> "Nothing here yet. Add a song from YouTube or open a file."
    s.playlistEmpty && s.jobs.isEmpty() -> "${s.filters.playlist} is empty. Use ⋯ on a track in ALL to add it."
    else -> "${s.tracks.size} of ${s.localCount} on this phone" + (s.cloudTotal?.let { " · $it in the cloud" } ?: "")
} else when {
    !s.signedIn -> "Signed out: nothing leaves this phone."
    !s.invited -> "Not on the invite list: nothing leaves this phone."
    s.cloudTotal == null -> "Listing the cloud…"
    s.cloudTotal == 0 && s.wanted.isEmpty() -> "The cloud is empty or not reachable."
    else -> "${s.cloudTotal} in the cloud · ${s.cloudOnPhone} on this phone"
}

@Composable
private fun TopBar(s: UiState, vm: LoadViewModel, close: () -> Unit) {
    // The field keeps its own text so typing never waits for the ViewModel round trip.
    var text by rememberSaveable { mutableStateOf(s.filters.query) }
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = text, onValueChange = { text = it; vm.setQuery(it) }, singleLine = true,
            textStyle = TextStyle(color = DJ.text, fontSize = 13.sp), cursorBrush = SolidColor(DJ.deckA),
            modifier = Modifier.weight(1f).height(44.dp).background(DJ.ground, RoundedCornerShape(8.dp)).border(1.dp, DJ.border, RoundedCornerShape(8.dp)),
            decorationBox = { field ->
                Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text("Search title or BPM", color = DJ.muted, fontSize = 13.sp)
                    field()
                }
            },
        )
        if (s.filters.tab == Tab.LIBRARY) {
            Spacer(Modifier.width(6.dp))
            LoadViewModel.Sort.entries.forEach { o ->
                val label = if (o == LoadViewModel.Sort.MATCH && s.filters.deckABpm > 0) "MATCH A ${s.filters.deckABpm.toInt()}" else o.label
                Pill(label, o == s.filters.sort, Modifier.padding(end = 4.dp)) { vm.setSort(o) }
            }
        }
        RowBtn("✕", width = 44.dp, height = 44.dp, fontSize = 16.sp, modifier = Modifier.padding(start = 2.dp), onClick = close)
    }
}

@Composable
private fun Header() = Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 10.dp, end = 10.dp)) {
    Label("Track · tap = idle deck", Modifier.weight(1f))
    Label("BPM", Modifier.width(56.dp), TextAlign.End)
    Label("Length", Modifier.width(52.dp), TextAlign.End)
    Spacer(Modifier.width(136.dp))
}

// ---- rows -----------------------------------------------------------------------------------------------

@Composable
private fun TrackRow(t: LocalTrack, onDeck: String?, onTitle: () -> Unit, onA: () -> Unit, onB: () -> Unit, onMore: () -> Unit, extraTags: List<String> = emptyList()) {
    val e = t.entry
    RowFrame(deckColor(onDeck), titled(e.vid, e.title, extraTags + (if (e.stems) listOf("ST") else emptyList()) + e.lists, DJ.text), onTitle, bpmText(t.bpm), clock(e.durationMs)) {
        RowBtn("A", DJ.deckA, DJ.deckATint, DJ.deckA, onClick = onA)
        RowBtn("B", DJ.deckB, DJ.deckBTint, DJ.deckB, onClick = onB)
        RowBtn("⋯", fontSize = 16.sp, onClick = onMore)
    }
}

/** A track still being prepared: status instead of BPM and length, RETRY when it failed. */
@Composable
private fun JobRow(j: Prep.Job, onRetry: () -> Unit) {
    val failed = j.stage == Prep.Stage.FAILED
    RowFrame(if (failed) DJ.kill else DJ.muted, titled(j.vid, j.title, emptyList(), DJ.muted), null, "", j.status, lengthWidth = 150.dp) {
        if (failed) RowBtn("RETRY", width = 132.dp, fontSize = 10.sp, onClick = onRetry)
    }
}

/** A cloud-only track: ↓ copies it here, A / B copy and load it, ⋯ for the sheet. A row being copied shows its state. */
@Composable
private fun CloudRow(c: CloudRepository.Track, job: Prep.Job?, onDown: () -> Unit, onA: () -> Unit, onB: () -> Unit, onMore: () -> Unit) {
    val busy = job != null && job.stage != Prep.Stage.FAILED
    RowFrame(DJ.border, titled(c.vid, c.title, emptyList(), DJ.muted), null, "–", if (busy) job!!.status else "", lengthWidth = if (busy) 90.dp else 52.dp) {
        if (!busy) {
            RowBtn("↓", fontSize = 16.sp, modifier = Modifier.padding(start = 2.dp), onClick = onDown)
            RowBtn("A", DJ.deckA, DJ.deckATint, DJ.deckA, onClick = onA)
            RowBtn("B", DJ.deckB, DJ.deckBTint, DJ.deckB, onClick = onB)
            RowBtn("⋯", fontSize = 16.sp, onClick = onMore)
        }
    }
}

/** Picked on a phone that never uploaded it: GET runs the normal prep here and shares it. */
@Composable
private fun WantedRow(w: CloudRepository.Track, onGet: () -> Unit) =
    RowFrame(DJ.deckB, titled(w.vid, w.title, listOf("WANTED"), DJ.muted), null, "–", "") {
        RowBtn("GET AND SHARE", DJ.deckB, DJ.deckBTint, DJ.deckB, width = 132.dp, fontSize = 10.sp, onClick = onGet)
    }

/** One 44 dp row: colour bar, title + tags, BPM, length, then its buttons. */
@Composable
private fun RowFrame(bar: Color, title: AnnotatedString, onTitle: (() -> Unit)?, bpm: String, length: String, lengthWidth: Dp = 52.dp, buttons: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.padding(bottom = 4.dp).fillMaxWidth().height(44.dp).background(DJ.controlDim, shape).border(1.dp, DJ.border, shape).padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(end = 8.dp).size(4.dp, 22.dp).background(bar))
        Box(Modifier.weight(1f).fillMaxHeight().then(if (onTitle != null) Modifier.clickable(onClick = onTitle) else Modifier), contentAlignment = Alignment.CenterStart) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Readout(bpm, Modifier.width(56.dp), DJ.text)
        Readout(length, Modifier.width(lengthWidth), DJ.muted)
        buttons()
    }
}

// ---- sheets ---------------------------------------------------------------------------------------------

/** ⋯ on a library track: where to load it, its playlists, stems and the two deletes. */
@Composable
private fun TrackSheet(t: LocalTrack, onDeck: String?, s: UiState, vm: LoadViewModel, onLoad: (String) -> Unit, onClose: () -> Unit, onPrompt: (Prompt) -> Unit) {
    val e = t.entry
    val inCloud = e.vid in s.inCloud
    SheetColumn(deckColor(onDeck), e.title,
        listOfNotNull(source(e.vid), "${bpmText(t.bpm)} BPM", clock(e.durationMs), if (e.stems) "stems" else null, if (t.prepared) "ready" else "not prepared",
            if (inCloud) "in the cloud" else "only on this phone").joinToString(" · ")) {
        Row(Modifier.height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Act("LOAD A", DJ.deckA, DJ.deckATint, DJ.deckA, Modifier.weight(1f)) { onLoad("A") }
            Act("LOAD B", DJ.deckB, DJ.deckBTint, DJ.deckB, Modifier.weight(1f)) { onLoad("B") }
            if (!t.prepared) Act("PREPARE", modifier = Modifier.weight(1f)) { vm.prepare(e); onClose() }
        }
        Label("Playlists", Modifier.padding(top = 12.dp, bottom = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            s.playlists.filter { it != ALL }.forEach { p -> Pill(p, p in e.lists, Modifier.padding(end = 6.dp)) { vm.setInPlaylist(e.vid, p, p !in e.lists) } }
            Pill("+ NEW", false) { onPrompt(Prompt.NewPlaylist(e.vid)) }
        }
        Row(Modifier.padding(top = 12.dp).height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!e.stems) Act("SEPARATE STEMS", modifier = Modifier.weight(1f)) { onPrompt(vm.stemsKey()?.let { Prompt.Stems(e, it) } ?: Prompt.StemsKey(e)) }
            Act("DELETE FROM PHONE", DJ.kill, DJ.killTint, DJ.kill, Modifier.weight(1f)) { onPrompt(Prompt.DeletePhone(e, inCloud)) }
            if (inCloud && s.signedIn) Act("DELETE FROM CLOUD", DJ.kill, DJ.killTint, DJ.kill, Modifier.weight(1f)) { onPrompt(Prompt.DeleteCloud(e.vid, e.title, e.file.name)) }
        }
    }
}

/** ⋯ on a cloud-only track: copy it, copy and load, or delete it from the cloud. */
@Composable
private fun CloudSheet(c: CloudRepository.Track, signedIn: Boolean, onCopy: () -> Unit, onLoad: (String) -> Unit, onDelete: () -> Unit) =
    SheetColumn(DJ.border, c.title, "${source(c.vid)} · in the cloud · not on this phone") {
        Row(Modifier.height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Act("COPY HERE", modifier = Modifier.weight(1f), onClick = onCopy)
            Act("COPY + LOAD A", DJ.deckA, DJ.deckATint, DJ.deckA, Modifier.weight(1f)) { onLoad("A") }
            Act("COPY + LOAD B", DJ.deckB, DJ.deckBTint, DJ.deckB, Modifier.weight(1f)) { onLoad("B") }
        }
        if (signedIn) Row(Modifier.padding(top = 12.dp).height(44.dp)) {
            Act("DELETE FROM CLOUD", DJ.kill, DJ.killTint, DJ.kill, Modifier.fillMaxWidth(0.5f), onClick = onDelete)
        }
    }

@Composable
private fun SheetColumn(bar: Color, title: String, meta: String, content: @Composable () -> Unit) =
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(end = 10.dp).size(4.dp, 22.dp).background(bar))
            Text(title, color = DJ.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(meta, color = DJ.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 14.dp, top = 2.dp, bottom = 12.dp))
        content()
    }

// ---- prompts --------------------------------------------------------------------------------------------

@Composable
private fun PromptDialog(p: Prompt, vm: LoadViewModel, dismiss: () -> Unit, open: (Prompt) -> Unit) {
    when (p) {
        is Prompt.NewPlaylist -> TextPrompt("New playlist", null, "Playlist name", "", "Create", dismiss) { vm.newPlaylist(it, p.vid) }
        is Prompt.StemsKey -> TextPrompt("LALAL.AI key", "From lalal.ai → API → licence key. Stored only on this phone.", "LALAL.AI licence key", vm.stemsKey() ?: "", "Save", dismiss) {
            vm.setStemsKey(it); vm.stemsKey()?.let { k -> open(Prompt.Stems(p.entry, k)) }
        }
        is Prompt.Stems -> {
            var note by remember(p) { mutableStateOf("Checking minutes left…") }
            LaunchedEffect(p) { note = vm.minutesLeft(p.key) }
            AlertDialog(
                onDismissRequest = dismiss,
                title = { Text("Separate vocals / instrumental?") },
                text = { Text("${p.entry.title}\n${clock(p.entry.durationMs)} of audio goes to LALAL.AI and is billed against your plan.\n$note") },
                confirmButton = { TextButton(onClick = { dismiss(); vm.separate(p.entry) }) { Text("Separate") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = { open(Prompt.StemsKey(p.entry)) }) { Text("Change key") }
                        TextButton(onClick = dismiss) { Text("Cancel") }
                    }
                },
            )
        }
        is Prompt.DeletePhone -> Confirm("Delete from this phone?", "${p.entry.title}\n" + if (p.inCloud) "The cloud copy stays." else "It is not in the cloud.", "Delete", dismiss) { vm.deleteFromPhone(p.entry) }
        is Prompt.DeleteCloud -> Confirm("Delete from the cloud?", "${p.title}\nEveryone signed in loses the cloud copy. Copies already on phones stay.", "Delete from cloud", dismiss) {
            vm.deleteFromCloud(p.vid, p.fileName)
        }
    }
}

@Composable
private fun TextPrompt(title: String, message: String?, hint: String, initial: String, confirm: String, dismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column {
                message?.let { Text(it, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(hint) })
            }
        },
        // Dismiss first: the action may open the next prompt.
        confirmButton = { TextButton(onClick = { dismiss(); onConfirm(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Confirm(title: String, message: String, confirm: String, dismiss: () -> Unit, onConfirm: () -> Unit) = AlertDialog(
    onDismissRequest = dismiss,
    title = { Text(title) },
    text = { Text(message) },
    confirmButton = { TextButton(onClick = { dismiss(); onConfirm() }) { Text(confirm, color = DJ.kill) } },
    dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
)

// ---- small parts ----------------------------------------------------------------------------------------

private fun deckColor(deck: String?) = when (deck) { "A" -> DJ.deckA; "B" -> DJ.deckB; else -> DJ.border }

/** Where a track came from, as a row tag: an Instagram reel or a YouTube pick. */
private fun source(vid: String) = if (IgAudio.isId(vid)) "IG" else "YT"

private fun clock(ms: Long): String { val s = (ms / 1000).toInt(); return "%d:%02d".format(s / 60, s % 60) }

/** The source tag small and muted before the title, then small muted tags after it (ST for stems, playlists). */
private fun titled(vid: String, title: String, tags: List<String>, color: Color): AnnotatedString = buildAnnotatedString {
    val small = SpanStyle(color = DJ.muted, fontSize = 9.sp)
    withStyle(small) { append(source(vid) + "  ") }
    withStyle(SpanStyle(color = color)) { append(title) }
    if (tags.isNotEmpty()) withStyle(small) { append("   " + tags.joinToString(" · ")) }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) =
    Text(text.uppercase(), modifier, color = DJ.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.08.em, textAlign = align)

@Composable
private fun Readout(text: String, modifier: Modifier, color: Color) =
    Text(text, modifier, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1)

@Composable
private fun TabBtn(text: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.padding(bottom = 4.dp).fillMaxWidth().height(40.dp).background(DJ.control, shape).border(1.dp, if (on) DJ.text else DJ.border, shape)
            .clickable(onClick = onClick).padding(start = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, color = if (on) DJ.text else DJ.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.06.em) }
}

/** A full-width rail entry (playlist, cloud action). */
@Composable
private fun RailChip(text: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.padding(top = 4.dp).fillMaxWidth().height(36.dp).background(DJ.control, shape).border(1.dp, if (on) DJ.text else DJ.border, shape)
            .clickable(onClick = onClick).padding(start = 10.dp, end = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, color = if (on) DJ.text else DJ.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.06.em, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** A pill chip (sort, playlist in the sheet). */
@Composable
private fun Pill(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier.height(40.dp).widthIn(min = 56.dp).background(DJ.control, shape).border(1.dp, if (on) DJ.text else DJ.border, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (on) DJ.text else DJ.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.06.em, maxLines = 1) }
}

/** A light row button: 40 dp square unless [width] says otherwise. */
@Composable
private fun RowBtn(
    text: String, color: Color = DJ.text, bg: Color = DJ.control, border: Color = DJ.border, width: Dp = 40.dp, height: Dp = 40.dp,
    fontSize: TextUnit = 13.sp, modifier: Modifier = Modifier.padding(start = 4.dp), onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.size(width, height).background(bg, shape).border(1.dp, border, shape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = fontSize, fontWeight = FontWeight.Bold, letterSpacing = 0.04.em, maxLines = 1)
    }
}

/** A sheet action: pill-shaped, the full height of its row. */
@Composable
private fun Act(text: String, color: Color = DJ.text, bg: Color = DJ.control, border: Color = DJ.border, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.fillMaxHeight().background(bg, shape).border(1.dp, border, shape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.06.em, maxLines = 1)
    }
}

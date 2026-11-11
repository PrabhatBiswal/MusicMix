package com.prabhat.djmix

import android.app.Dialog
import android.content.res.ColorStateList
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.prabhat.djmix.audio.BpmAnalyzer
import com.prabhat.djmix.audio.Deck
import com.prabhat.djmix.audio.Fx
import com.prabhat.djmix.audio.HookFinder
import com.prabhat.djmix.audio.Loudness
import com.prabhat.djmix.audio.Mashup
import com.prabhat.djmix.audio.Mixer
import com.prabhat.djmix.audio.PcmTrack
import com.prabhat.djmix.audio.Recorder
import com.prabhat.djmix.audio.StockSamples
import com.prabhat.djmix.audio.Strip
import com.prabhat.djmix.library.CloudRepository
import com.prabhat.djmix.library.Library
import com.prabhat.djmix.library.Prep
import com.prabhat.djmix.library.TrackMeta
import com.prabhat.djmix.ui.DualWaveformView
import com.prabhat.djmix.ui.KnobView
import com.prabhat.djmix.ui.load.LoadHost
import com.prabhat.djmix.ui.load.LoadScreenFragment
import com.prabhat.djmix.ui.MeterView
import com.prabhat.djmix.ui.VFaderView
import com.prabhat.djmix.instagram.IgAudio
import com.prabhat.djmix.instagram.InstagramLoginSheet
import com.prabhat.djmix.youtube.YouTubePickerSheet
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.floor

/**
 * The console. @AndroidEntryPoint: Hilt fills the @Inject fields ([prep], [cloud]) before onCreate runs, with the
 * same singletons the Load screen's ViewModels get.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity(), LoadHost {
    @Inject lateinit var prep: Prep
    @Inject lateinit var cloud: CloudRepository
    private val mixer = Mixer()
    private lateinit var panels: List<DeckPanel>
    private lateinit var waves: DualWaveformView
    private lateinit var masterL: MeterView
    private lateinit var masterR: MeterView
    private lateinit var splitBtn: MaterialButton
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val prefs by lazy { getSharedPreferences("console", MODE_PRIVATE) }
    private lateinit var meterA: MeterView
    private lateinit var meterB: MeterView
    private lateinit var recBtn: MaterialButton
    private lateinit var queueText: TextView
    private var masterLevel = 0f
    private var levelA = 0f
    private var levelB = 0f
    private val handler = Handler(Looper.getMainLooper())
    private val sampleNames = Array(Mixer.SAMPLE_SLOTS) { "" }
    /** Guards against double taps: one menu, one decode per track or pad, one file picker (the Load screen checks its tag). */
    private var menu: Dialog? = null
    private val loading = HashSet<String>()
    /** Slots holding a tempo-synced loop (tap toggles it) rather than a one-shot (tap retriggers it). */
    private val sampleLoop = BooleanArray(Mixer.SAMPLE_SLOTS)
    // Views the mashup moves: channel faders, HI MID LOW FLT per strip, headphone cue buttons.
    private lateinit var faderA: VFaderView
    private lateinit var faderB: VFaderView
    private lateinit var pflA: MaterialButton
    private lateinit var pflB: MaterialButton
    private val eqKnobs = HashMap<Strip, List<KnobView>>()

    // Tokens
    private val colA by lazy { getColor(R.color.deck_a) }
    private val tintA by lazy { getColor(R.color.deck_a_tint) }
    private val colB by lazy { getColor(R.color.deck_b) }
    private val tintB by lazy { getColor(R.color.deck_b_tint) }
    private val colLoop by lazy { getColor(R.color.loop) }
    private val tintLoop by lazy { getColor(R.color.loop_tint) }
    private val colKill by lazy { getColor(R.color.kill) }
    private val tintKill by lazy { getColor(R.color.kill_tint) }
    private val colText by lazy { getColor(R.color.text) }
    private val colMuted by lazy { getColor(R.color.muted) }
    private val colBorder by lazy { getColor(R.color.border) }
    private val colControl by lazy { getColor(R.color.control) }
    private val colGround by lazy { getColor(R.color.ground) }

    private var onFilePicked: ((Uri) -> Unit)? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val cb = onFilePicked; onFilePicked = null
        if (uri != null) cb?.invoke(uri)
    }

    private fun pickFile(cb: (Uri) -> Unit) {
        if (onFilePicked != null) return // the picker is already up
        onFilePicked = cb; picker.launch(arrayOf("audio/*"))
    }

    /** One tap-opened dialog at a time: a newer one replaces the one still up (a menu's action may open the next). */
    private fun showOnce(d: Dialog) { menu?.takeIf { it.isShowing }?.dismiss(); menu = d; d.show() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        // Immersive: the console needs every dp of height; a swipe from the edge shows the bars briefly.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val p = dp(8)
            v.setPadding(cut.left + p, cut.top + p, cut.right + p, cut.bottom + p)
            insets
        }
        waves = findViewById(R.id.waves)
        waves.deckA = mixer.a; waves.deckB = mixer.b
        val a = DeckPanel(findViewById(R.id.deckA), findViewById(R.id.readoutA), mixer.a, mixer.stripA, mixer.fxA, "A", colA, tintA)
        val b = DeckPanel(findViewById(R.id.deckB), findViewById(R.id.readoutB), mixer.b, mixer.stripB, mixer.fxB, "B", colB, tintB)
        a.other = mixer.b; b.other = mixer.a
        panels = listOf(a, b)
        buildMixer()
        masterL = findViewById(R.id.masterL); masterR = findViewById(R.id.masterR)
        splitBtn = findViewById(R.id.btnSplit)
        splitBtn.setOnClickListener { cycleCue() }
        findViewById<KnobView>(R.id.mixKnob).apply { bipolar = false; label = "MIX"; accent = colText; value = mixer.cueMix; onChange = { mixer.cueMix = it } }
        // Starts at unity (the level before the knob existed) and is saved; squared for a usable taper (0.7 = -6 dB).
        findViewById<KnobView>(R.id.phonesKnob).apply {
            bipolar = false; label = "PHONES"; accent = colText; default = 1f; value = prefs.getFloat("phones", 1f)
            mixer.phonesGain = value * value
            onChange = { mixer.phonesGain = it * it; prefs.edit().putFloat("phones", it).apply() }
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        refreshOutputs()
        toggle(findViewById(R.id.btnSharp), colText, colControl, { mixer.sharp }) { mixer.sharp = it }
        pflA = findViewById(R.id.btnPflA); pflB = findViewById(R.id.btnPflB)
        toggle(pflA, colA, tintA, { mixer.cueA }) { mixer.cueA = it }
        toggle(pflB, colB, tintB, { mixer.cueB }) { mixer.cueB = it }
        recBtn = findViewById(R.id.btnRec)
        recBtn.setOnClickListener { toggleRecording() }
        queueText = findViewById(R.id.tvQueue)
        queueText.setOnClickListener { if (mash.isNotEmpty()) stopMashup() else showLoad("A") }
        loadStockSamples()
        mixer.start()
        handler.post(tick)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Instagram's Share sheet (ACTION_SEND text) adds a reel's audio. Debug builds also take remote control from adb
     * scripts: `am start -n com.prabhat.djmix/.MainActivity --es queue "vid,vid"` queues YouTube picks,
     * `--es prepare "vid,vid"` decodes and analyses library tracks in the background, `--es fetch all` copies every cloud
     * track the phone lacks (signed in), `--es ig "<link>"` adds a reel, `--es mashup "vid,vid@ROLL,vid:32"` plays a
     * mashup (see [startMashup]). Release builds ignore those: the activity is exported, and other apps must not drive it.
     */
    private fun handleIntent(i: android.content.Intent?) {
        i?.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let { IgAudio.idFromText(it) }?.let { addFromInstagram(it) }
        if (i == null || !BuildConfig.DEBUG) return
        fun list(key: String) = i.getStringExtra(key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (i.getStringExtra("fetch") == "all") {
            val here = Library.list(this).map { it.vid }.toSet()
            cloud.tracks.value?.filter { it.vid !in here }?.forEach { prep.enqueueFetch(it.vid, it.title) }
        }
        i.getStringExtra("ig")?.let { IgAudio.idFromText(it) }?.let { addFromInstagram(it) }
        list("queue").forEach { prep.enqueue(it, it) }
        list("prepare").forEach { vid -> Library.read(this, Library.indexFile(this, vid))?.let { prep.enqueuePrepare(it.vid, it.title) } }
        i.getStringExtra("mashup")?.let { startMashup(it) }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        mixer.stop()
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            panels.forEach { it.refresh() }
            waves.invalidate()
            masterLevel = maxOf(mixer.masterPeak, masterLevel * METER_DECAY)
            masterL.level = masterLevel; masterR.level = masterLevel
            if (mixer.cueMode == Mixer.CueMode.DUAL && mixer.dualOk == false) {
                mixer.cueMode = Mixer.CueMode.OFF; paintCue()
                Toast.makeText(this@MainActivity, "This phone sends both outputs to one device. Use SPLIT with a splitter cable.", Toast.LENGTH_LONG).show()
            }
            levelA = maxOf(mixer.stripA.peak, levelA * METER_DECAY); meterA.level = levelA
            levelB = maxOf(mixer.stripB.peak, levelB * METER_DECAY); meterB.level = levelB
            val ratio = loopRatio()
            for (i in 0 until Mixer.SAMPLE_SLOTS) if (sampleLoop[i] && mixer.samples[i].playing) mixer.samples[i].tempo = ratio
            mixer.recorder?.let { recBtn.text = "● " + clock((System.currentTimeMillis() - it.startedAt).toDouble()) }
            if (mash.isNotEmpty()) syncControls()
            val q = mashStatus() ?: prep.summary()
            queueText.visibility = if (q == null) View.GONE else View.VISIBLE
            if (q != null) queueText.text = q
            handler.postDelayed(this, 50)
        }
    }

    // ---- cue outputs -------------------------------------------------------------------------------------

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshOutputs()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refreshOutputs()
    }

    /**
     * DUAL needs a Bluetooth speaker for the room and a wired or USB earphone for the cue, both connected. The last
     * mode the user chose is remembered, so DUAL comes back by itself once both outputs are there again. With no
     * choice saved (fresh install or cleared data) DUAL is the default: both outputs connected means the user wants it.
     */
    private fun refreshOutputs() {
        val devs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        mixer.masterDevice = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        mixer.cueDevice = devs.firstOrNull { it.type in WIRED_TYPES }
        val wanted = runCatching { Mixer.CueMode.valueOf(prefs.getString("cueMode", "DUAL")!!) }.getOrDefault(Mixer.CueMode.OFF)
        if (mixer.cueMode == Mixer.CueMode.DUAL && !dualAvailable()) {
            mixer.cueMode = Mixer.CueMode.OFF
            Toast.makeText(this, "DUAL off: an output went away", Toast.LENGTH_SHORT).show()
        } else if (mixer.cueMode == Mixer.CueMode.OFF && wanted != Mixer.CueMode.OFF && (wanted != Mixer.CueMode.DUAL || dualAvailable())) {
            mixer.cueMode = wanted
        }
        paintCue()
    }

    private fun dualAvailable() = mixer.masterDevice != null && mixer.cueDevice != null

    /** OFF → SPLIT → DUAL (when both outputs are present) → OFF. */
    private fun cycleCue() {
        val next = when (mixer.cueMode) {
            Mixer.CueMode.OFF -> Mixer.CueMode.SPLIT
            Mixer.CueMode.SPLIT -> if (dualAvailable()) Mixer.CueMode.DUAL else Mixer.CueMode.OFF
            Mixer.CueMode.DUAL -> Mixer.CueMode.OFF
        }
        if (mixer.cueMode == Mixer.CueMode.SPLIT && next == Mixer.CueMode.OFF)
            Toast.makeText(this, "DUAL needs a Bluetooth speaker and USB-C or wired earphones connected at the same time", Toast.LENGTH_LONG).show()
        mixer.cueMode = next
        prefs.edit().putString("cueMode", next.name).apply()
        paintCue()
    }

    private fun paintCue() {
        val mode = mixer.cueMode
        splitBtn.text = if (mode == Mixer.CueMode.DUAL) "DUAL" else "SPLIT"
        splitBtn.lit(mode != Mixer.CueMode.OFF, colA, tintA)
    }

    // ---- mixer -------------------------------------------------------------------------------------------

    private fun buildMixer() {
        // ponytail: TRIM has no knob any more (four 40 dp knobs are all the column fits); Strip.trimDb stays at 0.
        fun knobs(column: LinearLayout, strip: Strip, accent: Int) {
            val px = resources.getDimensionPixelSize(R.dimen.knob)
            val list = ArrayList<KnobView>().also { eqKnobs[strip] = it }
            fun add(label: String, bind: (KnobView) -> Unit) {
                if (column.childCount > 0) column.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(px, 0, 1f) })
                column.addView(KnobView(this).apply { layoutParams = LinearLayout.LayoutParams(px, px); this.accent = accent; this.label = label; bind(this) }.also { list += it })
            }
            add("HI") { eqKnob(it, { v -> strip.highDb = v }, { v -> strip.killHigh = v }) }
            add("MID") { eqKnob(it, { v -> strip.midDb = v }, { v -> strip.killMid = v }) }
            add("LOW") { eqKnob(it, { v -> strip.lowDb = v }, { v -> strip.killLow = v }) }
            add("FLT") { k -> k.onChange = { v -> val x = (v - 0.5) * 2.0; strip.filter = x; panels[if (strip === mixer.stripA) 0 else 1].onFilter(x) } }
        }
        knobs(findViewById(R.id.knobsA), mixer.stripA, colA)
        knobs(findViewById(R.id.knobsB), mixer.stripB, colB)
        faderA = findViewById<VFaderView>(R.id.faderA).apply { value = 1f; onChange = { mixer.a.volume = it } }
        faderB = findViewById<VFaderView>(R.id.faderB).apply { value = 1f; onChange = { mixer.b.volume = it } }
        meterA = findViewById(R.id.meterA); meterB = findViewById(R.id.meterB)
        findViewById<SeekBar>(R.id.sbCross).onProgress { mixer.crossfader = it / 100f }
    }

    /** Knob 0..1: left half cuts to the kill level, right half boosts; long-press kills the band. */
    private fun eqKnob(k: KnobView, setDb: (Double) -> Unit, setKill: (Boolean) -> Unit) {
        k.onChange = { v -> setDb(if (v < 0.5) (v - 0.5) / 0.5 * -Strip.KILL_DB else (v - 0.5) / 0.5 * Strip.BOOST_DB) }
        k.onLongPress = { k.killed = !k.killed; setKill(k.killed) }
    }

    private fun toggle(b: MaterialButton, color: Int, tint: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        b.lit(get(), color, tint)
        b.setOnClickListener { set(!get()); b.lit(get(), color, tint) }
    }

    /** Pad / toggle styling: lit = accent stroke on its tint; off = neutral, text muted when [dimOff]. */
    private fun MaterialButton.lit(on: Boolean, color: Int, tint: Int, dimOff: Boolean = true) {
        strokeColor = ColorStateList.valueOf(if (on) color else colBorder)
        backgroundTintList = ColorStateList.valueOf(if (on) tint else colControl)
        setTextColor(if (on) color else if (dimOff) colMuted else colText)
    }

    // ---- loading -----------------------------------------------------------------------------------------

    private fun panel(name: String) = panels.first { it.name == name }

    /** The Compose Load screen; a second tap while it is up does nothing. */
    private fun showLoad(deck: String) {
        if (supportFragmentManager.findFragmentByTag(LoadScreenFragment.TAG) != null) return
        LoadScreenFragment.newInstance(deck).show(supportFragmentManager, LoadScreenFragment.TAG)
    }

    // LoadHost: what the Load screen asks of the decks.
    override fun loadedKey(deck: String) = panel(deck).deck.track?.key
    override fun onLoad(entry: Library.Entry, deck: String) = loadInto(panel(deck), Uri.fromFile(entry.file), entry.vid, entry.title)
    override fun onFetch(vid: String, deck: String) {
        val p = panel(deck); p.title.text = "Downloading from cloud…"
        lifecycleScope.launch {
            runCatching { cloud.download(vid) }
                .onSuccess { e -> loadInto(p, Uri.fromFile(e.file), e.vid, e.title) }
                .onFailure { p.title.text = p.loadedName.ifEmpty { "No track" }; Toast.makeText(this@MainActivity, "Cloud: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    override fun onYouTube(deck: String) = addFromYouTube(panel(deck))
    override fun onFiles(deck: String) = pickFile { uri -> loadInto(panel(deck), uri, null, displayName(uri)) }
    override fun deckBpm(deck: String) = panel(deck).deck.let { if (it.bpm > 0) it.bpm * it.tempo else 0.0 }
    override fun idleDeck() = if (!mixer.a.playing) "A" else "B"

    /** Picks go to the background prep queue; the picker stays open for the next song. */
    private fun addFromYouTube(panel: DeckPanel) {
        if (supportFragmentManager.findFragmentByTag("yt_picker") != null) return
        val sheet = YouTubePickerSheet()
        sheet.onPicked = { pick ->
            val title = pick.title.ifBlank { pick.videoId }
            val queued = prep.enqueue(pick.videoId, title)
            Toast.makeText(this, if (queued) "Queued: $title" else "Already in the queue", Toast.LENGTH_SHORT).show()
        }
        sheet.show(supportFragmentManager, "yt_picker")
    }

    /** A shared Instagram link: the login page first while there is no session (closing it unsigned still tries the post). */
    private fun addFromInstagram(vid: String) {
        val queue = { Toast.makeText(this, if (prep.enqueue(vid, vid)) "Queued: $vid" else "Already in the queue", Toast.LENGTH_SHORT).show() }
        if (InstagramLoginSheet.loggedIn() || supportFragmentManager.findFragmentByTag("ig_login") != null) queue()
        else InstagramLoginSheet().apply { onDone = queue }.show(supportFragmentManager, "ig_login")
    }

    /** Decodes (cached), analyses (cached) and measures the loudness of [uri] off the main thread, then loads it and runs [then]. */
    private fun loadInto(panel: DeckPanel, uri: Uri, key: String?, name: String, then: () -> Unit = {}) {
        val id = key ?: uri.toString()
        if (!loading.add(id)) { Toast.makeText(this, "Already loading", Toast.LENGTH_SHORT).show(); return }
        panel.title.text = "Loading…"
        Thread {
            val result = runCatching {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val cached = key != null && File(cacheDir, "$key.pcm").length() > 8
                val t = if (key == null) PcmTrack.load(this, uri) else PcmTrack.load(this, uri, key)
                val t1 = android.os.SystemClock.elapsedRealtime()
                val saved = TrackMeta.load(this, t.key)
                    ?: BpmAnalyzer.analyze(t).let { TrackMeta.Meta(it.bpm, it.firstBeat, DoubleArray(Deck.HOT_CUES) { -1.0 }, Double.NaN) }
                val t2 = android.os.SystemClock.elapsedRealtime()
                val meta = if (saved.gainDb.isNaN()) {
                    val g = Loudness.gainDb(t)
                    TrackMeta.saveGain(this, t.key, g)
                    TrackMeta.Meta(saved.bpm, saved.firstBeat, saved.hotCues, g)
                } else saved
                val t3 = android.os.SystemClock.elapsedRealtime()
                // Hook on pad 1, so the chorus is one tap away (and where a mashup enters): for a fresh track, and for one
                // whose pad 1 the older loudness-rise finder placed. A cue set by hand stays.
                val old = HookFinder.riseFrame(t, meta.bpm, meta.firstBeat)
                if (meta.hotCues.all { it < 0 } || (old != null && meta.hotCues[0] == old))
                    HookFinder.hookFrame(t, meta.bpm, meta.firstBeat)?.let { meta.hotCues[0] = it }
                val t4 = android.os.SystemClock.elapsedRealtime()
                val stems = key?.let { Library.stemFiles(this, it) }?.let { (v, i) ->
                    PcmTrack.load(this, Uri.fromFile(v), "$key.vox") to PcmTrack.load(this, Uri.fromFile(i), "$key.inst")
                }
                Log.i(TAG, "load $name: ${if (cached) "cached" else "DECODED"} ${t1 - t0} ms, bpm ${t2 - t1} ms, loudness ${t3 - t2} ms, hook ${clock(meta.hotCues[0] / t.sampleRate * 1000)} ${t4 - t3} ms, stems ${android.os.SystemClock.elapsedRealtime() - t4} ms")
                Triple(t, meta, stems)
            }
            runOnUiThread {
                loading.remove(id)
                result.onSuccess { (t, meta, stems) -> panel.load(t, meta, name, stems); then() }
                    .onFailure {
                        Log.w(TAG, "load failed", it)
                        panel.title.text = panel.loadedName.ifEmpty { "No track" }
                        Toast.makeText(this, "Can't decode: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
        }.start()
    }

    // ---- sampler -----------------------------------------------------------------------------------------

    /** The stock bank fills every empty pad at start: four synced loops, four one-shots. */
    private fun loadStockSamples() {
        Thread {
            val pack = StockSamples.pack()
            runOnUiThread { pack.forEachIndexed { i, s -> if (i < Mixer.SAMPLE_SLOTS && mixer.samples[i].track == null) putSample(i, s.track, s.name, s.loop) } }
        }.start()
    }

    private fun showSampleMenu(i: Int) {
        val entries = Library.list(this)
        val stock = StockSamples.NAMES
        val items = arrayOf("Open a file…") + stock.map { "Stock · $it" } + entries.map { it.title }
        showOnce(AlertDialog.Builder(this).setTitle("Sample pad ${i + 1}").setItems(items) { _, k ->
            when {
                k == 0 -> pickFile { uri -> loadSample(i, uri, null, displayName(uri)) }
                k <= stock.size -> loadStock(i, k - 1)
                else -> entries[k - 1 - stock.size].let { loadSample(i, Uri.fromFile(it.file), it.vid, it.title) }
            }
        }.create())
    }

    private fun loadStock(slot: Int, index: Int) {
        if (!loading.add("slot$slot")) return
        sampleNames[slot] = "…"
        Thread {
            val s = StockSamples.sound(index)
            runOnUiThread { loading.remove("slot$slot"); putSample(slot, s.track, s.name, s.loop) }
        }.start()
    }

    /** A sample slot is a plain deck without analysis: one-shots play from the start, loops cycle in time. */
    private fun loadSample(i: Int, uri: Uri, key: String?, name: String) {
        if (!loading.add("slot$i")) return
        sampleNames[i] = "…"
        Thread {
            val result = runCatching { if (key == null) PcmTrack.load(this, uri) else PcmTrack.load(this, uri, key) }
            runOnUiThread {
                loading.remove("slot$i")
                result.onSuccess { t -> putSample(i, t, name, loop = false) }
                    .onFailure { sampleNames[i] = ""; Toast.makeText(this, "Can't decode: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun putSample(slot: Int, t: PcmTrack, name: String, loop: Boolean) {
        mixer.samples[slot].apply { load(t); tempo = 1.0 }
        sampleNames[slot] = name; sampleLoop[slot] = loop
    }

    private fun sampleTap(i: Int) {
        val s = mixer.samples[i]
        when {
            s.track == null -> showSampleMenu(i)
            sampleLoop[i] -> if (s.playing) s.playing = false else startLoop(i)
            else -> { s.jump(0.0); s.playing = true }
        }
    }

    /** Loops run at master BPM / stock BPM and start on the master deck's next beat. */
    private fun startLoop(i: Int) {
        val s = mixer.samples[i]
        val t = s.track ?: return
        s.setLoop(0.0, (t.frames - 1).toDouble()); s.jump(0.0); s.tempo = loopRatio()
        val m = masterDeck()
        val fpb = m?.framesPerBeat ?: 0.0
        val wait = if (m != null && m.playing && fpb > 0) {
            val b = m.beatAt(m.pos)
            ((1 - (b - floor(b))) * fpb / m.track!!.sampleRate / m.tempo * 1000).toLong()
        } else 0L
        // ponytail: quantised on the UI thread (±10 ms); move the start into the mixer for sample-accurate launches
        handler.postDelayed({ s.playing = true }, wait)
    }

    /** The deck sampler loops follow: a playing deck with a grid (A first), else deck A if analysed. */
    private fun masterDeck(): Deck? = listOf(mixer.a, mixer.b).firstOrNull { it.playing && it.bpm > 0 } ?: mixer.a.takeIf { it.bpm > 0 }

    private fun loopRatio(): Double = masterDeck()?.let { (it.bpm * it.tempo / StockSamples.BPM).coerceIn(0.5, 2.0) } ?: 1.0

    // ---- mashup ------------------------------------------------------------------------------------------

    /** One song of a mashup: [move] is the transition into it (null = rotation), [beats] its length from drop to drop. */
    private class Song(val entry: Library.Entry, val move: Mashup.Move?, val beats: Double)
    private var mash: List<Song> = emptyList()
    /** The song on the clock deck; songs alternate A, B, A… */
    private var mashIndex = 0

    /**
     * `--es mashup "vid,vid@ROLL,vid@BLEND:32"`: the songs in that order, unattended and recorded, each entering on hot
     * cue 1. `@MOVE` picks the transition into a song (BLEND, FILTER, CUT, HOOKS, STOP, ROLL, ECHO; else BLEND, or STOP
     * across a tempo gap too wide to blend), `:BEATS` how long it plays from its drop to the next (a multiple of 16,
     * default 128). Every song must be in the library and prepared.
     */
    private fun startMashup(spec: String) {
        val missing = ArrayList<String>()
        val songs = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { item ->
            val vid = item.substringBefore('@').substringBefore(':')
            val move = item.substringAfter('@', "").substringBefore(':').uppercase().let { m -> Mashup.Move.values().firstOrNull { it.name == m } }
            val beats = item.substringAfter(':', "").toIntOrNull()?.let { (it / 16).coerceAtLeast(1) * 16.0 } ?: Mashup.SEGMENT
            Library.read(this, Library.indexFile(this, vid))?.let { Song(it, move, beats) }.also { if (it == null) missing += vid }
        }
        val raw = songs.filter { !Prep.prepared(this, it.entry.vid) }
        raw.forEach { prep.enqueuePrepare(it.entry.vid, it.entry.title) }
        val problem = when {
            missing.isNotEmpty() -> "Not in the library: ${missing.joinToString()}"
            raw.isNotEmpty() -> "Preparing ${raw.size} songs first: start the mashup again when the queue is empty"
            songs.any { (TrackMeta.load(this, it.entry.vid)?.bpm ?: 0.0) <= 0 } -> "A song has no beat grid"
            songs.size < 2 -> "A mashup needs two songs"
            else -> null
        }
        if (problem != null) { Toast.makeText(this, problem, Toast.LENGTH_LONG).show(); return }
        stopMashup()
        mixer.a.playing = false; mixer.b.playing = false
        findViewById<SeekBar>(R.id.sbCross).progress = 50
        mash = songs; mashIndex = 0
        loadSong(0) {
            loadSong(1) {
                mixer.mashup.riser = sampleNames.indexOf("RISER").takeIf { it >= 0 }?.let { mixer.samples[it] }
                mixer.mashup.start(mixer.a)
                if (mixer.recorder == null) toggleRecording()
                mashNext()
            }
        }
    }

    private fun mashPanel(i: Int) = panels[i % 2]

    private fun loadSong(i: Int, then: () -> Unit) = mash[i].entry.let { e ->
        loadInto(mashPanel(i), Uri.fromFile(e.file), e.vid, e.title) { if (mash.isNotEmpty()) then() }
    }

    /** Schedules the hand-over from the song on the clock to the next (already loaded), or the ending after the last. */
    private fun mashNext() {
        if (mash.isEmpty()) return
        val y = mashPanel(mashIndex).deck
        val beats = mash[mashIndex].beats
        if (mashIndex + 1 >= mash.size) mixer.mashup.end(y, beats) { handler.post { mashDone() } }
        else mixer.mashup.handOver(y, mashPanel(mashIndex + 1).deck, mash[mashIndex + 1].move, beats) { handler.post { mashAdvance() } }
    }

    /** The old song is out: its deck takes the song after next. */
    private fun mashAdvance() {
        if (mash.isEmpty()) return
        mashIndex++
        if (mashIndex + 1 < mash.size) loadSong(mashIndex + 1) { mashNext() } else mashNext()
    }

    private fun mashDone() {
        if (mash.isEmpty()) return
        mash = emptyList()
        if (mixer.recorder != null) toggleRecording()
    }

    /** Tapping the status line: the decks keep playing as they are and the DJ takes over. */
    private fun stopMashup() { mixer.mashup.stop(); mash = emptyList() }

    private fun mashStatus(): String? = if (mash.isEmpty()) null else mixer.mashup.let {
        "■ MASHUP ${mashIndex + 1}/${mash.size} · ${it.nextLabel} in ${maxOf(0, (it.nextDrop - it.beat).toInt())}"
    }

    /** During a mashup the engine moves the controls; the views follow it. */
    private fun syncControls() {
        faderA.value = mixer.a.volume; faderB.value = mixer.b.volume
        for ((strip, k) in eqKnobs) { k[0].killed = strip.killHigh; k[1].killed = strip.killMid; k[2].killed = strip.killLow; k[3].value = (strip.filter / 2 + 0.5).toFloat() }
        pflA.lit(mixer.cueA, colA, tintA); pflB.lit(mixer.cueB, colB, tintB)
        panels.forEach { it.sync() }
    }

    // ---- recording ---------------------------------------------------------------------------------------

    private fun toggleRecording() {
        val r = mixer.recorder
        if (r == null) {
            val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
            val name = "mix-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            mixer.recorder = Recorder(Mixer.RATE, File(dir, "$name.wav"))
            recBtn.lit(true, colKill, tintKill)
        } else {
            mixer.recorder = null
            recBtn.text = "● REC"; recBtn.lit(false, colKill, tintKill); recBtn.setTextColor(colKill)
            Toast.makeText(this, "Encoding…", Toast.LENGTH_SHORT).show()
            Thread {
                val result = runCatching {
                    val wav = r.stop()
                    val m4a = File(wav.path.removeSuffix(".wav") + ".m4a")
                    Recorder.encodeToM4a(wav, m4a, Mixer.RATE)
                    wav.delete()
                    cloud.backupMix(m4a) // only when signed in with auto backup on
                    Recorder.publish(this, m4a) ?: m4a.path
                }
                runOnUiThread {
                    result.onSuccess { Toast.makeText(this, "Saved $it", Toast.LENGTH_LONG).show() }
                        .onFailure { Log.w(TAG, "recording failed", it); Toast.makeText(this, "Recording failed: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }.start()
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "Track"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun clock(ms: Double): String { val s = (ms / 1000).toInt(); return "%d:%02d".format(s / 60, s % 60) }

    private fun SeekBar.onProgress(f: (Int) -> Unit) = setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = f(p)
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    })

    // ---- deck panel --------------------------------------------------------------------------------------

    private enum class PadMode(val label: String) { HOTCUE("HOT"), LOOP("LOOP"), JUMP("JUMP"), SAMPLER("SMP"), FX("FX") }

    /** One deck's controls: [root] is its deck_panel, [head] its deck_readout in the top bar. */
    private inner class DeckPanel(
        root: View, head: View, val deck: Deck, val strip: Strip, val fx: Fx, val name: String, val accent: Int, val tint: Int,
    ) {
        var other: Deck? = null
        var loadedName = ""
        val title: TextView = head.findViewById(R.id.tvTitle)
        private val bpmText: TextView = head.findViewById(R.id.tvBpm)
        private val timeText: TextView = root.findViewById(R.id.tvTime)
        private val key: MaterialButton = head.findViewById(R.id.btnKey)
        private val slip: MaterialButton = head.findViewById(R.id.btnSlip)
        private val quant: MaterialButton = head.findViewById(R.id.btnQuant)
        private val play: MaterialButton = root.findViewById(R.id.btnPlay)
        private val sync: MaterialButton = root.findViewById(R.id.btnSync)
        private val tempo: VFaderView = root.findViewById(R.id.tempo)
        private val vox: KnobView = root.findViewById(R.id.vox)
        private val tempoText: TextView = root.findViewById(R.id.tvTempo)
        private val fxOn: MaterialButton = root.findViewById(R.id.btnFxOn)
        private val beatBtn: MaterialButton = root.findViewById(R.id.btnBeat)
        private val padRow2: LinearLayout = root.findViewById(R.id.padRow2)
        private val fxRow: LinearLayout = root.findViewById(R.id.fxRow)
        private val pads: List<MaterialButton>
        private val tabs = ArrayList<TextView>()
        private val tabLines = ArrayList<View>()
        private var mode = PadMode.HOTCUE
        private var beatIndex = 2
        private var synced = false
        private var cfx = false
        private var loopInPending = false
        private var lastBeatLoop = 0.0

        init {
            head.findViewById<View>(R.id.bar).setBackgroundColor(accent); bpmText.setTextColor(accent)
            if (name == "B") { // the tempo fader sits on the outer edge: deck B's goes to the right
                val col = root.findViewById<View>(R.id.tempoCol)
                val parent = col.parent as ViewGroup
                parent.removeView(col); parent.addView(col)
                (col.layoutParams as LinearLayout.LayoutParams).apply { marginStart = marginEnd; marginEnd = 0 }
            }
            head.setOnClickListener { showLoad(name) }
            play.setOnClickListener { deck.togglePlay() }
            root.findViewById<MaterialButton>(R.id.btnCue).setOnClickListener { deck.cue() }
            root.findViewById<MaterialButton>(R.id.btnCue).lit(false, accent, tint, dimOff = false)
            sync.setOnClickListener { other?.let { deck.syncTo(it); synced = true; showTempo() } }
            key.setOnClickListener { deck.keylock = !deck.keylock }
            slip.setOnClickListener { deck.slip = !deck.slip }
            quant.setOnClickListener { deck.quantize = !deck.quantize }
            tempo.detent = true; tempo.thumbColor = accent; tempo.value = 0.5f
            tempo.onChange = { v -> deck.tempo = 1 + (v - 0.5) * 2 * Deck.MAX_SYNC; synced = false; showTempo() }
            vox.accent = accent; vox.label = "VOX"
            vox.onChange = { v -> applyVox(v) }

            val tabRow = root.findViewById<LinearLayout>(R.id.tabs)
            PadMode.values().forEach { m ->
                val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f) }
                val tv = TextView(this@MainActivity).apply {
                    text = m.label; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.tab_sp)); letterSpacing = 0.08f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                    setOnClickListener { setMode(m) }
                }
                val line = View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)) }
                col.addView(tv); col.addView(line); tabRow.addView(col)
                tabs += tv; tabLines += line
            }
            val row1 = root.findViewById<LinearLayout>(R.id.padRow1)
            pads = List(8) { i ->
                val row = if (i < 4) row1 else padRow2
                (LayoutInflater.from(this@MainActivity).inflate(R.layout.pad, row, false) as MaterialButton).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = if (i % 4 == 3) 0 else dp(4)
                    setOnClickListener { padTap(i) }
                    setOnLongClickListener { padLong(i); true }
                    row.addView(this)
                }
            }
            root.findViewById<SeekBar>(R.id.sbDepth).onProgress { if (!cfx) fx.depth = it / 100f }
            fxOn.setOnClickListener { fx.on = !fx.on; refresh() }
            beatBtn.setOnClickListener { setBeat((beatIndex + 1) % Fx.BEATS.size) }
            beatBtn.setOnLongClickListener { setBeat((beatIndex + Fx.BEATS.size - 1) % Fx.BEATS.size); true }
            setBeat(beatIndex)
            setMode(PadMode.HOTCUE)
        }

        /** The mashup moved the tempo and the VOX: show it. */
        fun sync() { showTempo(); vox.value = (strip.vox / 2 + 0.5).toFloat() }

        /** The mixer's filter knob moved; with CFX on it also drives the macro. */
        fun onFilter(x: Double) { if (cfx) applyCfx(x) }

        /** The outer knob: real stems when the track has them (left = instrumental, right = acapella), else the centre-cut. */
        private fun applyVox(v: Float) {
            val x = ((v - 0.5) * 2).toDouble()
            if (deck.hasStems) {
                deck.vocGain = minOf(1.0, 1 + x).toFloat(); deck.instGain = minOf(1.0, 1 - x).toFloat(); strip.vox = 0.0
            } else {
                deck.vocGain = 1f; deck.instGain = 1f; strip.vox = x
            }
        }

        fun load(t: PcmTrack, meta: TrackMeta.Meta, name: String, stems: Pair<PcmTrack, PcmTrack>? = null) {
            deck.load(t)
            stems?.let { deck.loadStems(it.first, it.second) }
            vox.label = if (stems != null) "STEM" else "VOX"
            applyVox(vox.value)
            deck.bpm = meta.bpm; deck.firstBeat = meta.firstBeat
            strip.trimDb = meta.gainDb.takeIf { !it.isNaN() } ?: 0.0
            meta.hotCues.copyInto(deck.hotCues)
            deck.onChanged = { TrackMeta.save(this@MainActivity, t.key, deck) }
            deck.onChanged?.invoke()
            loadedName = name
            title.text = name
            synced = false
        }

        private fun setMode(m: PadMode) {
            mode = m
            tabs.forEachIndexed { i, tv -> tv.setTextColor(if (PadMode.values()[i] == m) accent else colMuted); tabLines[i].setBackgroundColor(if (PadMode.values()[i] == m) accent else 0) }
            padRow2.visibility = if (m == PadMode.FX) View.GONE else View.VISIBLE
            fxRow.visibility = if (m == PadMode.FX) View.VISIBLE else View.GONE
            refresh()
        }

        private fun setFxType(t: Fx.Type) { fx.type = t }

        private fun setBeat(i: Int) {
            beatIndex = i.coerceIn(0, Fx.BEATS.size - 1)
            fx.beats = Fx.BEATS[beatIndex]
            beatBtn.text = Fx.beatLabel(fx.beats) + "\nBEAT"
        }

        /** CFX macro: the filter knob also drives echo depth and fades the channel out at the ends. */
        private fun applyCfx(x: Double) {
            val a = abs(x)
            setFxType(Fx.Type.ECHO)
            fx.depth = a.toFloat()
            fx.on = a > 0.05
            strip.macroGain = (1 - maxOf(0.0, a - 0.7) / 0.3).toFloat()
        }

        private fun padTap(i: Int) {
            when (mode) {
                PadMode.HOTCUE -> deck.hotCue(i)
                PadMode.LOOP -> when (i) {
                    in 0..5 -> { lastBeatLoop = LOOP_BEATS[i]; deck.beatLoop(LOOP_BEATS[i]); loopInPending = false }
                    6 -> if (loopInPending) { deck.loopOutHere(); loopInPending = false } else { deck.loopInHere(); loopInPending = true }
                    else -> { if (deck.loopOn) deck.toggleLoop(); loopInPending = false }
                }
                PadMode.JUMP -> deck.beatJump(JUMPS[i])
                PadMode.SAMPLER -> sampleTap(i)
                PadMode.FX -> when (i) {
                    0 -> setFxType(Fx.Type.ECHO); 1 -> setFxType(Fx.Type.ROLL); 2 -> setFxType(Fx.Type.FLANGER)
                    3 -> { cfx = !cfx; if (cfx) applyCfx(strip.filter) else { strip.macroGain = 1f; fx.on = false } }
                }
            }
            refresh()
        }

        private fun padLong(i: Int) {
            when (mode) {
                PadMode.HOTCUE -> deck.clearHotCue(i)
                PadMode.SAMPLER -> showSampleMenu(i)
                else -> {}
            }
            refresh()
        }

        private fun showTempo() {
            val percent = (deck.tempo - 1) * 100
            tempo.value = (0.5 + (deck.tempo - 1) / (2 * Deck.MAX_SYNC)).toFloat()
            tempoText.text = String.format("%+.1f%%", percent)
            tempoText.setTextColor(if (abs(percent) > 0.05) accent else colMuted)
        }

        fun refresh() {
            val t = deck.track
            if (t != null) {
                val ms = deck.position * t.durationMs
                timeText.text = clock(ms) + "\n-" + clock(t.durationMs - ms)
                bpmText.text = if (deck.bpm > 0) String.format("%.1f", deck.bpm * deck.tempo) else "--.-"
            }
            play.text = if (deck.playing) "PAUSE" else "PLAY"
            play.strokeColor = ColorStateList.valueOf(if (deck.playing) accent else colBorder)
            play.backgroundTintList = ColorStateList.valueOf(if (deck.playing) accent else colControl)
            play.setTextColor(if (deck.playing) colGround else colText)
            sync.lit(synced, accent, tint); key.lit(deck.keylock, accent, tint); slip.lit(deck.slip, accent, tint); quant.lit(deck.quantize, accent, tint)
            fx.beatSeconds = if (deck.bpm > 0) 60.0 / (deck.bpm * deck.tempo) else 0.5
            fxOn.lit(fx.on, accent, tint)
            val fpb = deck.framesPerBeat
            for (i in 0 until 8) {
                val p = pads[i]
                when (mode) {
                    PadMode.HOTCUE -> {
                        val c = deck.hotCues[i]
                        val set = c >= 0 && t != null
                        p.text = if (set) "${i + 1}\n" + clock(c / t!!.sampleRate * 1000) else "${i + 1}"
                        p.lit(set, accent, tint)
                    }
                    PadMode.LOOP -> {
                        p.text = LOOP_LABELS[i]
                        val on = when (i) {
                            in 0..5 -> deck.loopOn && fpb > 0 && abs((deck.loopOut - deck.loopIn) / fpb - LOOP_BEATS[i]) < 0.01
                            6 -> loopInPending
                            else -> false
                        }
                        p.lit(on, colLoop, tintLoop, dimOff = false)
                    }
                    PadMode.JUMP -> { p.text = JUMP_LABELS[i]; p.lit(false, accent, tint, dimOff = false) }
                    PadMode.SAMPLER -> {
                        val s = mixer.samples[i]
                        p.text = sampleNames[i].ifEmpty { "S${i + 1}" }.take(9) + if (sampleLoop[i]) "\nLOOP" else ""
                        p.lit(s.playing, if (sampleLoop[i]) colLoop else accent, if (sampleLoop[i]) tintLoop else tint, dimOff = s.track == null)
                    }
                    PadMode.FX -> if (i < 4) {
                        p.text = FX_LABELS[i]
                        val on = when (i) { 0 -> fx.type == Fx.Type.ECHO; 1 -> fx.type == Fx.Type.ROLL; 2 -> fx.type == Fx.Type.FLANGER; else -> cfx }
                        p.lit(on, accent, tint, dimOff = false)
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "DJMix"
        const val METER_DECAY = 0.85f
        val LOOP_BEATS = doubleArrayOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0)
        val LOOP_LABELS = arrayOf("1/4", "1/2", "1", "2", "4", "8", "IN/OUT", "EXIT")
        val JUMPS = intArrayOf(-1, -2, -4, -8, 1, 2, 4, 8)
        val JUMP_LABELS = arrayOf("‹1", "‹2", "‹4", "‹8", "1›", "2›", "4›", "8›")
        val FX_LABELS = arrayOf("ECHO", "ROLL", "FLNG", "CFX")
        /** Outputs that can carry the cue: a plugged-in earphone of any kind. */
        val WIRED_TYPES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
    }
}

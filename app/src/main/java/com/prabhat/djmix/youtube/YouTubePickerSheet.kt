package com.prabhat.djmix.youtube

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.view.KeyEvent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.prabhat.djmix.R

/**
 * Real YouTube / YouTube Music in a WebView; the user opens a song, then taps "Add to library".
 * Ported from YOUTUBE-PICKER-GUIDE.md section 2; every non-obvious line there carries the reason it exists, keep them.
 */
class YouTubePickerSheet : BottomSheetDialogFragment() {

    data class Pick(val videoId: String, val title: String, val durationMs: Long)

    /** Gets the picked item. The sheet stays open so more songs can be added; the user closes it. */
    var onPicked: ((Pick) -> Unit)? = null
    private var queuedId: String? = null

    private var webView: WebView? = null
    private lateinit var btnAdd: Button
    private lateinit var tvTabMusic: TextView
    private lateinit var tvTabVideo: TextView
    private lateinit var lineTabMusic: View
    private lateinit var lineTabVideo: View
    private var current: Pick? = null

    /** Both open `.../watch?v=<id>` for an item. */
    private enum class Source(val home: String) {
        MUSIC("https://music.youtube.com/"),
        // YouTube's home feed. With no watch history it is an empty "Try searching" page;
        // onPageFinished then moves to VIDEO_FALLBACK. The WebView keeps YouTube's cookies, so the feed fills with use.
        VIDEO("https://m.youtube.com/")
    }
    private var source = Source.MUSIC

    // sp=EgIYAw== : YouTube's 4-20 minute duration filter, so the list is videos, not Shorts.
    private val videoFallback = "https://m.youtube.com/results?search_query=trending+videos&sp=EgIYAw%253D%253D"

    /** A playable item's page: a watch page or a Short. */
    private fun isItemUrl(url: String?) = url != null && (url.contains("watch?v=") || url.contains("/shorts/"))

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        // The keyboard must pan the sheet, not resize it: a resize changes the WebView's viewport, and
        // YouTube's mobile page closes its search box on that, so the keyboard opened and closed at once.
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        )
        // The phone's back key steps back inside the page (sign-in flows need it); only an empty history closes the sheet.
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && webView?.canGoBack() == true) { webView?.goBack(); true } else false
        }
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            val h = (resources.displayMetrics.heightPixels * 0.92).toInt()
            sheet.layoutParams = sheet.layoutParams.apply { height = h }
            BottomSheetBehavior.from(sheet).apply {
                peekHeight = h
                state = BottomSheetBehavior.STATE_EXPANDED
                isFitToContents = true
                skipCollapsed = true
                isDraggable = false // dragging stole the WebView's touches and focus
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.sheet_youtube_picker, container, false)
        btnAdd = root.findViewById(R.id.btnAdd)
        tvTabMusic = root.findViewById(R.id.tvTabMusic)
        tvTabVideo = root.findViewById(R.id.tvTabVideo)
        lineTabMusic = root.findViewById(R.id.lineTabMusic)
        lineTabVideo = root.findViewById(R.id.lineTabVideo)
        webView = root.findViewById(R.id.webView)
        setupWebView()
        root.findViewById<View>(R.id.ivBack).setOnClickListener { dismiss() }
        root.findViewById<View>(R.id.tabMusic).setOnClickListener { selectSource(Source.MUSIC) }
        root.findViewById<View>(R.id.tabVideo).setOnClickListener { selectSource(Source.VIDEO) }
        btnAdd.setOnClickListener {
            val pick = current
            if (pick == null) Toast.makeText(context, "Video data is loading, please wait", Toast.LENGTH_SHORT).show()
            else {
                onPicked?.invoke(pick)
                queuedId = pick.videoId
                btnAdd.text = "Queued"
                btnAdd.isEnabled = false
                btnAdd.alpha = 0.5f
            }
        }
        return root
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView() {
        btnAdd.visibility = View.GONE
        webView!!.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            // Keep keyboard focus inside the page (its search box) while the sheet is open.
            isFocusable = true
            isFocusableInTouchMode = true
            // Stop the bottom sheet from taking the WebView's scroll.
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        if (!v.hasFocus()) v.requestFocus()
                    }
                    MotionEvent.ACTION_MOVE -> v.parent.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                // A mobile Chrome UA: the WebView default UA gets a different (worse) YouTube page.
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            // Google refuses sign-in from web views it can identify. Two tells: the X-Requested-With header
            // (carries the app's package name) and refused third-party cookies across the accounts.google.com
            // -> youtube.com redirect. Drop the header where the WebView supports it, and accept the cookies.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
            CookieManager.getInstance().let { cm -> cm.setAcceptCookie(true); cm.setAcceptThirdPartyCookies(this, true) }
            addJavascriptInterface(Bridge(), "AndroidBridge")
            webChromeClient = object : WebChromeClient() {
                // YouTube Music holds the page while a song plays; leaving it is always fine here, so never let it cancel a navigation.
                override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    result?.confirm()
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith("http://") || url.startsWith("https://")) return false
                    // Google's sign-in hops to an intent:// link for phone verification, which a WebView cannot
                    // open (net::ERR_UNKNOWN_URL_SCHEME). Every such link carries a plain https fallback: stay on it.
                    if (url.startsWith("intent://")) {
                        val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()
                        val fallback = intent?.getStringExtra("browser_fallback_url")
                        if (fallback != null) { view?.loadUrl(fallback); return true }
                        if (intent != null && intent.resolveActivity(requireContext().packageManager) != null) {
                            runCatching { startActivity(intent) }
                            return true
                        }
                    }
                    return true // never show the error page for other schemes
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    updateAddButton(url)
                    if (url?.contains("m.youtube.com") == true) view?.evaluateJavascript(emptyHomeJs, null)
                }

                // YouTube is a single-page app: tapping an item changes the URL without a page load,
                // so this (not onPageFinished / shouldOverrideUrlLoading) is where it shows.
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    updateAddButton(url)
                    if (isItemUrl(url)) view?.postDelayed({ view.evaluateJavascript(EXTRACT_JS, null) }, 1500)
                }
            }
            loadUrl(source.home)
        }
        paintTabs()
    }

    // The logged-out m.youtube.com home renders "Try searching to get started" after load; check again while it settles.
    private val emptyHomeJs = """
        (function() {
            function emptyHome() {
                if (document.body && document.body.innerText.includes("Try searching to get started")) {
                    window.location.href = "$videoFallback";
                    return true;
                }
                return false;
            }
            if (!emptyHome()) { setTimeout(emptyHome, 1500); setTimeout(emptyHome, 3500); }
        })();
    """.trimIndent()

    private fun selectSource(next: Source) {
        if (next == source) return
        source = next
        paintTabs()
        current = null
        btnAdd.visibility = View.GONE
        webView?.loadUrl(next.home)
    }

    private fun paintTabs() {
        val selected = Color.parseColor("#6750A4")
        val normal = Color.parseColor("#3C3A3A")
        tvTabMusic.setTextColor(if (source == Source.MUSIC) selected else normal)
        tvTabVideo.setTextColor(if (source == Source.VIDEO) selected else normal)
        lineTabMusic.visibility = if (source == Source.MUSIC) View.VISIBLE else View.INVISIBLE
        lineTabVideo.visibility = if (source == Source.VIDEO) View.VISIBLE else View.INVISIBLE
    }

    private fun updateAddButton(url: String?) {
        if (isItemUrl(url)) {
            btnAdd.visibility = View.VISIBLE
            btnAdd.text = "Add to library"
            btnAdd.isEnabled = false // enabled when the JS reports the item
            btnAdd.alpha = 0.5f
        } else {
            btnAdd.visibility = View.GONE
            current = null
        }
    }

    inner class Bridge {
        /** The argument list must match the JS call exactly, or the call silently fails. */
        @JavascriptInterface
        fun onVideoDataExtracted(title: String, url: String, videoId: String, image: String, durationMs: Long) {
            activity?.runOnUiThread {
                if (webView == null) return@runOnUiThread
                current = Pick(videoId, title, durationMs)
                if (videoId == queuedId) return@runOnUiThread // already queued; the JS re-reports while it settles
                btnAdd.text = "Add to library"
                btnAdd.isEnabled = true
                btnAdd.alpha = 1.0f
            }
        }
    }

    override fun onDestroyView() {
        CookieManager.getInstance().flush() // keep the YouTube session (and a sign-in) across app restarts
        webView?.apply {
            loadUrl("about:blank")
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroyView()
    }

    companion object {
        // Retried while the player catches up with the address bar; each pass reports what it has,
        // so the Add button never waits on the duration.
        private val EXTRACT_JS = """
            (function() {
                function read(attempt) {
                    var url = window.location.href;
                    var videoId = "";
                    if (url.includes("watch?v=")) {
                        videoId = url.split("watch?v=")[1].split("&")[0];
                    } else if (url.includes("/shorts/")) {
                        videoId = url.split("/shorts/")[1].split(/[?&#/]/)[0];
                    }
                    if (!videoId) return;

                    // The player knows the item on screen. The page meta belongs to the site
                    // (YouTube Music's og:title is just "YouTube Music") and has no duration.
                    var title = "";
                    var durationMs = 0;
                    try {
                        var mp = document.getElementById("movie_player");
                        var d = mp && mp.getPlayerResponse ? (mp.getPlayerResponse() || {}).videoDetails : null;
                        if (d && d.videoId === videoId) {
                            title = d.title || "";
                            durationMs = (parseInt(d.lengthSeconds, 10) || 0) * 1000;
                        }
                    } catch (e) {}
                    if (!title) {
                        var og = document.querySelector('meta[property="og:title"]');
                        title = (og ? og.content : document.title).replace(" - YouTube Music", "").replace(" - YouTube", "");
                        if (title === "YouTube Music" || title === "YouTube") title = "";
                    }
                    if (!durationMs) {
                        // Never read the duration while an ad plays: it is the ad's.
                        var v = document.querySelector("video");
                        var ad = document.querySelector(".ad-showing, .ad-interrupting");
                        if (v && !ad && isFinite(v.duration) && v.duration > 0) durationMs = Math.round(v.duration * 1000);
                    }

                    var image = "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
                    AndroidBridge.onVideoDataExtracted(title, url, videoId, image, durationMs);

                    if ((!title || !durationMs) && attempt < 6) setTimeout(function() { read(attempt + 1); }, 1000);
                }
                read(0);
            })();
        """.trimIndent()
    }
}

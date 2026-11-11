package com.prabhat.djmix.instagram

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * instagram.com's login page in a WebView, so [IgAudio] can call Instagram's API with the user's cookies (the app has
 * one cookie jar, shared with the YouTube picker). Closes itself once a session cookie appears; closing it by hand is
 * fine too, some public posts resolve without a login. [onDone] runs either way.
 */
class InstagramLoginSheet : BottomSheetDialogFragment() {
    var onDone: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
        setOnShowListener {
            val sheet = findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            val h = (resources.displayMetrics.heightPixels * 0.92).toInt()
            sheet.layoutParams = sheet.layoutParams.apply { height = h }
            BottomSheetBehavior.from(sheet).apply { peekHeight = h; state = BottomSheetBehavior.STATE_EXPANDED; skipCollapsed = true; isDraggable = false }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = WebView(requireContext()).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = IgAudio.UA
        CookieManager.getInstance().let { it.setAcceptCookie(true); it.setAcceptThirdPartyCookies(this, true) }
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (isAdded && loggedIn()) { Toast.makeText(context, "Instagram: logged in", Toast.LENGTH_SHORT).show(); dismiss() }
            }
        }
        loadUrl("https://www.instagram.com/accounts/login/")
    }

    override fun onDismiss(dialog: DialogInterface) { super.onDismiss(dialog); onDone?.invoke() }

    companion object {
        private const val ORIGIN = "https://www.instagram.com"
        /** The WebView's instagram.com cookies for an API call, or null when there are none. Any thread. */
        fun cookie(): String? = CookieManager.getInstance().getCookie(ORIGIN)
        fun loggedIn() = cookie()?.contains("sessionid=") == true
        /** Drops the session (Instagram refused it), so the next shared link opens the login page again. */
        fun forget() = CookieManager.getInstance().setCookie(ORIGIN, "sessionid=; Max-Age=0; Path=/; Domain=.instagram.com")
    }
}

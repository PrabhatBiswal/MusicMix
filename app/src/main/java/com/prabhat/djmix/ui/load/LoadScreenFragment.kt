package com.prabhat.djmix.ui.load

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import com.prabhat.djmix.R
import com.prabhat.djmix.ui.theme.DJMixTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Hosts the Compose Load screen as a full-screen dialog over the console (the console is still Views, and the Mixer
 * lives in MainActivity, so the music keeps playing underneath). @AndroidEntryPoint lets hiltViewModel() inside the
 * screen build its ViewModels with injected repositories.
 */
@AndroidEntryPoint
class LoadScreenFragment : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.LoadScreen)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            DJMixTheme {
                LoadScreen(host = requireActivity() as LoadHost, deck = requireArguments().getString(DECK) ?: "A", close = { dismissAllowingStateLoss() })
            }
        }
    }

    /** Same immersive treatment as the console behind it. */
    override fun onStart() {
        super.onStart()
        dialog?.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.getInsetsController(w, w.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    companion object {
        const val TAG = "load"
        private const val DECK = "deck"
        fun newInstance(deck: String) = LoadScreenFragment().apply { arguments = bundleOf(DECK to deck) }
    }
}

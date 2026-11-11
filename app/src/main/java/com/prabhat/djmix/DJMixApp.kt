package com.prabhat.djmix

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * The Application class. @HiltAndroidApp makes Hilt generate the app-wide dependency container here; every
 * @AndroidEntryPoint (MainActivity, LoadScreenFragment) and @HiltViewModel then gets its @Inject fields from it.
 */
@HiltAndroidApp
class DJMixApp : Application()

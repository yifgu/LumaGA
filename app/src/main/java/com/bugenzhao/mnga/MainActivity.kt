package com.bugenzhao.mnga

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigationevent.NavigationEventTransitionState
import com.bugenzhao.mnga.model.SchemesModel
import com.bugenzhao.mnga.storage.PreferencesStorage
import com.bugenzhao.mnga.storage.ColorSchemeMode
import com.bugenzhao.mnga.ui.root.LumaGARoot
import com.bugenzhao.mnga.ui.theme.isDarkTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logBackDiagnostics()
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val prefs = App.prefs
            val colorScheme by prefs.colorSchemeRaw.flow.collectAsState()
            val dark = ColorSchemeMode.fromRaw(colorScheme).isDarkTheme(isSystemInDarkTheme())
            SideEffect {
                // Keep AndroidX's navigation-bar contrast scrims on older
                // Android and three-button navigation, but use the app theme.
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { dark },
                    navigationBarStyle = SystemBarStyle.auto(
                        0xe6ffffff.toInt(),
                        0x801b1b1b.toInt(),
                    ) { dark },
                )
            }
            val portrait by prefs.alwaysPortraitOnPhone.flow.collectAsState()
            androidx.compose.runtime.LaunchedEffect(portrait) {
                requestedOrientation =
                    if (portrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            LumaGARoot(onNewIntent = { handleIntent(it) })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun logBackDiagnostics() {
        Log.i(
            "LumaGABack",
            "environment app=${BuildConfig.VERSION_NAME} sdk=${Build.VERSION.SDK_INT} " +
                "targetSdk=${applicationInfo.targetSdkVersion} " +
                "device=${Build.MANUFACTURER}/${Build.MODEL}",
        )
        // Observe AndroidX's shared gesture state, never register a competing back callback.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var active = false
                var lastBucket = -1
                Log.i("LumaGABack", "observer started callbacks=${onBackPressedDispatcher.hasEnabledCallbacks()}")
                navigationEventDispatcher.transitionState.collect { state ->
                    when (state) {
                        is NavigationEventTransitionState.InProgress -> {
                            val event = state.latestEvent
                            val bucket = (event.progress * 10).toInt()
                            if (!active || bucket != lastBucket) {
                                Log.i(
                                    "LumaGABack",
                                    "gesture ${if (active) "progress" else "started"} " +
                                        "direction=${state.direction} progress=${event.progress} " +
                                        "edge=${event.swipeEdge}",
                                )
                            }
                            active = true
                            lastBucket = bucket
                        }
                        NavigationEventTransitionState.Idle -> {
                            // Idle alone cannot distinguish completion from cancellation.
                            Log.i(
                                "LumaGABack",
                                "gesture idle wasActive=$active " +
                                    "callbacks=${onBackPressedDispatcher.hasEnabledCallbacks()}",
                            )
                            active = false
                            lastBucket = -1
                        }
                    }
                }
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (App.schemes.canNavigateTo(uri)) App.schemes.navigateTo(uri)
    }
}

package com.bugenzhao.mnga

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
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
import com.bugenzhao.mnga.util.BackDiagnostics
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
        BackDiagnostics.log(
            "environment app=${BuildConfig.VERSION_NAME} sdk=${Build.VERSION.SDK_INT} " +
                "targetSdk=${applicationInfo.targetSdkVersion} " +
                "device=${Build.MANUFACTURER}/${Build.MODEL} " +
                "animatorScale=${Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)} " +
                "transitionScale=${Settings.Global.getFloat(contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)}",
        )
        // Observe AndroidX's shared gesture state, never register a competing back callback.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var active = false
                var startedAt = 0L
                var observedStates = 0
                var maxProgress = 0f
                BackDiagnostics.log("observer started callbacks=${onBackPressedDispatcher.hasEnabledCallbacks()}")
                // StateFlow can conflate events. These are observed transitions,
                // not a lossless trace of raw platform callbacks.
                try {
                    navigationEventDispatcher.transitionState.collect { state ->
                        when (state) {
                            is NavigationEventTransitionState.InProgress -> {
                                val event = state.latestEvent
                                if (!active) {
                                    startedAt = SystemClock.uptimeMillis()
                                    observedStates = 0
                                    maxProgress = 0f
                                }
                                observedStates++
                                maxProgress = maxOf(maxProgress, event.progress)
                                BackDiagnostics.log(
                                    "transition ${if (active) "progress" else "started"} " +
                                        "direction=${state.direction} progress=${event.progress} " +
                                        "edge=${event.swipeEdge}",
                                )
                                active = true
                            }
                            NavigationEventTransitionState.Idle -> {
                                // Idle alone cannot distinguish completion from cancellation.
                                val elapsed = if (active) SystemClock.uptimeMillis() - startedAt else 0L
                                BackDiagnostics.log(
                                    "transition idle wasActive=$active " +
                                        "observedStates=$observedStates maxProgress=$maxProgress " +
                                        "elapsedMs=$elapsed " +
                                        "callbacks=${onBackPressedDispatcher.hasEnabledCallbacks()}",
                                )
                                active = false
                                observedStates = 0
                                maxProgress = 0f
                            }
                        }
                    }
                } finally {
                    BackDiagnostics.log("observer stopped wasActive=$active")
                }
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (App.schemes.canNavigateTo(uri)) App.schemes.navigateTo(uri)
    }
}

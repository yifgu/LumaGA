package com.bugenzhao.mnga

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.bugenzhao.mnga.model.SchemesModel
import com.bugenzhao.mnga.storage.PreferencesStorage
import com.bugenzhao.mnga.storage.ColorSchemeMode
import com.bugenzhao.mnga.ui.root.LumaGARoot
import com.bugenzhao.mnga.ui.nav.Route
import com.bugenzhao.mnga.ui.theme.isDarkTheme

open class MainActivity : ComponentActivity() {

    protected open fun initialRoute(): Route? = Route.ForumList

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val route = initialRoute() ?: run {
            finish()
            return
        }
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
            LumaGARoot(
                onNewIntent = { handleIntent(it) },
                initialRoute = route,
                navigationActivity = this,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (App.schemes.canNavigateTo(uri)) App.schemes.navigateTo(uri)
    }
}

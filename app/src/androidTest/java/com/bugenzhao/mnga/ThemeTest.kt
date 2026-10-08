package com.bugenzhao.mnga

import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.bugenzhao.mnga.storage.ColorSchemeMode
import com.bugenzhao.mnga.storage.ThemeColor
import com.bugenzhao.mnga.ui.screens.prefs.PreferencesSheet
import com.bugenzhao.mnga.ui.theme.LumaGATheme
import com.bugenzhao.mnga.ui.theme.isDarkTheme
import com.bugenzhao.mnga.util.L
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeTest {
    @Test
    fun systemBarsFollowAppThemeChangesAndRecreation() = withSavedPreferences {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (mode in listOf(
                ColorSchemeMode.DARK,
                ColorSchemeMode.LIGHT,
                ColorSchemeMode.CLASSIC,
                ColorSchemeMode.AUTO,
            )) {
                scenario.onActivity { App.prefs.colorSchemeRaw.value = mode.raw }
                awaitSystemBars(scenario, mode)
            }
            scenario.onActivity { App.prefs.colorSchemeRaw.value = ColorSchemeMode.DARK.raw }
            awaitSystemBars(scenario, ColorSchemeMode.DARK)
            scenario.recreate()
            awaitSystemBars(scenario, ColorSchemeMode.DARK)
        }
    }

    @Test
    fun dynamicColorsRespectAppModeAndPreserveCustomAndClassicPalettes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (mode in listOf(ColorSchemeMode.LIGHT, ColorSchemeMode.DARK, ColorSchemeMode.CLASSIC)) {
                val custom = renderTheme(scenario, mode, dynamic = false)
                val dynamic = renderTheme(scenario, mode, dynamic = true)
                val expected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    mode != ColorSchemeMode.CLASSIC
                ) {
                    var colors: ColorScheme? = null
                    scenario.onActivity {
                        colors = if (mode == ColorSchemeMode.DARK) {
                            dynamicDarkColorScheme(it)
                        } else {
                            dynamicLightColorScheme(it)
                        }
                    }
                    checkNotNull(colors)
                } else {
                    custom
                }
                assertPaletteEquals(expected, dynamic)
                assertPaletteEquals(custom, renderTheme(scenario, mode, dynamic = false))
            }
        }
    }

    @Test
    fun dynamicPreferencePersistsWithoutReplacingSavedAccent() = withSavedPreferences {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                App.prefs.themeColorRaw.value = ThemeColor.RED.raw
                App.prefs.useDynamicColors.value = true
                assertTrue(App.sharedPreferences.getBoolean("useDynamicColors", false))
                assertEquals(ThemeColor.RED.raw, App.prefs.themeColorRaw.value)
            }
            scenario.recreate()
            scenario.onActivity {
                assertTrue(App.prefs.useDynamicColors.value)
                App.prefs.useDynamicColors.value = false
                assertEquals(ThemeColor.RED.raw, App.prefs.themeColorRaw.value)
            }
        }
    }

    @Test
    fun themeColorPickerOffersDynamicAndFixedColorsTogether() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        withThemeColorPicker(ColorSchemeMode.LIGHT) { scenario, device ->
            selectThemeColor(device, "Dynamic Colors")
            await(scenario) { App.prefs.useDynamicColors.value }
            scenario.onActivity {
                assertEquals(ThemeColor.RED.raw, App.prefs.themeColorRaw.value)
            }
            clickLabel(device, "Theme Color")
            selectThemeColor(device, "LumaGA")
            await(scenario) {
                !App.prefs.useDynamicColors.value &&
                    App.prefs.themeColorRaw.value == ThemeColor.LUMAGA.raw
            }
        }
    }

    @Test
    fun classicPickerKeepsDynamicPreferenceDormant() {
        withThemeColorPicker(ColorSchemeMode.CLASSIC) { scenario, device ->
            scenario.onActivity { App.prefs.useDynamicColors.value = true }
            assertFalse(device.hasObject(By.text(localized("Dynamic Colors"))))
            selectThemeColor(device, "LumaGA")
            await(scenario) { App.prefs.themeColorRaw.value == ThemeColor.LUMAGA.raw }
            scenario.onActivity {
                assertTrue(App.prefs.useDynamicColors.value)
                App.prefs.colorSchemeRaw.value = ColorSchemeMode.LIGHT.raw
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertTrue(device.wait(Until.hasObject(By.text(localized("Dynamic Colors"))), 5_000))
            }
        }
    }

    @Test
    fun olderAndroidPickerOnlyOffersFixedColors() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
        withThemeColorPicker(ColorSchemeMode.LIGHT) { scenario, device ->
            scenario.onActivity { App.prefs.useDynamicColors.value = true }
            assertFalse(device.hasObject(By.text(localized("Dynamic Colors"))))
            selectThemeColor(device, "LumaGA")
            await(scenario) { App.prefs.themeColorRaw.value == ThemeColor.LUMAGA.raw }
            scenario.onActivity { assertTrue(App.prefs.useDynamicColors.value) }
        }
    }

    private fun withThemeColorPicker(
        mode: ColorSchemeMode,
        block: (ActivityScenario<MainActivity>, UiDevice) -> Unit,
    ) = withSavedPreferences {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                App.prefs.colorSchemeRaw.value = mode.raw
                App.prefs.themeColorRaw.value = ThemeColor.RED.raw
                App.prefs.useDynamicColors.value = false
                activity.setContent {
                    val scheme by App.prefs.colorSchemeRaw.flow.collectAsState()
                    val color by App.prefs.themeColorRaw.flow.collectAsState()
                    val dynamic by App.prefs.useDynamicColors.flow.collectAsState()
                    LumaGATheme(
                        themeColor = ThemeColor.fromRaw(color),
                        colorSchemeMode = ColorSchemeMode.fromRaw(scheme),
                        useDynamicColors = dynamic,
                    ) {
                        PreferencesSheet(onDismiss = {})
                    }
                }
            }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            clickLabel(device, "Theme Color")
            // Wait for the actual picker, not just the Theme Color row.
            assertTrue(device.wait(Until.hasObject(By.text(localized("Cancel"))), 5_000))
            block(scenario, device)
        }
    }

    private fun localized(key: String) =
        L.str(InstrumentationRegistry.getInstrumentation().targetContext, key)

    private fun clickLabel(device: UiDevice, key: String) {
        val item = device.wait(Until.findObject(By.text(localized(key))), 5_000)
            ?: throw AssertionError("Missing theme picker label: $key")
        item.click()
    }

    private fun selectThemeColor(device: UiDevice, key: String) {
        clickLabel(device, key)
        assertTrue(device.wait(Until.gone(By.text(localized("Cancel"))), 5_000))
    }

    private fun renderTheme(
        scenario: ActivityScenario<MainActivity>,
        mode: ColorSchemeMode,
        dynamic: Boolean,
    ): ColorScheme {
        val colors = AtomicReference<ColorScheme>()
        scenario.onActivity { activity ->
            activity.setContent {
                LumaGATheme(
                    themeColor = ThemeColor.RED,
                    colorSchemeMode = mode,
                    useDynamicColors = dynamic,
                ) {
                    val scheme = MaterialTheme.colorScheme
                    SideEffect { colors.set(scheme) }
                }
            }
        }
        await(scenario) { colors.get() != null }
        return colors.get()
    }

    private fun assertPaletteEquals(expected: ColorScheme, actual: ColorScheme) {
        assertEquals(expected.primary, actual.primary)
        assertEquals(expected.onPrimary, actual.onPrimary)
        assertEquals(expected.secondary, actual.secondary)
        assertEquals(expected.tertiary, actual.tertiary)
        assertEquals(expected.background, actual.background)
        assertEquals(expected.surface, actual.surface)
        assertEquals(expected.onSurface, actual.onSurface)
    }

    private fun awaitSystemBars(scenario: ActivityScenario<MainActivity>, mode: ColorSchemeMode) {
        await(scenario) { activity ->
            val systemDark = (activity.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val light = !mode.isDarkTheme(systemDark)
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.isAppearanceLightStatusBars == light &&
                controller.isAppearanceLightNavigationBars == light
        }
    }

    private fun await(
        scenario: ActivityScenario<MainActivity>,
        condition: (MainActivity) -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Theme did not reach the expected state")
    }

    private fun withSavedPreferences(block: () -> Unit) {
        val mode = App.prefs.colorSchemeRaw.value
        val dynamic = App.prefs.useDynamicColors.value
        val accent = App.prefs.themeColorRaw.value
        try {
            block()
        } finally {
            App.prefs.colorSchemeRaw.value = mode
            App.prefs.useDynamicColors.value = dynamic
            App.prefs.themeColorRaw.value = accent
        }
    }
}

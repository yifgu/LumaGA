package com.bugenzhao.mnga.ui.theme

import com.bugenzhao.mnga.storage.ColorSchemeMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test
    fun `automatic theme follows system changes`() {
        assertFalse(ColorSchemeMode.AUTO.isDarkTheme(systemDark = false))
        assertTrue(ColorSchemeMode.AUTO.isDarkTheme(systemDark = true))
    }

    @Test
    fun `explicit modes override the system theme`() {
        for (systemDark in listOf(false, true)) {
            assertFalse(ColorSchemeMode.LIGHT.isDarkTheme(systemDark))
            assertTrue(ColorSchemeMode.DARK.isDarkTheme(systemDark))
            assertFalse(ColorSchemeMode.CLASSIC.isDarkTheme(systemDark))
        }
    }
}

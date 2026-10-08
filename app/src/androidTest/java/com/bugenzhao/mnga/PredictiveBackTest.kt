package com.bugenzhao.mnga

import android.os.Build
import android.os.SystemClock
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bugenzhao.mnga.storage.ColorSchemeMode
import com.bugenzhao.mnga.storage.ThemeColor
import com.bugenzhao.mnga.ui.nav.Navigator
import com.bugenzhao.mnga.ui.nav.Route
import com.bugenzhao.mnga.ui.nav.RouteCodec
import com.bugenzhao.mnga.ui.root.NavigationHost
import com.bugenzhao.mnga.ui.theme.LumaGATheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production NavHost without adding a Compose test dependency. */
@RunWith(AndroidJUnit4::class)
class PredictiveBackTest {

    @Test
    fun cancelledGesturePreviewsPreviousScreenWithoutPopping() = withNavigation { scenario, navigator ->
        scenario.onActivity { navigator.push(Route.About) }
        awaitDestination(scenario, navigator, Route.About)

        scenario.onActivity {
            it.onBackPressedDispatcher.dispatchOnBackStarted(backEvent(0f))
            it.onBackPressedDispatcher.dispatchOnBackProgressed(backEvent(0.5f))
        }
        // NavHost prepares the previous entry for the preview. A pop-only
        // BackHandler would leave it CREATED and never render that preview.
        await(scenario) {
            navigator.navController.getBackStackEntry(RouteCodec.ROUTE_FORUM_LIST)
                .lifecycle.currentState == Lifecycle.State.STARTED
        }
        scenario.onActivity {
            assertEquals(listOf(Route.ForumList, Route.About), navigator.stack.value)
            it.onBackPressedDispatcher.dispatchOnBackCancelled()
        }
        awaitDestination(scenario, navigator, Route.About)
        scenario.onActivity {
            assertEquals(listOf(Route.ForumList, Route.About), navigator.stack.value)
            assertEquals(Navigator.Op.PUSH, navigator.lastOp)
        }
    }

    @Test
    fun committedGesturePopsExactlyOneScreen() = withNavigation { scenario, navigator ->
        scenario.onActivity { navigator.push(Route.About) }
        awaitDestination(scenario, navigator, Route.About)
        scenario.onActivity { navigator.push(Route.BlockWords) }
        awaitDestination(scenario, navigator, Route.BlockWords)

        scenario.onActivity {
            it.onBackPressedDispatcher.dispatchOnBackStarted(backEvent(0f))
            it.onBackPressedDispatcher.dispatchOnBackProgressed(backEvent(0.7f))
        }
        await(scenario) {
            navigator.navController.getBackStackEntry(RouteCodec.ROUTE_ABOUT)
                .lifecycle.currentState == Lifecycle.State.STARTED
        }
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitDestination(scenario, navigator, Route.About)
        scenario.onActivity {
            assertEquals(listOf(Route.ForumList, Route.About), navigator.stack.value)
            assertEquals(Navigator.Op.POP, navigator.lastOp)
            assertFalse(it.isFinishing)
        }
    }

    @Test
    fun buttonBackStillPopsAndRootOnlyInterceptsOnOlderAndroid() =
        withNavigation { scenario, navigator ->
            scenario.onActivity { navigator.push(Route.About) }
            awaitDestination(scenario, navigator, Route.About)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitDestination(scenario, navigator, Route.ForumList)
            await(scenario) {
                it.onBackPressedDispatcher.hasEnabledCallbacks() ==
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            }
            scenario.onActivity {
                assertEquals(listOf(Route.ForumList), navigator.stack.value)
                assertFalse(it.isFinishing)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    it.onBackPressedDispatcher.onBackPressed()
                    assertFalse(it.isFinishing)
                    it.onBackPressedDispatcher.onBackPressed()
                    assertTrue(it.isFinishing)
                }
            }
        }

    private fun backEvent(progress: Float) =
        BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT)

    private fun withNavigation(block: (ActivityScenario<MainActivity>, Navigator) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val navigator = AtomicReference<Navigator>()
            scenario.onActivity { activity ->
                activity.setContent {
                    val controller = rememberNavController()
                    val current = remember { Navigator(controller, listOf(Route.ForumList)) }
                    SideEffect { navigator.set(current) }
                    LumaGATheme(
                        themeColor = ThemeColor.fromRaw(App.prefs.themeColorRaw.value),
                        colorSchemeMode = ColorSchemeMode.fromRaw(App.prefs.colorSchemeRaw.value),
                    ) {
                        NavigationHost(current, editor = null)
                    }
                }
            }
            await(scenario) { navigator.get() != null }
            val current = navigator.get()
            awaitDestination(scenario, current, Route.ForumList)
            block(scenario, current)
        }
    }

    private fun awaitDestination(
        scenario: ActivityScenario<MainActivity>,
        navigator: Navigator,
        route: Route,
    ) {
        await(scenario) {
            navigator.current == route &&
                navigator.navController.currentBackStackEntry?.lifecycle?.currentState ==
                Lifecycle.State.RESUMED
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
        throw AssertionError("Navigation did not reach the expected state")
    }
}

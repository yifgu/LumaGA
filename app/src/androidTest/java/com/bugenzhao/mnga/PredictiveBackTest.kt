package com.bugenzhao.mnga

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bugenzhao.mnga.storage.ColorSchemeMode
import com.bugenzhao.mnga.storage.ThemeColor
import com.bugenzhao.mnga.ui.nav.Navigator
import com.bugenzhao.mnga.ui.nav.Route
import com.bugenzhao.mnga.ui.nav.RouteCodec
import com.bugenzhao.mnga.ui.root.NavigationHost
import com.bugenzhao.mnga.ui.theme.LumaGATheme
import com.bugenzhao.mnga.util.BackDiagnostics
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production NavHost without adding a Compose test dependency. */
@RunWith(AndroidJUnit4::class)
class PredictiveBackTest {

    @Test
    fun rootWithoutArgumentsStaysInMirroredStack() = withNavigation { scenario, navigator ->
        scenario.onActivity {
            val root = navigator.navController.getBackStackEntry(RouteCodec.ROUTE_FORUM_LIST)
            assertNull(root.arguments)
            assertEquals(Route.ForumList, RouteCodec.decode(root))
            assertEquals(listOf(Route.ForumList), navigator.stack.value)
            assertEquals(1, navigator.size)
        }
        scenario.onActivity { navigator.push(Route.PersonalCenter) }
        awaitDestination(scenario, navigator, Route.PersonalCenter)
        scenario.onActivity { navigator.push(Route.Settings) }
        awaitDestination(scenario, navigator, Route.Settings)
        scenario.onActivity {
            assertEquals(
                listOf(Route.ForumList, Route.PersonalCenter, Route.Settings),
                navigator.stack.value,
            )
            navigator.popToRoot()
        }
        awaitDestination(scenario, navigator, Route.ForumList)
        scenario.onActivity {
            assertEquals(listOf(Route.ForumList), navigator.stack.value)
            assertEquals(1, navigator.size)
            assertEquals(Navigator.Op.POP, navigator.lastOp)
        }
    }

    @Test
    fun cancelledGesturePreviewsPreviousScreenWithoutPopping() = withNavigation { scenario, navigator ->
        scenario.onActivity { navigator.push(Route.About) }
        awaitDestination(scenario, navigator, Route.About)
        val marker = "cancelled-back-test-${System.nanoTime()}"
        BackDiagnostics.log(marker)

        scenario.onActivity {
            it.onBackPressedDispatcher.dispatchOnBackStarted(backEvent(0f))
            it.onBackPressedDispatcher.dispatchOnBackProgressed(backEvent(0.05f))
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
        val logs = runBlocking { BackDiagnostics.snapshot() }.substringAfter("$marker\n")
        assertTrue(logs.contains("progress=0.05"))
        assertTrue(logs.contains("transition idle wasActive=true"))
        assertTrue(logs.contains("maxProgress=0.05"))
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
    fun isolatedProbeLeavesNavHostStackIntactAfterClosing() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        withNavigation { scenario, navigator ->
            scenario.onActivity { navigator.push(Route.About) }
            awaitDestination(scenario, navigator, Route.About)
            val marker = "isolated-probe-test-${System.nanoTime()}"
            BackDiagnostics.log(marker)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val monitor = instrumentation.addMonitor(
                BackGestureDiagnosticsActivity::class.java.name, null, false,
            )
            try {
                // A second ActivityScenario.launch() would clear MainActivity's task.
                scenario.onActivity {
                    it.startActivity(Intent(it, BackGestureDiagnosticsActivity::class.java))
                }
                val probe = monitor.waitForActivityWithTimeout(10_000) as? BackGestureDiagnosticsActivity
                    ?: throw AssertionError("The raw back probe did not open")
                try {
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        assertEquals(
                            probe.getString(R.string.back_probe_ready),
                            probe.findViewById<TextView>(R.id.back_probe_status).text.toString(),
                        )
                        assertFalse(probe.isFinishing)
                        probe.findViewById<Button>(R.id.back_probe_close).performClick()
                    }
                } finally {
                    instrumentation.runOnMainSync { probe.finish() }
                }
            } finally {
                instrumentation.removeMonitor(monitor)
            }
            awaitDestination(scenario, navigator, Route.About)
            await(scenario) { it.onBackPressedDispatcher.hasEnabledCallbacks() }
            scenario.onActivity {
                assertEquals(listOf(Route.ForumList, Route.About), navigator.stack.value)
                assertFalse(it.isFinishing)
            }
            val logs = runBlocking { BackDiagnostics.snapshot() }.substringAfter("$marker\n")
            val rawLines = logs.lines().filter { it.contains("raw-probe ") }
            assertEquals(1, rawLines.count { it.contains("callback registered") })
            assertEquals(1, rawLines.count { it.contains("callback stopped") })
        }
    }

    @Test
    fun isolatedProbeRegistersCallbackOnlyWhileStarted() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val marker = "probe-lifecycle-test-${System.nanoTime()}"
        BackDiagnostics.log(marker)
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<BackGestureDiagnosticsActivity>(
            Intent(context, BackGestureDiagnosticsActivity::class.java),
        ).use { probe ->
            probe.moveToState(Lifecycle.State.CREATED)
            probe.moveToState(Lifecycle.State.RESUMED)
            probe.onActivity { assertFalse(it.isFinishing) }
        }
        val logs = runBlocking { BackDiagnostics.snapshot() }.substringAfter("$marker\n")
        val rawLines = logs.lines().filter { it.contains("raw-probe ") }
        assertEquals(2, rawLines.count { it.contains("callback registered") })
        assertEquals(2, rawLines.count { it.contains("callback stopped") })
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

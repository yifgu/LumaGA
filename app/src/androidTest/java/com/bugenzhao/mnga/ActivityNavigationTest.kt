package com.bugenzhao.mnga

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.navigation.NavHostController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.bugenzhao.mnga.protos.datamodel.ForumId
import com.bugenzhao.mnga.ui.nav.Navigator
import com.bugenzhao.mnga.ui.nav.Route
import com.bugenzhao.mnga.ui.nav.RouteCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the production Activity stack, not synthetic NavHost progress callbacks. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
class ActivityNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun systemBackPopsOneActivityAndToolbarBackReturnsToSameRoot() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var root: MainActivity
            scenario.onActivity {
                root = it
                navigator(it, Route.ForumList).push(Route.About)
            }
            val about = awaitResumed { it is ScreenActivity } as ScreenActivity
            instrumentation.runOnMainSync {
                assertEquals(root.taskId, about.taskId)
                assertFalse(about.onBackPressedDispatcher.hasEnabledCallbacks())
                navigator(about, Route.About).push(Route.BlockWords)
            }
            val words = awaitResumed { it is ScreenActivity && it !== about } as ScreenActivity
            instrumentation.runOnMainSync {
                assertFalse(words.onBackPressedDispatcher.hasEnabledCallbacks())
                words.onBackPressedDispatcher.onBackPressed()
            }
            assertSame(about, awaitResumed { it === about })
            instrumentation.runOnMainSync { navigator(about, Route.About).pop() }
            assertSame(root, awaitResumed { it === root })
            scenario.onActivity {
                assertFalse(it.isFinishing)
                assertFalse(it.onBackPressedDispatcher.hasEnabledCallbacks())
            }
        }
    }

    @Test
    fun routeSurvivesRecreationAndPopToRootClearsNestedActivities() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var root: MainActivity
            scenario.onActivity {
                root = it
                navigator(it, Route.ForumList).push(Route.About)
            }
            val about = awaitResumed { it is ScreenActivity } as ScreenActivity
            instrumentation.runOnMainSync { about.recreate() }
            val restored = awaitResumed { it is ScreenActivity && it !== about } as ScreenActivity
            instrumentation.runOnMainSync {
                assertFalse(restored.onBackPressedDispatcher.hasEnabledCallbacks())
                navigator(restored, Route.About).push(Route.BlockWords)
            }
            val words = awaitResumed { it is ScreenActivity && it !== restored } as ScreenActivity
            instrumentation.runOnMainSync { navigator(words, Route.BlockWords).popToRoot() }
            assertSame(root, awaitResumed { it === root })
            instrumentation.runOnMainSync {
                assertTrue(restored.isFinishing || restored.isDestroyed)
                assertTrue(words.isFinishing || words.isDestroyed)
            }
        }
    }

    @Test
    fun copyingCurrentTopicLinkDoesNotUndoActivityBack() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var root: MainActivity
            scenario.onActivity { root = it }
            SystemClock.sleep(700)
            val generation = App.foregroundGeneration
            val route = Route.TopicDetails("12345")
            scenario.onActivity { navigator(it, Route.ForumList).push(route) }
            val topic = awaitResumed { it is ScreenActivity } as ScreenActivity
            instrumentation.runOnMainSync {
                val clipboard = topic.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("link", "mnga://topic/12345"))
                navigator(topic, route).pop()
            }
            awaitResumed { it === root }
            SystemClock.sleep(1000)
            instrumentation.runOnMainSync {
                assertEquals(generation, App.foregroundGeneration)
                assertTrue(
                    ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).any { it === root },
                )
            }
        }
    }

    @Test
    fun internalActivityRoutesRoundTripAndRejectMalformedPayloads() {
        val routes = listOf(
            Route.About,
            Route.PersonalCenter,
            Route.Settings,
            Route.TopicList(
                ForumId.newBuilder().setFid("722").build(),
                categoryName = "中文 / % ? #",
            ),
            Route.TopicDetails("12345", postId = "42", startPage = 3),
            Route.UserProfile(userName = "中文 / % ? #"),
            Route.TopicSearch(),
            Route.ShortMessageDetails("123"),
        )
        routes.forEach { assertEquals(it, RouteCodec.decode(RouteCodec.encode(it))) }
        assertNull(RouteCodec.decode("unknown"))
        assertNull(RouteCodec.decode("topic-list"))
        assertNull(RouteCodec.decode("topic-list/%7B%7D"))
        assertNull(RouteCodec.decode("topic-details/not-json"))
    }

    private fun navigator(activity: MainActivity, route: Route) =
        Navigator(NavHostController(activity), listOf(route), activity)

    private fun awaitResumed(predicate: (Activity) -> Boolean): Activity {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            var activity: Activity? = null
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).firstOrNull(predicate)
            }
            activity?.let {
                instrumentation.waitForIdleSync()
                return it
            }
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Expected Activity did not resume")
    }
}

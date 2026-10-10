package com.bugenzhao.mnga

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.bugenzhao.mnga.protos.datamodel.FavoriteTopicFolder
import com.bugenzhao.mnga.protos.datamodel.Subject
import com.bugenzhao.mnga.protos.datamodel.Topic
import com.bugenzhao.mnga.protos.service.AsyncRequest
import com.bugenzhao.mnga.protos.service.FavoriteTopicListResponse
import com.bugenzhao.mnga.protos.service.TopicFavorRequest
import com.bugenzhao.mnga.protos.service.TopicFavorResponse
import com.bugenzhao.mnga.ui.nav.Navigator
import com.bugenzhao.mnga.ui.screens.favorites.FavoriteTopicDeletions
import com.bugenzhao.mnga.ui.screens.favorites.FavoriteTopicList
import com.bugenzhao.mnga.ui.screens.favorites.FavoriteTopicsModel
import com.bugenzhao.mnga.ui.theme.LumaGATheme
import com.bugenzhao.mnga.util.L
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FavoriteDeletionTest {
    @Test
    fun lastFavoriteCanBeSwipedAndEmptyFolderCanBeRefreshed() = withFavorites { scenario, device, fixture ->
        swipeTopic(device)
        await(scenario) { fixture.requests.size == 1 }
        scenario.onActivity {
            val request = fixture.requests.single().topicFavor
            assertEquals("7", request.folderId)
            assertEquals("10", request.topicId)
            assertEquals(TopicFavorRequest.Operation.DELETE, request.operation)
            fixture.responses.trySend(Result.success(TopicFavorResponse.getDefaultInstance()))
        }
        await(scenario) {
            fixture.model.dataSource("7").items.isEmpty() &&
                fixture.model.deletions.pending.value.isEmpty()
        }
        assertTrue(device.wait(Until.hasObject(By.text(noFavorites())), 5_000))

        device.swipe(
            device.displayWidth / 2, device.displayHeight / 3,
            device.displayWidth / 2, device.displayHeight * 3 / 4, 40,
        )
        await(scenario) { fixture.fetches > 0 && !fixture.model.dataSource("7").isLoading }
        assertTrue(device.hasObject(By.text(noFavorites())))
    }

    @Test
    fun failedLastFavoriteDeletionRestoresRowAndAllowsAnotherSwipe() = withFavorites { scenario, device, fixture ->
        swipeTopic(device)
        await(scenario) { fixture.requests.size == 1 }
        scenario.onActivity {
            fixture.responses.trySend(Result.failure(LogicException("delete failed")))
        }
        await(scenario) { fixture.model.deletions.pending.value.isEmpty() }
        assertTrue(device.wait(Until.hasObject(By.text(SUBJECT)), 5_000))
        swipeTopic(device)
        await(scenario) { fixture.requests.size == 2 }
        scenario.onActivity {
            fixture.responses.trySend(Result.success(TopicFavorResponse.getDefaultInstance()))
        }
        await(scenario) { fixture.model.dataSource("7").items.isEmpty() }
        assertTrue(device.wait(Until.hasObject(By.text(noFavorites())), 5_000))
    }

    @Test
    fun incompleteSwipeDoesNotDeleteTheLastFavorite() = withFavorites { scenario, device, fixture ->
        val row = device.wait(Until.findObject(By.text(SUBJECT)), 5_000)!!
        val bounds = row.visibleBounds
        val start = bounds.centerX()
        device.swipe(start, bounds.centerY(), start - bounds.width() / 30, bounds.centerY(), 40)
        SystemClock.sleep(500)
        scenario.onActivity {
            assertTrue(fixture.requests.isEmpty())
            assertEquals(1, fixture.model.dataSource("7").items.size)
        }
    }

    private class Fixture(scope: CoroutineScope) {
        val requests = mutableListOf<AsyncRequest>()
        val responses = Channel<Result<TopicFavorResponse>>(Channel.UNLIMITED)
        var fetches = 0
        val model = FavoriteTopicsModel(
            scope,
            FavoriteTopicDeletions(scope) {
                requests.add(it)
                responses.receive()
            },
        ) {
            fetches++
            Result.success(FavoriteTopicListResponse.getDefaultInstance())
        }
    }

    private fun withFavorites(
        test: (ActivityScenario<MainActivity>, UiDevice, Fixture) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var fixture: Fixture
                scenario.onActivity { activity ->
                    fixture = Fixture(scope)
                    fixture.model.dataSource("7").restoreItems(
                        listOf(
                            Topic.newBuilder().setId("10")
                                .setSubject(Subject.newBuilder().setContent(SUBJECT)).build()
                        ),
                        loadedPage = 1,
                        totalPages = 1,
                        lastRefreshTime = Date(),
                    )
                    activity.setContent {
                        val controller = rememberNavController()
                        val navigator = remember(controller) { Navigator(controller) }
                        LumaGATheme {
                            FavoriteTopicList(
                                FavoriteTopicFolder.newBuilder().setId("7").build(),
                                navigator,
                                fixture.model,
                            )
                        }
                    }
                }
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                assertTrue(device.wait(Until.hasObject(By.text(SUBJECT)), 5_000))
                test(scenario, device, fixture)
            }
        } finally {
            scope.cancel()
        }
    }

    private fun swipeTopic(device: UiDevice) {
        val row = device.wait(Until.findObject(By.text(SUBJECT)), 5_000)!!
        val bounds = row.visibleBounds
        device.swipe(
            device.displayWidth * 4 / 5, bounds.centerY(),
            device.displayWidth / 5, bounds.centerY(), 40,
        )
    }

    private fun await(scenario: ActivityScenario<MainActivity>, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var matched = false
            scenario.onActivity { matched = predicate() }
            if (matched) return
            SystemClock.sleep(25)
        }
        error("Timed out waiting for favorite state")
    }

    private fun noFavorites(): String =
        L.str(InstrumentationRegistry.getInstrumentation().targetContext, "No Favorites")

    companion object {
        private const val SUBJECT = "Favorite deletion regression topic"
    }
}

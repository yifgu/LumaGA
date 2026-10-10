package com.bugenzhao.mnga

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FavoriteDeletionTest {
    @Test
    fun lastFavoriteStaysDeletedAfterReturningToMainAndReopeningFolder() =
        withFavorites(navigation = true) { scenario, device, fixture ->
            val original = fixture.model
            swipeTopic(device)
            await(scenario) { fixture.requests.size == 1 }
            scenario.onActivity {
                fixture.responses.trySend(Result.success(TopicFavorResponse.getDefaultInstance()))
            }
            await(scenario) {
                original.dataSource("7").items.isEmpty() && fixture.deletions.pending.value.isEmpty()
            }
            assertTrue(device.wait(Until.hasObject(By.text(noFavorites())), 5_000))

            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text(OPEN_FAVORITES)), 5_000))
            device.findObject(By.text(OPEN_FAVORITES)).click()
            assertTrue(device.wait(Until.hasObject(By.text(noFavorites())), 5_000))
            await(scenario) { fixture.fetches == 2 && !fixture.model.dataSource("7").notLoaded }
            scenario.onActivity {
                assertNotSame(original, fixture.model)
                assertTrue(fixture.model.dataSource("7").items.isEmpty())
                assertTrue(fixture.topics.isEmpty())
                assertEquals(1, fixture.requests.size)
            }
            assertFalse(device.hasObject(By.text(SUBJECT)))
        }

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
        var topics = emptyList<Topic>()
        val deletions = FavoriteTopicDeletions(scope) { request ->
            requests.add(request)
            responses.receive().onSuccess {
                topics = topics.filterNot { it.id == request.topicFavor.topicId }
            }
        }
        var model = createModel(scope)

        fun createModel(scope: CoroutineScope) = FavoriteTopicsModel(scope, deletions) {
            fetches++
            Result.success(
                FavoriteTopicListResponse.newBuilder().addAllTopics(topics)
                    .setPages(if (topics.isEmpty()) 0 else 1).build()
            )
        }
    }

    private fun withFavorites(
        navigation: Boolean = false,
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
                    if (navigation) fixture.topics = fixture.model.dataSource("7").items
                    activity.setContent {
                        val controller = rememberNavController()
                        val navigator = remember(controller) { Navigator(controller) }
                        LumaGATheme {
                            if (navigation) {
                                NavHost(
                                    controller,
                                    startDestination = "main",
                                    modifier = Modifier.systemBarsPadding(),
                                ) {
                                    composable("main") {
                                        TextButton(onClick = { controller.navigate("favorites") }) {
                                            Text(OPEN_FAVORITES)
                                        }
                                    }
                                    composable("favorites") { entry ->
                                        val entryScope = rememberCoroutineScope()
                                        val model = remember(entry) {
                                            fixture.createModel(entryScope).also { fixture.model = it }
                                        }
                                        FavoriteTopicList(
                                            FavoriteTopicFolder.newBuilder().setId("7").build(),
                                            navigator,
                                            model,
                                        )
                                    }
                                }
                            } else {
                                FavoriteTopicList(
                                    FavoriteTopicFolder.newBuilder().setId("7").build(),
                                    navigator,
                                    fixture.model,
                                )
                            }
                        }
                    }
                }
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                if (navigation) {
                    assertTrue(device.wait(Until.hasObject(By.text(OPEN_FAVORITES)), 5_000))
                    device.findObject(By.text(OPEN_FAVORITES)).click()
                }
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
        private const val OPEN_FAVORITES = "Open favorite topics from main page"
    }
}

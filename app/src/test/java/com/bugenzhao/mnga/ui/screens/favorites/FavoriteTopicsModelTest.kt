package com.bugenzhao.mnga.ui.screens.favorites

import com.bugenzhao.mnga.protos.datamodel.Topic
import com.bugenzhao.mnga.protos.service.FavoriteTopicListResponse
import java.util.Date
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteTopicsModelTest {
    @Test
    fun `pending last-item deletion stays hidden when returning to a folder`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = FavoriteTopicsModel(scope, FavoriteTopicDeletions())
            val source = model.dataSource("1")
            source.restoreItems(listOf(Topic.newBuilder().setId("10").build()), 1, 1, Date())

            assertTrue(model.beginDelete("10"))
            assertFalse(model.beginDelete("10"))
            model.dataSource("2")
            val returned = model.dataSource("1")

            assertSame(source, returned)
            assertEquals(setOf("10"), model.deletingIds.value)
            assertTrue(returned.items.filterNot { it.id in model.deletingIds.value }.isEmpty())

            model.finishDelete("10", success = true)
            assertTrue(model.deletingIds.value.isEmpty())
            assertTrue(returned.items.isEmpty())
            assertFalse(returned.notLoaded)
            assertFalse(returned.hasMore)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failed deletion restores the last row and allows retry`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = FavoriteTopicsModel(scope, FavoriteTopicDeletions())
            val topic = Topic.newBuilder().setId("10").build()
            val source = model.dataSource("1")
            source.restoreItems(listOf(topic), 1, 1, Date())

            model.beginDelete("10")
            model.finishDelete("10", success = false)

            assertTrue(model.deletingIds.value.isEmpty())
            assertEquals(listOf(topic), source.items)
            assertTrue(model.beginDelete("10"))
            model.finishDelete("10", success = false)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `global unfavorite removes the topic from every cached folder`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = FavoriteTopicsModel(scope, FavoriteTopicDeletions())
            val deleted = Topic.newBuilder().setId("10").build()
            val remaining = Topic.newBuilder().setId("20").build()
            val first = model.dataSource("1")
            val second = model.dataSource("2")
            first.restoreItems(listOf(deleted), 1, 1, Date())
            second.restoreItems(listOf(deleted, remaining), 1, 1, Date())

            model.beginDelete("10")
            model.finishDelete("10", success = true)

            assertTrue(first.items.isEmpty())
            assertEquals(listOf(remaining), second.items)
            assertEquals(listOf(remaining), second.itemsAtPage(1))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `last-item deletion wins over an initial load in another folder`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val started = CompletableDeferred<Unit>()
            val response = CompletableDeferred<FavoriteTopicListResponse>()
            val model = FavoriteTopicsModel(scope, FavoriteTopicDeletions()) {
                started.complete(Unit)
                Result.success(response.await())
            }
            val topic = Topic.newBuilder().setId("10").build()
            model.dataSource("1").restoreItems(listOf(topic), 1, 1, Date())
            val other = model.dataSource("2")
            val load = other.initialLoad()!!
            started.await()

            model.beginDelete("10")
            model.finishDelete("10", success = true)
            response.complete(
                FavoriteTopicListResponse.newBuilder().addTopics(topic).setPages(1).build()
            )
            load.join()

            assertTrue(other.items.isEmpty())
            assertFalse(other.notLoaded)
            assertFalse(other.hasMore)
            assertFalse(other.isLoading)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `reopening favorites before deletion finishes does not resurrect the last row`() = runBlocking {
        val deletions = FavoriteTopicDeletions()
        val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val old = FavoriteTopicsModel(oldScope, deletions)
            assertTrue(old.beginDelete("10"))
            oldScope.cancel()

            val reopened = FavoriteTopicsModel(newScope, deletions)
            val source = reopened.dataSource("1")
            source.restoreItems(listOf(Topic.newBuilder().setId("10").build()), 1, 1, Date())
            assertEquals(setOf("10"), reopened.deletingIds.value)
            assertFalse(reopened.beginDelete("10"))

            old.finishDelete("10", success = true)
            assertTrue(reopened.deletingIds.value.isEmpty())
            assertTrue(source.items.isEmpty())
            assertFalse(source.notLoaded)
        } finally {
            oldScope.cancel()
            newScope.cancel()
        }
    }

    @Test
    fun `emptying the loaded page preserves pagination for remaining favorites`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val remaining = Topic.newBuilder().setId("20").build()
            val model = FavoriteTopicsModel(scope, FavoriteTopicDeletions()) {
                assertEquals(2, it.favoriteTopicList.page)
                Result.success(
                    FavoriteTopicListResponse.newBuilder().addTopics(remaining).setPages(2).build()
                )
            }
            val source = model.dataSource("1")
            source.restoreItems(listOf(Topic.newBuilder().setId("10").build()), 1, 2, Date())
            model.beginDelete("10")
            model.finishDelete("10", success = true)

            assertTrue(source.items.isEmpty())
            assertTrue(source.hasMore)
            source.loadMore().join()
            assertEquals(listOf(remaining), source.items)
            assertFalse(source.hasMore)
        } finally {
            scope.cancel()
        }
    }
}

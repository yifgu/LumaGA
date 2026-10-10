package com.bugenzhao.mnga.ui.screens.favorites

import com.bugenzhao.mnga.protos.datamodel.Topic
import com.bugenzhao.mnga.protos.service.FavoriteTopicListResponse
import com.bugenzhao.mnga.protos.service.TopicFavorRequest
import com.bugenzhao.mnga.protos.service.TopicFavorResponse
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
            val finish = CompletableDeferred<TopicFavorResponse>()
            val deletions = FavoriteTopicDeletions(scope) { request ->
                assertEquals("1", request.topicFavor.folderId)
                assertEquals("10", request.topicFavor.topicId)
                assertEquals(TopicFavorRequest.Operation.DELETE, request.topicFavor.operation)
                Result.success(finish.await())
            }
            val model = FavoriteTopicsModel(scope, deletions)
            val source = model.dataSource("1")
            source.restoreItems(listOf(Topic.newBuilder().setId("10").build()), 1, 1, Date())

            assertTrue(deletions.delete("1", "10"))
            assertFalse(deletions.delete("1", "10"))
            model.dataSource("2")
            val returned = model.dataSource("1")

            assertSame(source, returned)
            assertEquals(setOf(FavoriteTopicDeletions.Item("1", "10")), deletions.pending.value)
            assertTrue(returned.items.filterNot {
                FavoriteTopicDeletions.Item("1", it.id) in deletions.pending.value
            }.isEmpty())

            finish.complete(TopicFavorResponse.getDefaultInstance())
            assertTrue(deletions.pending.value.isEmpty())
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
            var requests = 0
            val deletions = FavoriteTopicDeletions(scope) {
                if (requests++ == 0) Result.failure(IllegalStateException("delete failed"))
                else Result.success(TopicFavorResponse.getDefaultInstance())
            }
            val model = FavoriteTopicsModel(scope, deletions)
            val topic = Topic.newBuilder().setId("10").build()
            val source = model.dataSource("1")
            source.restoreItems(listOf(topic), 1, 1, Date())

            var error: Throwable? = null
            deletions.delete("1", "10") { error = it }

            assertEquals("delete failed", error?.message)
            assertEquals(1, requests)
            assertTrue(deletions.pending.value.isEmpty())
            assertEquals(listOf(topic), source.items)
            assertTrue(deletions.delete("1", "10"))
            assertEquals(2, requests)
            assertTrue(source.items.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `deleting from one folder preserves the same topic in other folders`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val deletions = FavoriteTopicDeletions(scope) {
                // The topic remains favored in the other folder.
                Result.success(TopicFavorResponse.newBuilder().setIsFavored(true).addFolderIds("2").build())
            }
            val model = FavoriteTopicsModel(scope, deletions)
            val deleted = Topic.newBuilder().setId("10").build()
            val remaining = Topic.newBuilder().setId("20").build()
            val first = model.dataSource("1")
            val second = model.dataSource("2")
            first.restoreItems(listOf(deleted), 1, 1, Date())
            second.restoreItems(listOf(deleted, remaining), 1, 1, Date())

            deletions.delete("1", "10")

            assertTrue(first.items.isEmpty())
            assertEquals(listOf(deleted, remaining), second.items)
            assertEquals(listOf(deleted, remaining), second.itemsAtPage(1))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `last-item deletion wins over an initial load in another entry`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val started = CompletableDeferred<Unit>()
            val response = CompletableDeferred<FavoriteTopicListResponse>()
            val deletions = FavoriteTopicDeletions(scope) {
                Result.success(TopicFavorResponse.getDefaultInstance())
            }
            val model = FavoriteTopicsModel(scope, deletions) {
                started.complete(Unit)
                Result.success(response.await())
            }
            val topic = Topic.newBuilder().setId("10").build()
            val otherModel = FavoriteTopicsModel(scope, deletions)
            otherModel.dataSource("1").restoreItems(listOf(topic), 1, 1, Date())
            val other = model.dataSource("1")
            val load = other.initialLoad()!!
            started.await()

            deletions.delete("1", "10")
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
        val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val finish = CompletableDeferred<TopicFavorResponse>()
        val deletions = FavoriteTopicDeletions(requestScope) { Result.success(finish.await()) }
        val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val old = FavoriteTopicsModel(oldScope, deletions)
            assertTrue(old.deletions.delete("1", "10"))
            oldScope.cancel()

            val reopened = FavoriteTopicsModel(newScope, deletions)
            val source = reopened.dataSource("1")
            source.restoreItems(listOf(Topic.newBuilder().setId("10").build()), 1, 1, Date())
            assertEquals(setOf(FavoriteTopicDeletions.Item("1", "10")), reopened.deletions.pending.value)
            assertFalse(reopened.deletions.delete("1", "10"))

            finish.complete(TopicFavorResponse.getDefaultInstance())
            assertTrue(reopened.deletions.pending.value.isEmpty())
            assertTrue(source.items.isEmpty())
            assertFalse(source.notLoaded)
        } finally {
            oldScope.cancel()
            newScope.cancel()
            requestScope.cancel()
        }
    }

    @Test
    fun `emptying the loaded page preserves pagination for remaining favorites`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val remaining = Topic.newBuilder().setId("20").build()
            val deletions = FavoriteTopicDeletions(scope) {
                Result.success(TopicFavorResponse.getDefaultInstance())
            }
            val model = FavoriteTopicsModel(scope, deletions) {
                assertEquals(2, it.favoriteTopicList.page)
                Result.success(
                    FavoriteTopicListResponse.newBuilder().addTopics(remaining).setPages(2).build()
                )
            }
            val source = model.dataSource("1")
            source.restoreItems(listOf(Topic.newBuilder().setId("10").build()), 1, 2, Date())
            deletions.delete("1", "10")

            assertTrue(source.items.isEmpty())
            assertTrue(source.hasMore)
            source.loadMore().join()
            assertEquals(listOf(remaining), source.items)
            assertFalse(source.hasMore)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `queued deletions keep their folder and continue after the entry is closed`() = runBlocking {
        val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entryScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val finish = CompletableDeferred<TopicFavorResponse>()
            val requests = mutableListOf<String>()
            val deletions = FavoriteTopicDeletions(requestScope) {
                requests.add(it.topicFavor.folderId)
                Result.success(finish.await())
            }
            val model = FavoriteTopicsModel(entryScope, deletions)

            assertTrue(model.deletions.delete("1", "10"))
            assertTrue(model.deletions.delete("2", "10"))
            assertEquals(listOf("1"), requests)
            assertEquals(2, deletions.pending.value.size)
            entryScope.cancel()
            finish.complete(TopicFavorResponse.getDefaultInstance())

            assertEquals(listOf("1", "2"), requests)
            assertTrue(deletions.pending.value.isEmpty())
        } finally {
            requestScope.cancel()
            entryScope.cancel()
        }
    }

    @Test
    fun `thrown request failure clears pending state and permits retry`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var failures = 0
            val deletions = FavoriteTopicDeletions(scope) { throw IllegalStateException("failed") }

            assertTrue(deletions.delete("1", "10") { failures++ })
            assertTrue(deletions.pending.value.isEmpty())
            assertTrue(deletions.delete("1", "10") { failures++ })
            assertEquals(2, failures)
        } finally {
            scope.cancel()
        }
    }
}

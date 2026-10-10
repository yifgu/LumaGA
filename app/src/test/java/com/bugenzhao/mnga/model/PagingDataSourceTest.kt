package com.bugenzhao.mnga.model

import com.bugenzhao.mnga.protos.datamodel.Topic
import com.bugenzhao.mnga.protos.service.AsyncRequest
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
import org.junit.Assert.assertTrue
import org.junit.Test

class PagingDataSourceTest {

    @Test
    fun `deletions survive stale refresh reload and load more responses`() = runBlocking {
        for (operation in listOf("refresh", "reload", "loadMore")) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val started = CompletableDeferred<Unit>()
                val response = CompletableDeferred<FavoriteTopicListResponse>()
                val source = PagingDataSource(
                    scope = scope,
                    responseParser = { FavoriteTopicListResponse.parser() },
                    buildRequest = { AsyncRequest.getDefaultInstance() },
                    onResponse = { it.topicsList to it.pages },
                    id = { topic: Topic -> topic.id },
                    fetchResponse = {
                        started.complete(Unit)
                        Result.success(response.await())
                    },
                )
                val deleted = Topic.newBuilder().setId("1").build()
                val remaining = Topic.newBuilder().setId("2").build()
                source.restoreItems(listOf(deleted), 1, 2, Date())

                val load = when (operation) {
                    "refresh" -> source.refresh()
                    "reload" -> source.reload(1)
                    else -> source.loadMore()
                }
                started.await()
                assertTrue(source.removeItem("1"))
                response.complete(
                    FavoriteTopicListResponse.newBuilder()
                        .addTopics(deleted)
                        .addTopics(remaining)
                        .setPages(2)
                        .build()
                )
                load.join()

                assertEquals(operation, listOf("2"), source.items.map { it.id })
                assertFalse(operation, source.isLoading)
                assertFalse(operation, source.isRefreshing)

                // Later requests must still allow a topic that was favorited again.
                source.refresh().join()
                assertEquals(listOf("1", "2"), source.items.map { it.id })
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `removing the only favorite leaves a settled empty list`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val source = PagingDataSource(
                scope = scope,
                responseParser = { FavoriteTopicListResponse.parser() },
                buildRequest = { AsyncRequest.getDefaultInstance() },
                onResponse = { response -> response.topicsList to response.pages },
                id = { topic: Topic -> topic.id },
            )
            source.restoreItems(
                items = listOf(Topic.newBuilder().setId("1").build()),
                loadedPage = 1,
                totalPages = 1,
                lastRefreshTime = Date(),
            )

            assertTrue(source.removeItem("1"))
            assertTrue(source.items.isEmpty())
            assertTrue(source.itemsAtPage(1).isEmpty())
            assertFalse(source.isLoading)
            assertFalse(source.isRefreshing)
            assertFalse(source.isInitialLoading)
            assertFalse(source.notLoaded)
            assertFalse(source.hasMore)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `removeItem permanently removes a restored row and reindexes the page`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val source = PagingDataSource(
            scope = scope,
            responseParser = { FavoriteTopicListResponse.parser() },
            buildRequest = { AsyncRequest.getDefaultInstance() },
            onResponse = { response -> response.topicsList to response.pages },
            id = { topic: Topic -> topic.id },
        )
        val topics = listOf("1", "2", "3").map { id ->
            Topic.newBuilder().setId(id).build()
        }
        source.restoreItems(
            items = topics,
            loadedPage = 1,
            totalPages = 1,
            lastRefreshTime = null,
        )

        assertTrue(source.removeItem("2"))
        assertEquals(listOf("1", "3"), source.items.map { it.id })
        assertEquals(listOf("1", "3"), source.itemsAtPage(1).map { it.id })
        assertFalse(source.removeItem("2"))

        scope.cancel()
    }
}

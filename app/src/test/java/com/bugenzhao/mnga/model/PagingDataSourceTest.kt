package com.bugenzhao.mnga.model

import com.bugenzhao.mnga.protos.datamodel.Topic
import com.bugenzhao.mnga.protos.service.AsyncRequest
import com.bugenzhao.mnga.protos.service.FavoriteTopicListResponse
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagingDataSourceTest {

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

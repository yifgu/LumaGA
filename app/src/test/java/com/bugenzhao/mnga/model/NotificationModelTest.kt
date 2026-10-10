package com.bugenzhao.mnga.model

import com.bugenzhao.mnga.protos.datamodel.Notification
import java.util.Date
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationModelTest {

    @Test
    fun `opening an unread reply updates the shared list and main page badge`() = runBlocking {
        val requests = mutableListOf<Pair<List<String>, Boolean>>()
        withModel(persistRead = { ids, read -> requests.add(ids to read) }) { model ->
            restore(model, unread("reply"), unread("other"))
            yield()
            assertEquals(2, model.unreadCountAnimated.value)

            model.markRead(listOf("reply"), read = true).join()
            yield()

            assertTrue(model.state.value.items.first().read)
            assertEquals(1, model.unreadCount)
            assertEquals(1, model.unreadCountAnimated.value)
            assertEquals(listOf(listOf("reply") to true), requests)
        }
    }

    @Test
    fun `marking finishes after the originating screen scope is cancelled`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        withModel(persistRead = { _, _ ->
            started.complete(Unit)
            finish.await()
        }) { model ->
            restore(model, unread("reply"))
            val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                screenScope.launch { model.markRead(listOf("reply"), read = true) }
                started.await()
                screenScope.cancel()
                finish.complete(Unit)
                yield()

                assertTrue(model.items.single().read)
                assertEquals(0, model.unreadCountAnimated.value)
            } finally {
                screenScope.cancel()
            }
        }
    }

    @Test
    fun `read toggles and mark all keep every counter in sync`() = runBlocking {
        withModel { model ->
            restore(model, unread("reply"), unread("other"))

            model.markRead(listOf("reply"), read = true).join()
            yield()
            assertEquals(1, model.unreadCountAnimated.value)

            model.markRead(listOf("reply"), read = false).join()
            yield()
            assertFalse(model.items.first().read)
            assertEquals(2, model.unreadCountAnimated.value)

            model.markRead(model.items.map { it.id }, read = true).join()
            yield()
            assertTrue(model.items.all { it.read })
            assertEquals(0, model.unreadCount)
            assertEquals(0, model.unreadCountAnimated.value)
        }
    }

    @Test
    fun `stale refresh cannot resurrect locally read notifications`() = runBlocking {
        withModel { model ->
            val reply = unread("reply")
            restore(model, reply)
            model.markRead(listOf("reply"), read = true).join()

            restore(model, reply, unread("new"))
            yield()

            assertTrue(model.items.first().read)
            assertFalse(model.items.last().read)
            assertEquals(1, model.unreadCountAnimated.value)
            assertEquals(model.dataSource.lastRefreshTime, model.state.value.lastRefreshTime)

            model.markRead(listOf("reply"), read = false).join()
            restore(model, reply.toBuilder().setRead(true).build(), unread("new"))
            yield()
            assertFalse(model.items.first().read)
            assertEquals(2, model.unreadCountAnimated.value)
        }
    }

    @Test
    fun `failed persistence leaves read state unchanged and does not report success`() = runBlocking {
        ToastModel.banner.dismiss()
        try {
            withModel(persistRead = { _, _ -> error("mark failed") }) { model ->
                restore(model, unread("reply"))
                var succeeded = false

                model.markRead(listOf("reply"), read = true) { succeeded = true }.join()
                yield()

                assertFalse(succeeded)
                assertFalse(model.items.single().read)
                assertEquals(1, model.unreadCountAnimated.value)
                assertEquals(
                    "mark failed",
                    (ToastModel.banner.message.value as ToastModel.Message.Error).error,
                )
            }
        } finally {
            ToastModel.banner.dismiss()
        }
    }

    @Test
    fun `overlapping changes are persisted in order`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val requests = mutableListOf<Boolean>()
        withModel(persistRead = { _, read ->
            requests.add(read)
            if (read) {
                started.complete(Unit)
                finish.await()
            }
        }) { model ->
            restore(model, unread("reply"))
            val first = model.markRead(listOf("reply"), read = true)
            started.await()
            val second = model.markRead(listOf("reply"), read = false)
            assertEquals(listOf(true), requests)

            finish.complete(Unit)
            first.join()
            second.join()
            yield()

            assertEquals(listOf(true, false), requests)
            assertFalse(model.items.single().read)
            assertEquals(1, model.unreadCountAnimated.value)
        }
    }

    @Test
    fun `empty and repeated marking do not decrement the badge twice`() = runBlocking {
        var requests = 0
        withModel(persistRead = { _, _ -> requests++ }) { model ->
            restore(model, unread("reply"))
            model.markRead(emptyList(), read = true).join()
            assertEquals(0, requests)

            repeat(2) { model.markRead(listOf("reply", "reply"), read = true).join() }
            yield()

            assertEquals(0, model.unreadCount)
            assertEquals(0, model.unreadCountAnimated.value)
        }
    }

    private fun unread(id: String): Notification =
        Notification.newBuilder()
            .setId(id)
            .setType(Notification.Type.REPLY_POST)
            .build()

    private fun restore(model: NotificationModel, vararg items: Notification) {
        model.dataSource.restoreItems(
            items = items.toList(),
            loadedPage = 1,
            totalPages = 1,
            lastRefreshTime = Date(),
        )
    }

    private suspend fun withModel(
        persistRead: suspend (List<String>, Boolean) -> Unit = { _, _ -> },
        block: suspend (NotificationModel) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            block(NotificationModel(scope, persistRead, pollIntervalMillis = null))
        } finally {
            scope.cancel()
        }
    }
}

package com.bugenzhao.mnga.ui.screens.favorites

import com.bugenzhao.mnga.LogicException
import com.bugenzhao.mnga.protos.datamodel.FavoriteTopicFolder
import com.bugenzhao.mnga.protos.datamodel.Topic
import com.bugenzhao.mnga.protos.service.FavoriteFolderListResponse
import com.bugenzhao.mnga.protos.service.FavoriteFolderModifyResponse
import com.bugenzhao.mnga.protos.service.FavoriteTopicListResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteFoldersModelTest {
    @Test
    fun `deleting the last topic removes the default folder in every live entry and on reopening`() = runBlocking {
        val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entryScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val folder = FavoriteTopicFolder.newBuilder().setId("1").setIsDefault(true).build()
            val other = FavoriteTopicFolder.newBuilder().setId("2").build()
            var folders = listOf(folder, other)
            val finish = CompletableDeferred<Unit>()
            val deletions = FavoriteTopicDeletions(
                requestScope,
                fetchTopics = {
                    Result.success(
                        FavoriteTopicListResponse.newBuilder()
                            .addTopics(Topic.newBuilder().setId("10")).setPages(1).build()
                    )
                },
                fetchFolders = {
                    Result.success(
                        FavoriteFolderListResponse.newBuilder()
                            .addFolders(folder.toBuilder().setTopicCount(1)).build()
                    )
                },
                deleteFolder = {
                    finish.await()
                    folders = listOf(other.toBuilder().setIsDefault(true).build())
                    Result.success(FavoriteFolderModifyResponse.getDefaultInstance())
                },
                deleteTopic = { error("Must delete the folder") },
            )
            val fetch: suspend () -> Result<FavoriteFolderListResponse> = {
                Result.success(FavoriteFolderListResponse.newBuilder().addAllFolders(folders).build())
            }
            val original = FavoriteFoldersModel(entryScope, deletions, fetch)
            original.load()
            deletions.delete("1", "10", deleteFolderIfLast = true)
            entryScope.cancel()
            val reopened = FavoriteFoldersModel(reopenedScope, deletions, fetch)
            val another = FavoriteFoldersModel(reopenedScope, deletions, fetch)
            reopened.load()
            another.load()

            finish.complete(Unit)

            assertEquals(folders, reopened.state.value.folders)
            assertEquals(folders, another.state.value.folders)
            assertEquals("2", reopened.state.value.folders.single().id)
            assertTrue(reopened.state.value.folders.single().isDefault)
            val fresh = FavoriteFoldersModel(reopenedScope, deletions, fetch)
            fresh.load()
            assertEquals(folders, fresh.state.value.folders)
        } finally {
            requestScope.cancel()
            entryScope.cancel()
            reopenedScope.cancel()
        }
    }

    @Test
    fun `folder deletion wins over an in-flight stale folder load even if refresh fails`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val folder = FavoriteTopicFolder.newBuilder().setId("1").build()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val deletions = FavoriteTopicDeletions(
                scope,
                fetchTopics = {
                    Result.success(
                        FavoriteTopicListResponse.newBuilder()
                            .addTopics(Topic.newBuilder().setId("10")).setPages(1).build()
                    )
                },
                fetchFolders = {
                    Result.success(
                        FavoriteFolderListResponse.newBuilder()
                            .addFolders(folder.toBuilder().setTopicCount(1)).build()
                    )
                },
                deleteFolder = { Result.success(FavoriteFolderModifyResponse.getDefaultInstance()) },
                deleteTopic = { error("Must delete the folder") },
            )
            var requests = 0
            val model = FavoriteFoldersModel(scope, deletions) {
                if (requests++ == 0) {
                    started.complete(Unit)
                    finish.await()
                    Result.success(FavoriteFolderListResponse.newBuilder().addFolders(folder).build())
                } else {
                    Result.failure(LogicException("refresh failed"))
                }
            }
            val load = scope.launch { model.load() }
            started.await()

            deletions.delete("1", "10", deleteFolderIfLast = true)
            finish.complete(Unit)
            load.join()

            assertEquals(2, requests)
            assertTrue(model.state.value.folders.isEmpty())
            assertFalse(model.state.value.isLoading)
            assertEquals("refresh failed", model.state.value.latestError?.error)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `slow folder refresh does not block subsequent folder deletions or restore stale folders`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var folders = listOf("1", "2").map {
                FavoriteTopicFolder.newBuilder().setId(it).setTopicCount(1).build()
            }
            val finish = CompletableDeferred<Unit>()
            val deletions = FavoriteTopicDeletions(
                scope,
                fetchTopics = {
                    Result.success(
                        FavoriteTopicListResponse.newBuilder()
                            .addTopics(Topic.newBuilder().setId("10")).setPages(1).build()
                    )
                },
                fetchFolders = {
                    Result.success(
                        FavoriteFolderListResponse.newBuilder().addAllFolders(folders).build()
                    )
                },
                deleteFolder = { request ->
                    folders = folders.filterNot { it.id == request.favoriteFolderModify.folderId }
                    Result.success(FavoriteFolderModifyResponse.getDefaultInstance())
                },
                deleteTopic = { error("Must delete the folder") },
            )
            var requests = 0
            val model = FavoriteFoldersModel(scope, deletions) {
                val snapshot = FavoriteFolderListResponse.newBuilder().addAllFolders(folders).build()
                if (requests++ == 1) finish.await()
                Result.success(snapshot)
            }
            model.load()

            deletions.delete("1", "10", deleteFolderIfLast = true)
            assertTrue(model.state.value.isLoading)
            deletions.delete("2", "10", deleteFolderIfLast = true)

            assertTrue(folders.isEmpty())
            assertTrue(deletions.pending.value.isEmpty())
            assertTrue(model.state.value.folders.isEmpty())
            finish.complete(Unit)
            assertEquals(3, requests)
            assertFalse(model.state.value.isLoading)
            assertTrue(model.state.value.folders.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `empty folders finish loading and do not refetch on return`() = runBlocking {
        var requests = 0
        val model = FavoriteFoldersModel {
            requests++
            Result.success(FavoriteFolderListResponse.getDefaultInstance())
        }

        model.load()
        assertTrue(model.state.value.hasLoaded)
        assertFalse(model.state.value.isLoading)
        assertTrue(model.state.value.folders.isEmpty())
        assertNull(model.state.value.latestError)

        model.load()
        assertEquals(1, requests)
        model.load(force = true)
        assertEquals(2, requests)
    }

    @Test
    fun `failed folder load stops loading and can be retried`() = runBlocking {
        var fail = true
        val folder = FavoriteTopicFolder.newBuilder().setId("1").build()
        val model = FavoriteFoldersModel {
            if (fail) {
                Result.failure(LogicException("load failed"))
            } else {
                Result.success(FavoriteFolderListResponse.newBuilder().addFolders(folder).build())
            }
        }

        model.load()
        assertFalse(model.state.value.isLoading)
        assertFalse(model.state.value.hasLoaded)
        assertEquals("load failed", model.state.value.latestError?.error)

        fail = false
        model.load(force = true)
        assertFalse(model.state.value.isLoading)
        assertTrue(model.state.value.hasLoaded)
        assertEquals(listOf(folder), model.state.value.folders)
        assertNull(model.state.value.latestError)
    }

    @Test
    fun `loading is tracked and duplicate folder requests are ignored`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var requests = 0
        val model = FavoriteFoldersModel {
            requests++
            started.complete(Unit)
            finish.await()
            Result.success(FavoriteFolderListResponse.getDefaultInstance())
        }
        val load = launch { model.load() }
        started.await()
        assertTrue(model.state.value.isLoading)
        val duplicate = launch { model.load() }
        assertEquals(1, requests)
        finish.complete(Unit)
        load.join()
        duplicate.join()
        assertFalse(model.state.value.isLoading)
        assertTrue(model.state.value.hasLoaded)
    }

    @Test
    fun `forced folder refresh waits for the initial load instead of being dropped`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val folder = FavoriteTopicFolder.newBuilder().setId("1").build()
        var requests = 0
        val model = FavoriteFoldersModel {
            if (requests++ == 0) {
                started.complete(Unit)
                finish.await()
                Result.success(FavoriteFolderListResponse.getDefaultInstance())
            } else {
                Result.success(FavoriteFolderListResponse.newBuilder().addFolders(folder).build())
            }
        }
        val load = launch { model.load() }
        started.await()
        val refresh = launch { model.load(force = true) }
        finish.complete(Unit)
        load.join()
        refresh.join()

        assertEquals(2, requests)
        assertEquals(listOf(folder), model.state.value.folders)
        assertFalse(model.state.value.isLoading)
    }

    @Test
    fun `cancelling folder loading allows another load`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var requests = 0
        val model = FavoriteFoldersModel {
            if (requests++ == 0) {
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            Result.success(FavoriteFolderListResponse.getDefaultInstance())
        }
        val load = launch { model.load() }
        started.await()
        load.cancel()
        load.join()
        assertFalse(model.state.value.isLoading)
        model.load()
        assertTrue(model.state.value.hasLoaded)
        assertEquals(2, requests)
    }
}

package com.bugenzhao.mnga.ui.screens.favorites

import com.bugenzhao.mnga.LogicException
import com.bugenzhao.mnga.protos.datamodel.FavoriteTopicFolder
import com.bugenzhao.mnga.protos.service.FavoriteFolderListResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteFoldersModelTest {
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
        model.load(force = true)
        assertEquals(1, requests)
        finish.complete(Unit)
        load.join()
        assertFalse(model.state.value.isLoading)
        assertTrue(model.state.value.hasLoaded)
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

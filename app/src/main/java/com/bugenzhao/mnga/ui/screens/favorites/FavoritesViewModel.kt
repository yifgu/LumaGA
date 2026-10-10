package com.bugenzhao.mnga.ui.screens.favorites

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugenzhao.mnga.LogicException
import com.bugenzhao.mnga.logicCallAsync
import com.bugenzhao.mnga.model.PagingDataSource
import com.bugenzhao.mnga.protos.datamodel.FavoriteTopicFolder
import com.bugenzhao.mnga.protos.datamodel.Topic
import com.bugenzhao.mnga.protos.service.AsyncRequest
import com.bugenzhao.mnga.protos.service.FavoriteFolderCreateRequest
import com.bugenzhao.mnga.protos.service.FavoriteFolderCreateResponse
import com.bugenzhao.mnga.protos.service.FavoriteFolderListRequest
import com.bugenzhao.mnga.protos.service.FavoriteFolderListResponse
import com.bugenzhao.mnga.protos.service.FavoriteFolderModifyRequest
import com.bugenzhao.mnga.protos.service.FavoriteFolderModifyResponse
import com.bugenzhao.mnga.protos.service.FavoriteTopicListRequest
import com.bugenzhao.mnga.protos.service.FavoriteTopicListResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Favorite folders of the logged-in user (SS15). */
class FavoriteFoldersModel(
    private val fetchFolders: suspend () -> Result<FavoriteFolderListResponse> = {
        logicCallAsync(
            AsyncRequest.newBuilder()
                .setFavoriteFolderList(FavoriteFolderListRequest.getDefaultInstance())
                .build(),
            FavoriteFolderListResponse.parser(),
        )
    },
) {
    data class State(
        val folders: List<FavoriteTopicFolder> = emptyList(),
        val isLoading: Boolean = false,
        val hasLoaded: Boolean = false,
        val latestError: LogicException? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val loadMutex = Mutex()

    suspend fun load(force: Boolean = false) = loadMutex.withLock {
        if (_state.value.hasLoaded && !force) return@withLock
        _state.value = _state.value.copy(isLoading = true, latestError = null)
        try {
            fetchFolders().fold(
                onSuccess = { response ->
                    _state.value = _state.value.copy(
                        folders = response.foldersList,
                        hasLoaded = true,
                    )
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        latestError = error as? LogicException
                            ?: LogicException(error.message ?: "error"),
                    )
                },
            )
        } finally {
            _state.value = _state.value.copy(isLoading = false)
        }
    }

    suspend fun modify(request: FavoriteFolderModifyRequest): Boolean {
        val result = logicCallAsync(
            AsyncRequest.newBuilder().setFavoriteFolderModify(request).build(),
            FavoriteFolderModifyResponse.parser(),
        )
        return if (result.isSuccess) {
            load(force = true)
            true
        } else {
            false
        }
    }

    /** Creates a folder and returns its id, or null on failure. */
    suspend fun create(name: String): String? {
        val result = logicCallAsync(
            AsyncRequest.newBuilder()
                .setFavoriteFolderCreate(
                    FavoriteFolderCreateRequest.newBuilder().setName(name).build()
                )
                .build(),
            FavoriteFolderCreateResponse.parser(),
        )
        return result.getOrNull()?.folderId?.also { load(force = true) }
    }
}

/**
 * Entry-scoped holder of the favorites screen state.
 *
 * NavHost scopes [ViewModel]s to the back-stack entry: when the entry is
 * covered, its composition is disposed but the ViewModel (folders, the
 * selected folder id, loaded topic lists) survives, so popping back reuses
 * it instead of refetching — no manual snapshot needed. The selected folder
 * id lives in [SavedStateHandle] so it also survives process death.
 */
class FavoritesViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    val foldersModel = FavoriteFoldersModel()

    /** The folder the user last selected; kept across pop-backs and process death. */
    var currentFolderId: String?
        get() = savedStateHandle["currentFolderId"]
        set(value) {
            savedStateHandle["currentFolderId"] = value
        }

    val topicsModel = FavoriteTopicsModel(viewModelScope)

    fun topicDataSource(folderId: String): PagingDataSource<FavoriteTopicListResponse, Topic> =
        topicsModel.dataSource(folderId)
}

/** Coordinates app-scoped delete requests, including when a favorites entry is reopened. */
class FavoriteTopicDeletions {
    private val _deletingIds = MutableStateFlow<Set<String>>(emptySet())
    val deletingIds: StateFlow<Set<String>> = _deletingIds
    private val _confirmedIds = MutableSharedFlow<String>()
    val confirmedIds: SharedFlow<String> = _confirmedIds

    fun beginDelete(topicId: String): Boolean {
        if (topicId in _deletingIds.value) return false
        _deletingIds.value = _deletingIds.value + topicId
        return true
    }

    suspend fun finishDelete(topicId: String, success: Boolean) {
        if (success) _confirmedIds.emit(topicId)
        _deletingIds.value = _deletingIds.value - topicId
    }

    companion object {
        val shared = FavoriteTopicDeletions()
    }
}

/** Retains folder snapshots and observes deletions for the lifetime of its entry. */
class FavoriteTopicsModel(
    private val scope: CoroutineScope,
    private val deletions: FavoriteTopicDeletions = FavoriteTopicDeletions.shared,
    private val fetchTopics: suspend (AsyncRequest) -> Result<FavoriteTopicListResponse> = {
        logicCallAsync(it, FavoriteTopicListResponse.parser())
    },
) {
    val deletingIds: StateFlow<Set<String>> = deletions.deletingIds

    private val topicSources =
        mutableMapOf<String, PagingDataSource<FavoriteTopicListResponse, Topic>>()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            deletions.confirmedIds.collect { topicId ->
                // The RPC unfavorites globally, including other active entries.
                topicSources.values.forEach { it.removeItem(topicId) }
            }
        }
    }

    fun beginDelete(topicId: String): Boolean = deletions.beginDelete(topicId)

    suspend fun finishDelete(topicId: String, success: Boolean) =
        deletions.finishDelete(topicId, success)

    fun dataSource(folderId: String): PagingDataSource<FavoriteTopicListResponse, Topic> =
        topicSources.getOrPut(folderId) {
            PagingDataSource(
                scope = scope,
                responseParser = { FavoriteTopicListResponse.parser() },
                buildRequest = { page ->
                    AsyncRequest.newBuilder()
                        .setFavoriteTopicList(
                            FavoriteTopicListRequest.newBuilder()
                                .setFolderId(folderId)
                                .setPage(page)
                                .build()
                        )
                        .build()
                },
                onResponse = { response ->
                    Pair(response.topicsList, response.pages.toInt().takeIf { it > 0 })
                },
                id = { it.id },
                fetchResponse = fetchTopics,
            )
        }
}

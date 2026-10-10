package com.bugenzhao.mnga.model

import com.bugenzhao.mnga.logicCall
import com.bugenzhao.mnga.protos.service.AsyncRequest
import com.bugenzhao.mnga.protos.service.FetchNotificationRequest
import com.bugenzhao.mnga.protos.service.FetchNotificationResponse
import com.bugenzhao.mnga.protos.service.MarkNotificationReadRequest
import com.bugenzhao.mnga.protos.service.SyncRequest
import com.bugenzhao.mnga.protos.datamodel.Notification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Polls the notification list on a timer and exposes the unread badge count,
 * ported from `Models/NotificationModel.swift`. Built on [PagingDataSource]
 * pinned to a single page, like the iOS subclass.
 */
class NotificationModel(
    private val scope: CoroutineScope,
    private val persistRead: suspend (List<String>, Boolean) -> Unit = { ids, read ->
        withContext(Dispatchers.IO) {
            logicCall(
                SyncRequest.newBuilder()
                    .setMarkNotiRead(
                        MarkNotificationReadRequest.newBuilder()
                            .addAllIds(ids)
                            .setRead(read)
                    )
                    .build(),
            )
        }
    },
    private val pollIntervalMillis: Long? =
        if (com.bugenzhao.mnga.BuildConfig.DEBUG) 10_000L else 60_000L,
) {

    companion object {
        var shared: NotificationModel? = null
    }

    val dataSource = PagingDataSource<FetchNotificationResponse, Notification>(
        scope = scope,
        responseParser = { FetchNotificationResponse.parser() },
        buildRequest = { _ ->
            AsyncRequest.newBuilder()
                .setFetchNotification(FetchNotificationRequest.getDefaultInstance())
                .build()
        },
        onResponse = { response -> Pair(response.notisList, 1) },
        id = { it.id },
    )

    val showingFromUserMenu = MutableStateFlow(false)
    val showingSheet = MutableStateFlow(false)

    // Successful local changes survive navigation and stale in-flight fetches.
    private val readOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val markMutex = Mutex()

    val state: StateFlow<PagingDataSource.State<Notification>> =
        combine(dataSource.state, readOverrides) { fetched, overrides ->
            fetched.copy(
                items = fetched.items.map { noti ->
                    val read = overrides[noti.id] ?: noti.read
                    if (read == noti.read) noti else noti.toBuilder().setRead(read).build()
                },
            )
        }.stateIn(scope, SharingStarted.Eagerly, dataSource.state.value)

    val items: List<Notification> get() = state.value.items
    val unreadCount: Int
        get() {
            val overrides = readOverrides.value
            return dataSource.items.count { !(overrides[it.id] ?: it.read) }
        }

    private val _unreadCountAnimated = MutableStateFlow(0)
    val unreadCountAnimated: StateFlow<Int> = _unreadCountAnimated

    init {
        // Poll: 10 s in debug, 60 s in release; immediate first tick.
        pollIntervalMillis?.let { interval ->
            scope.launch {
                while (true) {
                    refreshNotis()
                    delay(interval)
                }
            }
        }
        scope.launch {
            state.collect { state ->
                val count = state.items.count { !it.read }
                if (_unreadCountAnimated.value != count) _unreadCountAnimated.value = count
            }
        }
    }

    fun markRead(ids: List<String>, read: Boolean, onSuccess: () -> Unit = {}): Job =
        scope.launch {
            if (ids.isEmpty()) return@launch
            markMutex.withLock {
                try {
                    persistRead(ids, read)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ToastModel.showAuto(ToastModel.Message.Error(e.message ?: "error"))
                    return@withLock
                }
                readOverrides.value = readOverrides.value + ids.associateWith { read }
                onSuccess()
            }
        }

    fun clearReadOverrides(): Job =
        scope.launch {
            markMutex.withLock {
                readOverrides.value = emptyMap()
            }
        }

    fun refreshNotis() {
        scope.launch {
            val oldCount = unreadCount
            dataSource.refresh(silentOnError = true).join()
            if (unreadCount > oldCount) {
                ToastModel.showAuto(ToastModel.Message.Notification(unreadCount - oldCount))
            }
        }
    }
}

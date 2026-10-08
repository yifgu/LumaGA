package com.bugenzhao.mnga.util

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Bounded, app-private diagnostics; file I/O never runs on the gesture thread. */
object BackDiagnostics {
    private const val TAG = "LumaGABack"
    private const val MAX_BYTES = 256 * 1024
    private sealed interface Command {
        data class Line(val text: String) : Command
        data class Snapshot(val result: CompletableDeferred<String>) : Command
    }

    private val commands = Channel<Command>(256)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(context: Context) {
        val directory = File(context.noBackupFilesDir, "back-diagnostics")
        scope.launch {
            val current = File(directory, "predictive-back.txt")
            val previous = File(directory, "predictive-back-previous.txt")
            for (command in commands) {
                when (command) {
                    is Command.Line -> {
                        runCatching {
                            check(directory.isDirectory || directory.mkdirs())
                            if (current.length() >= MAX_BYTES) {
                                check(!previous.exists() || previous.delete())
                                check(current.renameTo(previous))
                            }
                            current.appendText(command.text)
                        }.onFailure { Log.w(TAG, "Could not persist back diagnostics", it) }
                    }
                    is Command.Snapshot -> {
                        runCatching {
                            listOf(previous, current).filter { it.exists() }
                                .joinToString("") { it.readText() }
                        }.fold(
                            onSuccess = { command.result.complete(it) },
                            onFailure = { command.result.completeExceptionally(it) },
                        )
                    }
                }
            }
        }
    }

    fun log(message: String) {
        Log.i(TAG, message)
        // Drop excess diagnostics rather than delaying navigation.
        commands.trySend(Command.Line("${System.currentTimeMillis()} $TAG $message\n"))
    }

    suspend fun snapshot(): String {
        val result = CompletableDeferred<String>()
        commands.send(Command.Snapshot(result))
        return result.await()
    }
}

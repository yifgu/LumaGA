package com.bugenzhao.mnga.util

/** Records callback values directly, independently of AndroidX's conflated gesture state. */
internal class RawBackGestureTrace(
    private val uptimeMillis: () -> Long,
    private val log: (String) -> Unit,
) {
    var progress = 0f
        private set

    private var active = false
    private var startedReceived = false
    private var gesture = 0
    private var startedAt = 0L
    private var progressCallbacks = 0
    private var maxProgress = 0f
    private var edge = -1

    fun started(progress: Float, edge: Int): String {
        interrupted()
        begin(startedReceived = true)
        update(progress, edge)
        return record("started")
    }

    fun progressed(progress: Float, edge: Int): String {
        if (!active) begin(startedReceived = false)
        progressCallbacks++
        update(progress, edge)
        return record("progress")
    }

    fun cancelled(): String = finish("cancelled")

    fun invoked(): String = finish("invoked")

    fun interrupted(): String? = if (active) finish("interrupted") else null

    private fun begin(startedReceived: Boolean) {
        gesture++
        active = true
        this.startedReceived = startedReceived
        startedAt = uptimeMillis()
        progress = 0f
        progressCallbacks = 0
        maxProgress = 0f
        edge = -1
    }

    private fun update(progress: Float, edge: Int) {
        this.progress = progress
        this.edge = edge
        maxProgress = maxOf(maxProgress, progress)
    }

    private fun finish(event: String): String {
        // Button back can invoke without any predictive start/progress callbacks.
        if (!active) begin(startedReceived = false)
        val result = record(event)
        active = false
        return result
    }

    private fun record(event: String): String {
        val line = "gesture=$gesture event=$event progress=$progress edge=$edge " +
            "progressCallbacks=$progressCallbacks maxProgress=$maxProgress " +
            "elapsedMs=${uptimeMillis() - startedAt} startedReceived=$startedReceived"
        log(line)
        return line
    }
}

package com.bugenzhao.mnga.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawBackGestureTraceTest {
    private var now = 100L
    private val logs = mutableListOf<String>()
    private val trace = RawBackGestureTrace({ now }, logs::add)

    @Test
    fun cancellationRecordsEveryZeroProgressCallbackWithoutConflation() {
        trace.started(0f, 0)
        repeat(6) {
            now += 10
            trace.progressed(0f, 0)
        }
        now = 993
        val result = trace.cancelled()

        assertEquals(8, logs.size)
        assertEquals(6, logs.count { "event=progress " in it })
        assertTrue(result.contains("event=cancelled"))
        assertTrue(result.contains("progressCallbacks=6 maxProgress=0.0 elapsedMs=893"))
        assertTrue(result.contains("startedReceived=true"))
        assertNull(trace.interrupted())
    }

    @Test
    fun cancellationKeepsMaximumProgressAndNextGestureResetsStatistics() {
        trace.started(0f, 1)
        trace.progressed(0.7f, 1)
        trace.progressed(0.05f, 1)
        val cancelled = trace.cancelled()
        assertTrue(cancelled.contains("progress=0.05 edge=1"))
        assertTrue(cancelled.contains("maxProgress=0.7"))

        now = 300
        trace.started(0f, 0)
        trace.progressed(0.2f, 0)
        now = 400
        val invoked = trace.invoked()
        assertTrue(invoked.contains("gesture=2 event=invoked"))
        assertTrue(invoked.contains("progressCallbacks=1 maxProgress=0.2 elapsedMs=100"))
        assertEquals(0.2f, trace.progress, 0f)
    }

    @Test
    fun buttonBackDoesNotReusePreviousGestureProgress() {
        trace.started(0f, 0)
        trace.progressed(0.8f, 0)
        trace.invoked()
        val buttonBack = trace.invoked()

        assertTrue(buttonBack.contains("gesture=2 event=invoked progress=0.0 edge=-1"))
        assertTrue(buttonBack.contains("progressCallbacks=0 maxProgress=0.0"))
        assertTrue(buttonBack.contains("startedReceived=false"))
    }

    @Test
    fun stoppingObserverIsNotMisreportedAsCancellation() {
        assertNull(trace.interrupted())
        trace.started(0f, 0)
        now = 200
        val interrupted = trace.interrupted()!!

        assertTrue(interrupted.contains("event=interrupted"))
        assertTrue(interrupted.contains("elapsedMs=100"))
        assertNull(trace.interrupted())
    }

    @Test
    fun progressWithoutStartIsRecordedWithoutInventingAStartCallback() {
        val progress = trace.progressed(0.3f, 1)
        assertTrue(progress.contains("startedReceived=false"))
        assertTrue(progress.contains("progressCallbacks=1 maxProgress=0.3"))
        assertEquals(1, logs.size)
    }
}

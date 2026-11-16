package com.voxbridge.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PushToTalkHoldTest {
    @Test fun `normal release ends one started hold exactly once`() = runBlocking {
        val calls = mutableListOf<String>()
        runPushToTalkHold(
            onStart = { calls += "start" },
            onEnd = { calls += "end" },
            awaitRelease = { calls += "release" },
        )
        assertEquals(listOf("start", "release", "end"), calls)
    }

    @Test fun `gesture cancellation return still ends the hold`() = runBlocking {
        var ends = 0
        fun cancelledGesture() = false
        runPushToTalkHold(onStart = {}, onEnd = { ends++ }) {
            // tryAwaitRelease returns false for gesture cancellation without throwing.
            cancelledGesture()
        }
        assertEquals(1, ends)
    }

    @Test fun `coroutine cancellation while held ends capture and propagates cancellation`() = runBlocking {
        val awaiting = CompletableDeferred<Unit>()
        var held = false
        var ends = 0
        val press = launch {
            runPushToTalkHold(
                onStart = { held = true },
                onEnd = { held = false; ends++ },
            ) {
                awaiting.complete(Unit)
                awaitCancellation()
            }
        }
        awaiting.await()
        assertTrue(held)
        press.cancelAndJoin()
        assertFalse(held)
        assertEquals(1, ends)
        assertTrue(press.isCancelled)
    }

    @Test fun `await failure ends capture once and preserves the failure`() {
        var ends = 0
        val failure = IllegalStateException("gesture failed")
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                runPushToTalkHold(onStart = {}, onEnd = { ends++ }) { throw failure }
            }
        }
        assertTrue(thrown === failure)
        assertEquals(1, ends)
    }

    @Test fun `a start failure never waits or ends an unstarted capture`() {
        var awaits = 0
        var ends = 0
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                runPushToTalkHold(
                    onStart = { throw IllegalArgumentException("start failed") },
                    onEnd = { ends++ },
                    awaitRelease = { awaits++ },
                )
            }
        }
        assertEquals(0, awaits)
        assertEquals(0, ends)
    }
}

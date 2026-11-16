package com.voxbridge.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeTeardownTest {
    @Test fun closeWaitsUntilCaptureHasActuallyCompleted() = runBlocking {
        val capture = CompletableDeferred<Unit>()
        var closed = false
        val teardown = launch(start = CoroutineStart.UNDISPATCHED) {
            closeAfterCapture(capture) { closed = true }
        }
        assertFalse(closed)
        capture.complete(Unit)
        teardown.join()
        assertTrue(closed)
    }

    @Test fun noCaptureClosesImmediately() = runBlocking {
        var closed = false
        closeAfterCapture(null) { closed = true }
        assertTrue(closed)
    }
}

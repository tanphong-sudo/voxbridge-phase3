package com.voxbridge.ui

import kotlinx.coroutines.Job

/** Cancellation does not interrupt blocking microphone/native calls: wait for their finally. */
internal suspend fun closeAfterCapture(capture: Job?, close: () -> Unit) {
    capture?.join()
    close()
}

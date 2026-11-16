package com.voxbridge.ui

/** Release a successfully started hold even when gesture disposal cancels its coroutine. */
internal suspend fun runPushToTalkHold(
    onStart: () -> Unit,
    onEnd: () -> Unit,
    awaitRelease: suspend () -> Unit,
) {
    onStart()
    try {
        awaitRelease()
    } finally {
        onEnd()
    }
}

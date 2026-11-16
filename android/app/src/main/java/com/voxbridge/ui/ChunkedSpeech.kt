package com.voxbridge.ui

import com.voxbridge.core.audio.PcmAudio
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Starts playback of PCM and signals once the first bytes reach the speaker. */
internal typealias StartPlayback = (pcm: PcmAudio, onStarted: () -> Unit) -> Deferred<Unit>

/**
 * Plays sentence chunks in order while synthesizing the NEXT chunk during the current one's
 * playback, so the listener hears sentence one after only one sentence of synthesis instead of
 * the whole translation. Completes only after the last chunk has drained (Speaking-until-done
 * semantics are preserved). [onFirstAudio] fires at most once, for the first chunk only.
 *
 * Failure policy: an empty or failed chunk stops the sequence with an exception; later chunks are
 * never played out of order or skipped silently.
 */
internal suspend fun playChunked(
    producers: List<suspend () -> PcmAudio>,
    startPlayback: StartPlayback,
    onFirstAudio: () -> Unit,
) {
    if (producers.isEmpty()) return
    coroutineScope {
        var next = async { producers[0]() }
        for (index in producers.indices) {
            val pcm = next.await()
            check(pcm.bytes.isNotEmpty()) { "Empty speech audio for chunk $index" }
            if (index + 1 < producers.size) {
                val producer = producers[index + 1]
                next = async { producer() }
            }
            val started: () -> Unit = if (index == 0) onFirstAudio else NO_OP
            startPlayback(pcm, started).await()
        }
    }
}

private val NO_OP: () -> Unit = {}

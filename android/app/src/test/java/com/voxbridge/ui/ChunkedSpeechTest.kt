package com.voxbridge.ui

import com.voxbridge.core.audio.PcmAudio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChunkedSpeechTest {
    private fun pcm(tag: Int) = PcmAudio(byteArrayOf(tag.toByte(), 0), 16_000)

    @Test
    fun nextChunkIsSynthesizedBeforeCurrentChunkFinishesPlaying() = runBlocking {
        withTimeout(2000) {
            val firstDrain = CompletableDeferred<Unit>()
            val secondSynthesized = CompletableDeferred<Unit>()
            val played = mutableListOf<Int>()
            val job = async {
                playChunked(
                    listOf(suspend { pcm(1) }, suspend { secondSynthesized.complete(Unit); pcm(2) }),
                    startPlayback = { audio, started ->
                        played += audio.bytes[0].toInt()
                        started()
                        if (played.size == 1) firstDrain else CompletableDeferred(Unit)
                    },
                    onFirstAudio = {},
                )
            }
            secondSynthesized.await()
            assertEquals(listOf(1), played)
            assertTrue(!job.isCompleted)
            firstDrain.complete(Unit)
            job.await()
            assertEquals(listOf(1, 2), played)
        }
    }

    @Test
    fun firstAudioFiresExactlyOnceForTheFirstChunk() = runBlocking {
        var firstAudio = 0
        playChunked(
            (1..3).map { i -> suspend { pcm(i) } },
            startPlayback = { _, onStarted -> onStarted(); CompletableDeferred(Unit) },
            onFirstAudio = { firstAudio++ },
        )
        assertEquals(1, firstAudio)
    }

    @Test
    fun emptyChunkStopsTheSequenceInsteadOfSkipping() = runBlocking {
        val played = mutableListOf<Int>()
        try {
            playChunked(
                listOf(suspend { pcm(1) }, suspend { PcmAudio(ByteArray(0), 16_000) }, suspend { pcm(3) }),
                startPlayback = { audio, _ -> played += audio.bytes[0].toInt(); CompletableDeferred(Unit) },
                onFirstAudio = {},
            )
            fail("empty chunk must fail")
        } catch (expected: IllegalStateException) {
            assertEquals(listOf(1), played)
        }
    }

    @Test
    fun noProducersMeansNoPlaybackAndNoFirstAudio() = runBlocking {
        var calls = 0
        playChunked(emptyList(), startPlayback = { _, _ -> calls++; CompletableDeferred(Unit) }, onFirstAudio = { calls++ })
        assertEquals(0, calls)
    }
}

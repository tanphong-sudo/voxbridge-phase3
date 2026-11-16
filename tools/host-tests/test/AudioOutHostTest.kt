package com.voxbridge.ui

import android.media.AudioTrack
import com.voxbridge.core.audio.PcmAudio
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.util.concurrent.TimeUnit

class AudioOutHostTest {
    @After fun stop() { AudioOut.stop() }
    private val pcm=PcmAudio(ByteArray(640),16000)

    @Test fun completionWaitsForDrainAndRelease() = runBlocking {
        val completion=AudioOut.play(pcm)
        val track=AudioTrack.tracks.last()
        assertTrue(track.started.await(2,TimeUnit.SECONDS))
        assertFalse(completion.isCompleted)
        assertEquals(0,track.releaseCount)
        track.playbackHeadPosition=320
        withTimeout(2000) { completion.await() }
        assertEquals(1,track.releaseCount)
    }

    @Test fun stopCancelsCompletionAndReplacementCompletesIndependently() = runBlocking {
        val previous=AudioOut.play(pcm)
        val oldTrack=AudioTrack.tracks.last()
        assertTrue(oldTrack.started.await(2,TimeUnit.SECONDS))
        val next=AudioOut.play(pcm)
        val nextTrack=AudioTrack.tracks.last()
        assertTrue(previous.isCancelled)
        assertTrue(nextTrack.started.await(2,TimeUnit.SECONDS))
        assertFalse(next.isCompleted)
        nextTrack.playbackHeadPosition=320
        withTimeout(2000) { next.await() }
        assertEquals(1,oldTrack.releaseCount)
        assertEquals(1,nextTrack.releaseCount)
    }

    @Test fun writeFailureIsReportedInsteadOfSuccessfulCompletion() = runBlocking {
        AudioTrack.nextWriteResult=-6
        val completion=AudioOut.play(pcm)
        val track=AudioTrack.tracks.last()
        val error=runCatching { withTimeout(2000) { completion.await() } }.exceptionOrNull()
        assertTrue(error.toString(),error is IllegalStateException)
        assertEquals(1,track.releaseCount)
    }

    @Test fun teardownFailureReleasesTrackAndCompletesExceptionally() = runBlocking {
        AudioTrack.nextStopFailure=IllegalArgumentException("fixture stop failure")
        val completion=AudioOut.play(pcm)
        val track=AudioTrack.tracks.last()
        assertTrue(track.started.await(2,TimeUnit.SECONDS))
        track.playbackHeadPosition=320
        val error=runCatching { withTimeout(2000) { completion.await() } }.exceptionOrNull()
        assertTrue(error.toString(),error is IllegalArgumentException)
        assertEquals(1,track.releaseCount)
    }

    @Test fun emptyPlaybackCompletesWithoutAllocatingTrack() = runBlocking {
        val before=AudioTrack.tracks.size
        withTimeout(2000) { AudioOut.play(PcmAudio(ByteArray(0),16000)).await() }
        assertEquals(before,AudioTrack.tracks.size)
    }
}

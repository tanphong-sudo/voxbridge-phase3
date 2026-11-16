package com.voxbridge.core

import com.voxbridge.core.audio.*
import kotlin.test.*

class StreamingAudioTest {
    @Test fun streamingVadMatchesBatchAcrossReadBoundaries() {
        val samples = FloatArray(2311) { if (it < 1024) 0f else 1f }
        val expected = CaptureAnalysis.analyzeVad(samples, reset = {}, probability = { it.average().toFloat() })
        var resets = 0
        val stream = StreamingVadAnalysis(reset = { resets++ }, probability = { it.average().toFloat() })
        for (start in samples.indices step 137) stream.append(samples, start, minOf(137, samples.size - start))
        assertEquals(expected, stream.summary())
        assertEquals(1, resets)
    }

    @Test fun boundedPrimitiveBufferAndPartialWrites() {
        val buffer = FloatCaptureBuffer(5)
        assertEquals(3, buffer.append(shortArrayOf(0, 16384, -32768), 3))
        assertEquals(2, buffer.append(shortArrayOf(1, 2, 3), 3))
        assertContentEquals(floatArrayOf(0f, .5f, -1f, 1f / 32768, 2f / 32768), buffer.finish())
        var total = 0
        writePcmFully(ByteArray(10), isActive = { true }) { _, _, count -> minOf(2, count).also { total += it } }
        assertEquals(10, total)
        assertFailsWith<IllegalStateException> { writePcmFully(ByteArray(2), { true }) { _, _, _ -> 0 } }
        assertFailsWith<IllegalStateException> { writePcmFully(ByteArray(2), { true }) { _, _, _ -> -6 } }
    }

    @Test fun cancellationDoesNotWriteAndNoTrimSharesReadOnlyAudio() {
        var calls = 0
        writePcmFully(ByteArray(20), { false }) { _, _, count -> calls++; count }
        assertEquals(0, calls)
        val samples = FloatArray(1000)
        assertSame(samples, CaptureAnalysis.trimToSpeech(samples, null, null))
        assertSame(samples, CaptureAnalysis.trimToSpeech(samples, 0, 32))
        assertFailsWith<IllegalArgumentException> { PcmAudio(ByteArray(1), 16000) }
        assertFailsWith<IllegalArgumentException> { PcmAudio(ByteArray(2), 0) }
    }

    @Test fun frameObservationReusesExactlyOneVadPassAndPreservesSamples() {
        val audio = FloatArray(1559) { it / 2000f }
        val original = audio.copyOf()
        val observed = mutableListOf<Pair<FloatArray, Float>>()
        var probabilityCalls = 0
        var resets = 0
        val stream = StreamingVadAnalysis(
            reset = { resets++ },
            probability = { probabilityCalls++; it.average().toFloat() },
            onFrame = { frame, p -> observed += frame.copyOf() to p },
        )
        for (offset in audio.indices step 91) stream.append(audio, offset, minOf(91, audio.size - offset))
        val summary = stream.summary()
        assertEquals(1, resets)
        assertEquals(3, probabilityCalls)
        assertEquals(3, observed.size)
        assertEquals(summary.frames.map { it.probability }, observed.map { it.second })
        observed.forEachIndexed { index, frame ->
            assertContentEquals(original.copyOfRange(index * 512, (index + 1) * 512), frame.first)
        }
        assertContentEquals(original, audio)
        assertEquals(CaptureAnalysis.analyzeVad(audio, reset = {}, probability = { it.average().toFloat() }), summary)
    }
}

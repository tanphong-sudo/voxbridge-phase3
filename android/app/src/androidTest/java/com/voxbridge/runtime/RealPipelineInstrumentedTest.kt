package com.voxbridge.runtime

import ai.onnxruntime.OrtEnvironment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voxbridge.audio.AudioRecordSource
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.pipeline.TranslationDecision
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale

/**
 * Android-runtime inference smoke against the real external `vi-en-phase2` model set.
 *
 * This deliberately avoids the microphone so emulator evidence stays deterministic while still
 * exercising sherpa JNI + Piper, Android ONNX Runtime + Silero VAD + both Whisper variants,
 * static QNN MT, and the real DecisionEngine safety gate. Physical-device
 * acceptance separately covers AudioRecord/QNN/hardware.
 */
@RunWith(AndroidJUnit4::class)
class RealPipelineInstrumentedTest {

    @Test
    fun piperSafetyOutputIsDeterministic() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val paths = AndroidModelPaths.from(context, ModelProfile.VI_EN_QNN_ENVIT5)
        val status = ExternalDevModelRepository(paths).prepare()
        assertTrue(status.humanReadable(), status.ready)

        PiperTts(paths).use { tts ->
            listOf(
                Language.EN to "Hello, I need help.",
                Language.VI to "Tôi cần trợ giúp.",
            ).forEach { (language, text) ->
                val first = tts.synthesize(text, language)
                val second = tts.synthesize(text, language)
                assertTrue("Piper sample rate changed for $language", first.sampleRate == second.sampleRate)
                assertTrue("Piper output is not deterministic for $language", first.bytes.contentEquals(second.bytes))
            }
        }
    }

    @Test
    fun realPiperAsrAndMtWorkBothDirections() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val paths = AndroidModelPaths.from(context, ModelProfile.VI_EN_QNN_ENVIT5)
        val status = ExternalDevModelRepository(paths).prepare()
        assertTrue(status.humanReadable(), status.ready)

        runDirection(
            paths = paths,
            source = Language.EN,
            target = Language.VI,
            spoken = "Hello, I need help.",
            // Android Piper + Whisper may confuse the short greeting ("Hello" -> "below") while
            // preserving the request. Gate on the stable semantic core that MT actually needs.
            transcriptAnchors = listOf("need", "help"),
            translationAnchorGroups = listOf(
                listOf("cần"),
                listOf("giúp", "trợ", "hỗ trợ"),
            ),
        )
        runDirection(
            paths = paths,
            source = Language.VI,
            target = Language.EN,
            spoken = "Tôi cần trợ giúp.",
            // Piper is configured deterministically in production. This short phrase exercises
            // the exact production TTS -> VAD -> PhoWhisper path with a stable semantic contract.
            transcriptAnchors = listOf("cần", "trợ", "giúp"),
            translationAnchorGroups = listOf(
                listOf("need"),
                listOf("help", "support", "assistance"),
            ),
        )
    }

    private fun runDirection(
        paths: ModelPaths,
        source: Language,
        target: Language,
        spoken: String,
        transcriptAnchors: List<String>,
        translationAnchorGroups: List<List<String>>,
    ) {
        val synthesized = PiperTts(paths).use { it.synthesize(spoken, source) }
        assertTrue("Piper returned empty PCM for $source", synthesized.bytes.isNotEmpty())
        assertTrue("Piper returned invalid sample rate ${synthesized.sampleRate}", synthesized.sampleRate > 0)

        val floats = pcm16LeToFloat(synthesized.bytes)
        val pcm16k = AudioRecordSource.resampleLinear(
            floats,
            synthesized.sampleRate,
            AudioRecordSource.TARGET_SAMPLE_RATE,
        )
        assertTrue("resampled audio is empty for $source", pcm16k.isNotEmpty())

        val env = OrtEnvironment.getEnvironment()
        var speechFrames = 0
        var maxVadProbability = 0f
        SileroVad.load(env, paths.vad).use { vad ->
            vad.reset()
            var offset = 0
            while (offset + 512 <= pcm16k.size) {
                val probability = vad.chunkProbability(pcm16k.copyOfRange(offset, offset + 512))
                if (probability > maxVadProbability) maxVadProbability = probability
                if (probability >= AudioRecordSource.SPEECH_THRESHOLD) speechFrames++
                offset += 512
            }
        }
        val validSpeechMs = speechFrames.toLong() * 32L
        assertTrue(
            "Silero VAD did not detect Piper speech for $source (max=$maxVadProbability, voiced=${validSpeechMs}ms)",
            speechFrames > 0 && validSpeechMs >= 250,
        )

        val detailed = LanguageRoutedAsr(env, paths).use {
            it.transcribeDetailed(pcm16k, source, target)
        }
        val transcript = detailed.result.text
        assertTrue("ASR returned blank for $source from Piper '$spoken'", transcript.isNotBlank())
        val normalizedTranscript = semanticNormalize(transcript)
        assertTrue(
            "ASR semantic anchors missing for $source: '$transcript'",
            transcriptAnchors.all { normalizedTranscript.contains(semanticNormalize(it)) },
        )

        val decision = StaticMtTranslator(env, java.io.File(paths.root, "translation/qnn"), ApplicationProvider.getApplicationContext<android.content.Context>().applicationInfo.nativeLibraryDir, paths.profile.mtBackend).use { translator ->
            translator.prepare()
            val audio = AudioEvidence(
                speechPresent = true,
                audioScore = 0.95,
                durationMs = pcm16k.size.toLong() * 1000 / AudioRecordSource.TARGET_SAMPLE_RATE,
                validSpeechDurationMs = validSpeechMs,
                clippingDetected = false,
                silenceRatio = 1.0 - validSpeechMs.toDouble() /
                    (pcm16k.size.toDouble() * 1000.0 / AudioRecordSource.TARGET_SAMPLE_RATE),
            )
            DecisionEngine(translator = translator).decide(audio, detailed.result)
        }
        val translation = when (decision) {
            is TranslationDecision.ModelTranslation -> decision.text
            is TranslationDecision.VerifiedTranslation -> decision.text
            is TranslationDecision.RepairedTranslation -> decision.text
            is TranslationDecision.MemoryTranslation -> decision.text
            is TranslationDecision.AskRepeat -> error("DecisionEngine rejected $source->$target: ${decision.reason}")
            TranslationDecision.NoSpeech -> error("DecisionEngine returned NoSpeech for detected $source speech")
        }
        assertTrue("MT returned blank for $source->$target", translation.isNotBlank())
        val normalizedTranslation = semanticNormalize(translation)
        assertTrue(
            "MT semantic anchors missing for $source->$target: '$translation'",
            translationAnchorGroups.all { group ->
                group.any { normalizedTranslation.contains(semanticNormalize(it)) }
            },
        )
    }

    private fun semanticNormalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun pcm16LeToFloat(bytes: ByteArray): FloatArray {
        require(bytes.size % 2 == 0) { "PCM16 byte count must be even" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 2) { buffer.short / 32768.0f }
    }
}

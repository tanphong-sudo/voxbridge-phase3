package com.voxbridge.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class ModelProfileTest {
    @Test fun qnnProfilesUseConfirmedAsrModelsAndDistinctTranslationCandidates() {
        for (id in listOf("vi-en-qnn-envit5")) {
            val profile = ModelProfile.fromId(id)
            assertEquals("phowhisper-small", profile.asrModels.getValue("vi"))
            assertEquals("parakeet-v2", profile.asrModels.getValue("en"))
            assertEquals(true, profile.qnnAsr)
            val missing = ModelPaths(File("absent-test-models"), profile).missing()
            assertEquals(true, missing.any { it.contains("parakeet-v2-onnx/tokens.json") })
            assertEquals(false, missing.any { it.contains("parakeet-v2-onnx/whisper_tokens") })
        }
        assertEquals("envit5", ModelProfile.VI_EN_QNN_ENVIT5.mtBackend)
        assertEquals(true, runCatching { ModelProfile.fromId("vi-en-qnn-vinai") }.isFailure)
    }

    @Test fun correctedPhoSmallProfileSelectsSmallForVietnameseAndKeepsEnglish() {
        val profile=ModelProfile.fromId("vi-en-phosmall")
        assertEquals("phowhisper-small",profile.asrModels.getValue("vi"))
        assertEquals("whisper-small",profile.asrModels.getValue("en"))
        val paths=ModelPaths(File("models"),profile)
        assertEquals("phowhisper-small-onnx",paths.forLanguage("vi").whisperOnnxDir.name)
        assertEquals("phowhisper-small-qnn",paths.forLanguage("vi").whisperQnnDir.name)
    }

    @Test
    fun mlKitProfileIsGone() {
        assertEquals(true, runCatching { ModelProfile.fromId("vi-en-mlkit") }.isFailure)
    }
    @Test
    fun phase2ProductionProfileMatchesFinalProposal() {
        val profile = ModelProfile.fromId("vi-en-phase2")
        assertEquals("phowhisper-base", profile.asrModels.getValue("vi"))
        assertEquals("whisper-small", profile.asrModels.getValue("en"))
    }

    @Test
    fun defaultModelPathsUseFinalPhase2Selection() {
        val paths = ModelPaths(File("models"))
        assertEquals("vi-en-phase2", paths.profile.id)
        assertEquals("phowhisper-base-onnx", paths.forLanguage("vi").whisperOnnxDir.name)
        assertEquals("whisper-small-onnx", paths.forLanguage("en").whisperOnnxDir.name)
    }

    @Test
    fun legacyBaseProfileRemainsAvailableOnlyWhenExplicitlySelected() {
        val profile = ModelProfile.fromId("vi-en-base")
        assertEquals("phowhisper-base", profile.asrModels.getValue("vi"))
        assertEquals("whisper-base", profile.asrModels.getValue("en"))
    }

    @Test
    fun optionalProfilesAreExplicitAndNeverAliasBase() {
        assertSame(ModelProfile.VI_EN_TINY, ModelProfile.fromId("vi-en-tiny"))
        assertSame(ModelProfile.VI_EN_SMALL, ModelProfile.fromId("vi-en-small"))
        assertEquals("whisper-tiny", ModelProfile.VI_EN_TINY.asrModels.getValue("vi"))
        assertEquals("whisper-small", ModelProfile.VI_EN_SMALL.asrModels.getValue("en"))
    }
}

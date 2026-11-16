package com.voxbridge.runtime

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ModelInstallSafetyTest {
    private fun temporary(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("model-install-test").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    @Test fun failedStagingPreservesOldModel() = temporary { dir ->
        val root = File(dir, "models").apply { mkdirs() }
        File(root, "old").writeText("working")
        assertThrows(IllegalStateException::class.java) {
            ModelInstallTransaction.replace(root, { stage ->
                File(stage, "new").writeText("partial"); error("disk full")
            }, {})
        }
        assertEquals("working", File(root, "old").readText())
    }
    @Test fun invalidStagingNeverReplacesOldModel() = temporary { dir ->
        val root = File(dir, "models").apply { mkdirs() }
        File(root, "old").writeText("working")
        assertThrows(IllegalStateException::class.java) {
            ModelInstallTransaction.replace(root, { File(it, "new").writeText("bad") }, { error("invalid") })
        }
        assertTrue(File(root, "old").isFile)
    }
    @Test fun interruptedRenameRestoresBackup() = temporary { dir ->
        val root = File(dir, "models")
        File(dir, "models.backup").apply { mkdirs(); File(this, "old").writeText("working") }
        ModelInstallTransaction.recover(root)
        assertEquals("working", File(root, "old").readText())
    }
    @Test fun validStagingReplacesOldModel() = temporary { dir ->
        val root = File(dir, "models").apply { mkdirs() }
        File(root, "old").writeText("old")
        ModelInstallTransaction.replace(root, { File(it, "new").writeText("new") }, {
            check(File(it, "new").readText() == "new")
        })
        assertEquals("new", File(root, "new").readText())
        assertFalse(File(root, "old").exists())
    }
    @Test fun integrityRejectsCorruptionAndTraversal() = temporary { root ->
        val data = File(root, "weights.bin").apply { writeText("valid") }
        val hash = MessageDigest.getInstance("SHA-256").digest(data.readBytes()).joinToString("") { "%02x".format(it) }
        File(root, "integrity.sha256").writeText("$hash 5 weights.bin\n")
        assertTrue(ModelIntegrity.validate(root).isEmpty())
        data.writeText("broken")
        assertTrue(ModelIntegrity.validate(root).isNotEmpty())
        File(root, "integrity.sha256").writeText("$hash 5 ../escape\n")
        assertTrue(ModelIntegrity.validate(root).isNotEmpty())
    }
    @Test fun asrSidecarIsRequired() = temporary { root ->
        assertTrue(ModelPaths(root).missing().any { it.endsWith("shared_weights.bin") })
    }
    @Test fun emptyPiperGraphAndTokensAreNotReady() = temporary { root ->
        val paths = ModelPaths(root)
        val voice = ModelPaths.PIPER_VOICES.first()
        val dir = paths.piperVoiceDir(voice).apply { mkdirs() }
        File(dir, "model.onnx").writeBytes(byteArrayOf())
        File(dir, "tokens.txt").writeBytes(byteArrayOf())
        File(dir, "espeak-ng-data").mkdirs()
        assertTrue(paths.piperMissing().any { it.contains("$voice/<voice>.onnx") })
        assertTrue(paths.piperMissing().any { it.contains("$voice/tokens.txt") })
    }
    @Test fun safetyPhraseBankIsRequired() = temporary { root ->
        assertTrue(ModelPaths(root).missing().any { it.contains("cache/ask_repeat_vi.pcm") })
    }
    @Test fun manifestMustCoverRequestedModelFiles() = temporary { root ->
        val data = File(root, "weights.bin").apply { writeText("valid") }
        val hash = MessageDigest.getInstance("SHA-256").digest(data.readBytes()).joinToString("") { "%02x".format(it) }
        File(root, "integrity.sha256").writeText("$hash 5 weights.bin\n")
        assertTrue(ModelIntegrity.validate(root, listOf("missing-sidecar.bin")).isNotEmpty())
        File(root, "unused-old-model.bin").writeText("keep me")
        assertTrue(ModelIntegrity.validate(root, listOf("weights.bin")).isEmpty())
    }
}

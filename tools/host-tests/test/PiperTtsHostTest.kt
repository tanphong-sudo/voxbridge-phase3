package com.voxbridge.runtime

import com.k2fsa.sherpa.onnx.OfflineTts
import com.voxbridge.core.model.Language
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PiperTtsHostTest {
    private fun fixture(block: (PiperTts) -> Unit) {
        val root = Files.createTempDirectory("tts-preload").toFile()
        for (name in listOf("vits-piper-vi_VN-vais1000-medium", "vits-piper-en_US-lessac-medium")) {
            File(root, "text_to_speech/piper/$name/voice.onnx").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        }
        OfflineTts.instances.clear()
        OfflineTts.failGeneration = false
        try { PiperTts(ModelPaths(root)).use(block) }
        finally { root.deleteRecursively(); OfflineTts.failGeneration = false }
    }

    @Test fun preloadingWarmsOnceAndFirstSpeechReusesTheSameVoice() = fixture { tts ->
        tts.preload(Language.VI)
        val voice = OfflineTts.instances.single()
        assertEquals(1, voice.texts.size)
        tts.preload(Language.VI)
        assertEquals(1, voice.texts.size)
        val pcm = tts.synthesize("Điện áp 220 V.", Language.VI)
        assertEquals(1, OfflineTts.instances.size)
        assertTrue(voice.texts.last().contains("hai trăm hai mươi vôn"))
        assertEquals(22050, pcm.sampleRate)
        assertEquals(4, pcm.bytes.size)
    }

    @Test fun switchingDirectionFreesThePreviousVoiceAndWarmsTheNewOne() = fixture { tts ->
        tts.preload(Language.VI)
        val vi = OfflineTts.instances.single()
        tts.preload(Language.EN)
        assertEquals(1, vi.frees)
        val en = OfflineTts.instances.last()
        assertEquals(1, en.texts.size)
        tts.synthesize("Stop machine five.", Language.EN)
        assertEquals(2, OfflineTts.instances.size)
        tts.close()
        tts.close()
        assertEquals(1, en.frees)
        assertThrows(IllegalStateException::class.java) { tts.preload(Language.EN) }
    }

    @Test fun aFailedWarmupIsRetriedInsteadOfMarkedReady() = fixture { tts ->
        OfflineTts.failGeneration = true
        assertThrows(IllegalStateException::class.java) { tts.preload(Language.EN) }
        OfflineTts.failGeneration = false
        tts.preload(Language.EN)
        assertEquals(1, OfflineTts.instances.single().texts.size)
    }
}

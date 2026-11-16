package com.voxbridge.core

import com.voxbridge.core.audio.PcmAudio
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.CachedPhraseIndex
import com.voxbridge.core.pipeline.SpeechAudioPlan
import kotlin.test.*

class CachedPhraseIndexTest {
    @Test fun `only complete declarative commands match in their own language`() {
        assertEquals("stop_machine_vi", CachedPhraseIndex.lookup("  DỪNG   MÁY. ", Language.VI))
        assertEquals("stop_conveyor_en", CachedPhraseIndex.lookup("Stop the conveyor!", Language.EN))
        assertEquals("evacuate_en", CachedPhraseIndex.lookup("Evacuate immediately", Language.EN))
        assertEquals("do_not_enter_vi", CachedPhraseIndex.lookup("Không vào khu vực nguy hiểm.", Language.VI))
        for (text in listOf("Stop the machine?", "Do not stop the machine.", "Stop the machine 5.",
                "Stop the machine. Then leave.", "Stop, the machine.", "Stop-the-machine.",
                "Do not enter the hazard zone unless permitted.")) {
            assertNull(CachedPhraseIndex.lookup(text, Language.EN), text)
        }
        assertEquals("stop_conveyor_vi", CachedPhraseIndex.lookup("Dừng băng chuyền.", Language.VI))
        assertEquals("stop_machine_en", CachedPhraseIndex.lookup("Stop the machine.", Language.EN))
        assertEquals("do_not_enter_en", CachedPhraseIndex.lookup("Do not enter the hazard zone.", Language.EN))
        assertEquals("evacuate_vi", CachedPhraseIndex.lookup("Sơ tán ngay lập tức.", Language.VI))
        assertNull(CachedPhraseIndex.lookup("Dừng máy.", Language.EN))
        assertNull(CachedPhraseIndex.lookup("Stop the machine.", Language.KO))
    }

    @Test fun `cache hit avoids synthesis and cache miss retains all chunks`() {
        val cached = PcmAudio(byteArrayOf(1, 0), 22050)
        var synthesized = 0
        val plan = SpeechAudioPlan.producers("Stop the machine.", Language.EN,
            { id -> if (id == "stop_machine_en") cached else null },
            { _, _ -> synthesized++; error("cache hit must not synthesize") })
        assertSame(cached, plan.single()())
        assertEquals(0, synthesized)
        val spoken = mutableListOf<String>()
        val fallback = SpeechAudioPlan.producers("Stop the machine. Check the valve before leaving.", Language.EN,
            { cached }, { text, _ -> spoken += text; cached })
        fallback.forEach { it() }
        assertEquals(listOf("Stop the machine.", "Check the valve before leaving."), spoken)
        val miss = SpeechAudioPlan.producers("Dừng máy.", Language.VI, { null },
            { text, lang -> assertEquals("Dừng máy.", text); assertEquals(Language.VI, lang); synthesized++; cached })
        miss.single()()
        assertEquals(1, synthesized)
    }
}

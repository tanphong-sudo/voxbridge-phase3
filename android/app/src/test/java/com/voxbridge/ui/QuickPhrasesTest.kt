package com.voxbridge.ui

import com.voxbridge.core.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPhrasesTest {
    @Test fun `every phrase is written in both languages and ids are unique`() {
        assertEquals(QUICK_PHRASES.size, QUICK_PHRASES.map { it.id }.toSet().size)
        QUICK_PHRASES.forEach {
            assertTrue(it.vi.isNotBlank() && it.en.isNotBlank())
            assertNotEquals(it.vi, it.en)
        }
    }

    @Test fun `the button shows the speaker language and plays the listener language`() {
        val stop = checkNotNull(quickPhrase("stop"))
        assertEquals("Dừng lại!", stop.text(Language.VI))
        assertEquals("Stop!", stop.text(Language.EN))
        assertNull(quickPhrase("nope"))
    }

    @Test fun `audio is cached per target language`() {
        val stop = checkNotNull(quickPhrase("stop"))
        assertNotEquals(quickPhraseAudioKey(stop, Language.VI), quickPhraseAudioKey(stop, Language.EN))
    }

    @Test fun `a played quick phrase shows as an accepted result`() {
        assertEquals(TurnMode.RESULT, turnModeOf("SPEAKING", false, QUICK_PHRASE_DECISION, "Stop!"))
    }
}

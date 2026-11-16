package com.voxbridge.core

import com.voxbridge.core.translate.MtOutputCleanup
import kotlin.test.Test
import kotlin.test.assertEquals

class MtOutputCleanupTest {
    @Test fun quotesAroundTheWholeTranslationAreRemoved() {
        assertEquals("Mười hai mét khối. Khi nào xe chở bê tông đến?",
            MtOutputCleanup.clean("\" Mười hai mét khối. Khi nào xe chở bê tông đến? \""))
        assertEquals("Stop here.", MtOutputCleanup.clean("“Stop here.”"))
    }

    @Test fun otherTextIsUnchanged() {
        listOf("He said \"stop\" twice.", "Plain sentence.", "\"Only a leading quote", "").forEach {
            assertEquals(it, MtOutputCleanup.clean(it))
        }
    }
}

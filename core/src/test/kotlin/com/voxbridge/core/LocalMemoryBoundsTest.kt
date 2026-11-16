package com.voxbridge.core

import com.voxbridge.core.learn.*
import com.voxbridge.core.model.Language
import com.voxbridge.core.safety.SafetyFrame
import kotlin.test.*

class LocalMemoryBoundsTest {
    private fun example(key: String, source: Language = Language.VI, target: Language = Language.EN) =
        LearningExample(key, key, sourceLanguage = source, targetLanguage = target,
            feedbackType = FeedbackType.APPROVED, approvalCount = 10)

    @Test fun exactLookupSeparatesDirections() {
        val store = LearningStore { listOf(example("hello"), example("hello", Language.EN, Language.VI)) }
        assertEquals(Language.EN, store.findExact("hello", Language.EN, Language.VI)?.sourceLanguage)
    }

    @Test fun candidatesAreBoundedAndKeepOldExactHit() {
        val exact = example("exact").copy(lastUsedAtMs = 0)
        val store = LearningStore { (1..1000).map { example("other $it").copy(lastUsedAtMs = it.toLong()) } + exact }
        val rows = store.candidates("exact", Language.VI, Language.EN, 32)
        assertEquals(32, rows.size)
        assertEquals(exact, rows.first())
    }

    @Test fun retrieverUsesCandidatePortNotWholeTable() {
        val store = object : LearningStore {
            override fun all(): List<LearningExample> = error("Unbounded scan")
            override fun candidates(key: String, source: Language, target: Language, limit: Int) = listOf(example("hello"))
        }
        val retriever = LocalMemoryRetriever(store)
        assertEquals(1, retriever.retrieve("hello", SafetyFrame(Language.VI), Language.VI, Language.EN, 1).size)
        assertTrue(retriever.retrieve("hello", SafetyFrame(Language.VI), Language.VI, Language.EN, 0).isEmpty())
    }
}

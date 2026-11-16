package com.voxbridge.core.learn

import com.voxbridge.core.model.Language
import com.voxbridge.core.normalize.UtteranceKey
import com.voxbridge.core.safety.SafetyFrame

/**
 * Read access to promoted local learning examples for one worksite/device.
 * Backed on Android by SQLite (Room); fakeable in laptop tests.
 */
fun interface LearningStore {
    fun all(): List<LearningExample>

    fun findExact(key: String, source: Language, target: Language): LearningExample? =
        all().firstOrNull { it.normalizedSource == key && it.sourceLanguage == source && it.targetLanguage == target }

    /** Bounded retrieval port. Persistent stores override this with indexed SQL. */
    fun candidates(key: String, source: Language, target: Language, limit: Int): List<LearningExample> =
        all().asSequence().filter { it.sourceLanguage == source && it.targetLanguage == target && it.rejectionCount == 0 }
            .sortedWith(compareByDescending<LearningExample> { it.normalizedSource == key }.thenByDescending { it.lastUsedAtMs })
            .take(limit.coerceIn(0, 256)).toList()
}

/**
 * Retrieves the best approved past translation for the current utterance, before the
 * neural model runs. Candidate selection retains an exact hit plus recent approved rows;
 * scoring blends lexical similarity, safety-frame compatibility, and approval weight.
 */
class LocalMemoryRetriever(
    private val store: LearningStore
) {
    fun retrieve(
        rawSource: String,
        sourceFrame: SafetyFrame,
        sourceLanguage: Language,
        targetLanguage: Language,
        topK: Int = 5
    ): List<MemoryMatch> {
        if (topK <= 0) return emptyList()
        val queryTokens = tokenize(rawSource, sourceLanguage)
        if (queryTokens.isEmpty()) return emptyList()

        val best = java.util.PriorityQueue<MemoryMatch>(compareBy { it.score })
        store.candidates(UtteranceKey.normalize(rawSource, sourceLanguage), sourceLanguage, targetLanguage, 256)
            .asSequence()
            .filter { it.sourceLanguage == sourceLanguage && it.targetLanguage == targetLanguage }
            .filter { it.rejectionCount == 0 }
            .map { example ->
                val lexical = jaccard(queryTokens, tokenize(example.normalizedSource, sourceLanguage))
                val frame = frameSimilarity(sourceFrame, example.sourceFrame)
                val approval = (example.approvalCount.toDouble() / (example.approvalCount + 2)).coerceIn(0.0, 1.0)
                val score = 0.55 * lexical + 0.30 * frame + 0.15 * approval
                MemoryMatch(example, score)
            }
            .forEach { match ->
                if (best.size < topK.coerceAtMost(256)) best.add(match)
                else if (match.score > best.peek().score) { best.poll(); best.add(match) }
            }
        return best.sortedByDescending { it.score }
    }

    /**
     * Tokenize and normalize a sentence. BOTH the live query and the stored example go through
     * this same path so they are comparable: the query transcript still carries diacritics, and
     * `normalizedSource` may have been stored either raw or normalized. Delegates to
     * [UtteranceKey] — the one shared normalizer (Round-4 HIGH); the old inline `[^a-z0-9 ]`
     * branch erased every CJK glyph, so a ZH/KO query tokenized to an empty set and retrieve()
     * bailed out before ever consulting the store. Normalization is idempotent, so running it
     * on both sides is always safe and prevents asymmetric-shortcut matching collapse.
     */
    private fun tokenize(text: String, language: Language): Set<String> =
        UtteranceKey.normalize(text, language)
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    private fun frameSimilarity(a: SafetyFrame, b: SafetyFrame?): Double {
        if (b == null) return 0.5 // neutral when example has no frame
        var matches = 0
        var total = 0
        fun cmp(x: Any?, y: Any?) { total++; if (x == y) matches++ }
        cmp(a.action, b.action)
        cmp(a.objectType, b.objectType)
        cmp(a.objectId, b.objectId)
        cmp(a.negation, b.negation)
        cmp(a.hazard, b.hazard)
        return if (total == 0) 0.5 else matches.toDouble() / total
    }

    companion object {
        /** A memory hit at or above this score may be used directly as the output. */
        const val DIRECT_REUSE_THRESHOLD = 0.92

        /** Between this and the direct-reuse threshold, the hit is used as a hint only. */
        const val HINT_THRESHOLD = 0.75
    }
}

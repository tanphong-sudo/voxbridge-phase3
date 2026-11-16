package com.voxbridge.core.normalize

import java.text.Normalizer

/**
 * THE single normalization used everywhere an utterance becomes a comparable key:
 *
 *  - write side:  FeedbackPromoter.normalizeSource  -> LearningExample.normalizedSource
 *  - read side:   LocalMemoryRetriever.tokenize     -> live-query tokens + stored-key tokens
 *  - VM lookup:   PushToTalkViewModel.normalizeForKey -> existing-example lookup
 *
 * WHY IT MUST BE SHARED (Round-4 review HIGH): the earlier split had the VM key keep CJK
 * (`\p{L}`) while the promoter and retriever still stripped it (`[^a-z0-9 ]`), so a stored
 * ZH/KO example normalized to "" and a live ZH/KO query tokenized to an empty set — the
 * local-learning loop silently no-opped for VI/ZH/KO pairs. One shared implementation makes
 * the stored key, the existing-lookup key, and the live query always comparable.
 *
 * Language-aware:
 *  - VI: diacritic-folded (NFD strip + đ->d), like VietnameseNormalizer — transcript text
 *    still carries diacritics and stored keys must match it.
 *  - EN/ZH/KO: lowercase + NFKC fold, then KEEP letters and digits (`\p{L}\p{Nd}`): CJK has
 *    no spaces, so stripping non-ASCII erased every Han/Hangul character (the same class of
 *    bug the SafetyFrameExtractor notes); here each Han/Hangul glyph survives as its own
 *    whitespace-delimited token. NFKC folds full-width CJK punctuation/width variants
 *    (，→,; ３→3) so they compare equal across the write/read paths.
 *
 * Idempotent: normalize(normalize(x)) == normalize(x), so it is always safe to run on both
 * already-normalized and raw text (LocalMemoryRetriever relies on this).
 */
object UtteranceKey {
    private val VI_STRIP = Regex("[^a-z0-9 ]")
    private val GENERAL_STRIP = Regex("[^\\p{L}\\p{Nd} ]")
    private val WHITESPACE = Regex("\\s+")

    fun normalize(text: String, language: com.voxbridge.core.model.Language): String {
        val folded = Normalizer.normalize(text.lowercase().trim(), Normalizer.Form.NFKC)
        val stripped = when (language) {
            com.voxbridge.core.model.Language.VI ->
                Normalizer.normalize(folded, Normalizer.Form.NFD)
                    .replace(Regex("\\p{Mn}+"), "")
                    .replace('đ', 'd')
                    .replace(VI_STRIP, " ")
            else -> folded.replace(GENERAL_STRIP, " ")
        }
        return stripped.replace(WHITESPACE, " ").trim()
    }
}

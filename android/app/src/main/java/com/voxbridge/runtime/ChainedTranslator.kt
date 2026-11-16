package com.voxbridge.runtime

import com.voxbridge.core.model.Language
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.translate.Translator

/**
 * Multi-hop [Translator] for pairs with no direct OPUS-MT graph: pivots through English
 * (VI→EN→ZH, VI→EN→KO, KO→EN→VI — MODEL_MANIFEST §1). Each hop is one [OnnxTranslator] whose
 * fixed direction is exercised verbatim; the chain itself carries no sessions, so it owns
 * NOTHING to close (no-op [close], documented) — the caller (PushToTalkViewModel.Runners)
 * closes every hop exactly once.
 *
 * Pivot semantics:
 *  - every intermediate hop ends at [Language.EN] and every hop after the first starts there;
 *    the FINAL hop must run EN→target; [init] enforces the wiring contract so a mis-wired chain
 *    fails at runner-build time, never mid-utterance;
 *  - [translate] fails fast if asked for a pair the chain was not built for (it would otherwise
 *    return the wrong-language text labelled as the requested target);
 *  - [modelName] joins the per-hop names with " + " so the decision trace names the full route;
 *  - [translationScore] is the MINIMUM of the hop scores (reported only when EVERY hop scored) —
 *    the chain is as strong as its weakest link, and one low-confidence hop must drag the chain
 *    under the verification gate rather than being averaged away;
 *  - [sourceText] of the final result is corrected to the ORIGINAL input (the last hop saw only
 *    the pivot text, so its raw result would name the pivot text as "source").
 */
class ChainedTranslator(private vararg val hops: OnnxTranslator) : Translator, AutoCloseable {

    init {
        require(hops.size >= 2) { "ChainedTranslator needs >= 2 hops (a single hop is an OnnxTranslator)" }
        hops.dropLast(1).forEachIndexed { i, hop ->
            require(hop.targetLanguage == Language.EN) {
                "hop $i (${hop.sourceLanguage}->${hop.targetLanguage}) must end at EN (pivot chain)"
            }
        }
        hops.drop(1).forEachIndexed { i, hop ->
            require(hop.sourceLanguage == Language.EN) {
                "hop ${i + 1} (${hop.sourceLanguage}->${hop.targetLanguage}) must start from EN (pivot chain)"
            }
        }
        val last = hops.last()
        require(last.sourceLanguage == Language.EN && last.targetLanguage != Language.EN) {
            "final hop must run EN->target, got ${last.sourceLanguage}->${last.targetLanguage}"
        }
    }

    override fun translate(text: String, sourceLanguage: Language, targetLanguage: Language): TranslationResult {
        require(sourceLanguage == hops.first().sourceLanguage && targetLanguage == hops.last().targetLanguage) {
            "chain is wired for ${hops.first().sourceLanguage}->${hops.last().targetLanguage} " +
                "but was asked for $sourceLanguage->$targetLanguage"
        }
        val names = ArrayList<String>(hops.size)
        val scores = ArrayList<Double>(hops.size)
        var current = text
        for (hop in hops) {
            val r = hop.translate(current, hop.sourceLanguage, hop.targetLanguage)
            names += r.modelName
            r.translationScore?.let { scores += it }
            current = r.translatedText
        }
        return TranslationResult(
            sourceText = text,
            translatedText = current,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            modelName = names.joinToString(" + "),
            // Only when every hop scored: a null (unknown) hop score must yield a null chain
            // score, never a fabricated one.
            translationScore = if (scores.size == hops.size) scores.minOrNull() else null,
        )
    }

    override fun close() {
        // No-op BY CONTRACT: hops are shared across directions (ChainedTranslator(viEn, enZh)
        // shares viEn with the direct VI->EN entry of the same map) and are owned + closed by
        // the ViewModel's Runners exactly once. The AutoCloseable conformance exists only so a
        // "close everything you hold" sweep can treat chains and single-hop translators
        // uniformly without ever closing a shared hop twice.
    }
}

package com.voxbridge.core

import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.pipeline.TranslationDecision
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.translate.Translator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TranslationCollapsePipelineTest {
    @Test fun fluentButTruncatedDecoderOutputIsNeverSpoken() {
        val mt = Translator { text, s, t ->
            TranslationResult(text, "Vui lòng kiểm tra van.", s, t, "capped", hitTokenLimit = true)
        }
        val decision = DecisionEngine(mt).decide(
            AudioEvidence(speechPresent = true, audioScore = .95, durationMs = 1000),
            TranscriptResult(Language.EN, Language.VI, "Please check the valve and then close it.", .95),
        )
        assertEquals(TranslationDecision.AskRepeat("translation_token_limit"), decision)
    }
    private fun run(source: String, target: String, language: Language=Language.EN): Pair<TranslationDecision,Int> {
        var calls=0
        val mt=Translator { text,s,t -> calls++;TranslationResult(text,target,s,t,"test") }
        val result=DecisionEngine(mt).decide(AudioEvidence(speechPresent=true,audioScore=.95,durationMs=1000),
            TranscriptResult(language,if(language==Language.VI) Language.EN else Language.VI,source,.95))
        return result to calls
    }
    @Test fun repeatedPhraseIsRejectedOnOrdinaryAndSafetyPathsWithoutRetry() {
        for(source in listOf("Hello there.","Stop the machine.")) {
            val (decision,calls)=run(source,"dừng máy ngay dừng máy ngay dừng máy ngay")
            assertEquals(TranslationDecision.AskRepeat("translation_degenerate"),decision)
            assertEquals(1,calls)
        }
    }
    @Test fun punctuationOnlyOutputIsRejected() {
        assertEquals(TranslationDecision.AskRepeat("translation_degenerate"),run("Hello.","...").first)
    }
    @Test fun genuineRepeatedSourceIsAllowed() {
        assertEquals(TranslationDecision.ModelTranslation("Đi ngay đi ngay đi ngay!"),run("Go now go now go now!","Đi ngay đi ngay đi ngay!").first)
    }
    @Test fun missingCueShortTranslationOrSentenceMergeDoesNotActivateRetryOrGate() {
        val (missing,calls)=run("Please repeat the room number. Send the address.","Gửi địa chỉ.")
        assertEquals(TranslationDecision.ModelTranslation("Gửi địa chỉ."),missing)
        assertEquals(1,calls)
        assertEquals(TranslationDecision.ModelTranslation("Chào, cảm ơn."),run("Hello. Thank you.","Chào, cảm ơn.").first)
    }
    @Test fun shortSingleTokenLoopStillBlocksInsideLongerOutput() {
        assertEquals(TranslationDecision.AskRepeat("translation_degenerate"),
            run("Please explain the procedure.","One two three four five six seven eight nine ten stop stop stop stop stop stop then keep going and explain the rest of the procedure.").first)
    }
    @Test fun safetyChecksStillRunForFluentTranslations() {
        val (decision,calls)=run("Do not open valve number 3.","Mở van số 3.")
        assertFalse(decision is TranslationDecision.ModelTranslation)
        assertEquals(1,calls)
    }
}

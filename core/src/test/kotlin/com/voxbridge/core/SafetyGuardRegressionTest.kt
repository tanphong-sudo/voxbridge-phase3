package com.voxbridge.core

import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.learn.*
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.*
import com.voxbridge.core.safety.*
import com.voxbridge.core.translate.*
import kotlin.test.*

class SafetyGuardRegressionTest {
    private val extractor = SafetyFrameExtractor()
    private val audio = AudioEvidence(true, 0.95, 1600)
    private fun frame(text: String, lang: Language) = extractor.extract(text, lang)
    private fun decision(source: String, target: String): TranslationDecision = DecisionEngine(FakeTranslator(mapOf(source to target)))
        .decide(audio, TranscriptResult(sourceLanguage=Language.VI, targetLanguage=Language.EN, text=source, asrScore=0.95))

    @Test fun changingHazardTypeIsNeverVerifiedOrRepaired() {
        val d = decision("Dừng máy vì rò rỉ khí.", "Stop the machine because of fire.")
        assertIs<TranslationDecision.AskRepeat>(d)
    }

    @Test fun addingNegationToAnAffirmativeSafetyCommandIsBlocked() {
        assertIs<TranslationDecision.AskRepeat>(decision("Mở cửa.", "Do not open the door."))
    }

    @Test fun changedQuantityCannotBeHiddenByDecimalSuffix() {
        assertIs<TranslationDecision.AskRepeat>(decision("Áp suất phải dưới 3.5 bar.", "Pressure must be below 4.5 bar."))
    }

    @Test fun equivalentDecimalSeparatorsPreserveMeasurement() {
        val a=frame("Áp suất phải dưới 3,5 bar.",Language.VI)
        val b=frame("Pressure must be below 3.5 bar.",Language.EN)
        assertEquals(a.quantity,b.quantity)
        assertNotNull(a.quantity)
    }

    @Test fun reversingThresholdIsBlockedEvenWhenNumberUnchanged() {
        assertIs<TranslationDecision.AskRepeat>(decision("Áp suất phải dưới 3.5 bar.", "Pressure must be above 3.5 bar."))
    }

    @Test fun changingSecondMeasurementCannotHideBehindFirst() {
        assertIs<TranslationDecision.AskRepeat>(decision("Giữ áp suất 30 bar và nhiệt độ 200 C.", "Keep pressure at 30 bar and temperature at 20 C."))
    }

    @Test fun approvedMemoryCannotBypassCollapseGuard() {
        val source="hom nay troi dep"
        val example=LearningExample(normalizedSource=source, approvedTarget=List(12){"Yes"}.joinToString(" "),
            sourceLanguage=Language.VI,targetLanguage=Language.EN,sourceFrame=frame(source,Language.VI),feedbackType=FeedbackType.APPROVED,approvalCount=3)
        val engine=DecisionEngine(FakeTranslator(mapOf(source to "The weather is nice today.")),LocalMemoryRetriever(LearningStore {listOf(example)}))
        val d=engine.decide(audio,TranscriptResult(sourceLanguage=Language.VI,targetLanguage=Language.EN,text=source,asrScore=0.95))
        assertIs<TranslationDecision.ModelTranslation>(d)
        assertEquals("The weather is nice today.", d.text)
    }

    @Test fun approvedMemoryCannotInventDangerousCommand() {
        val source="hom nay troi dep"
        val example=LearningExample(normalizedSource=source,approvedTarget="Stop the machine.",sourceLanguage=Language.VI,targetLanguage=Language.EN,
            sourceFrame=frame(source,Language.VI),feedbackType=FeedbackType.APPROVED,approvalCount=3)
        val engine=DecisionEngine(FakeTranslator(mapOf(source to "The weather is nice today.")),LocalMemoryRetriever(LearningStore {listOf(example)}))
        val d=engine.decide(audio,TranscriptResult(sourceLanguage=Language.VI,targetLanguage=Language.EN,text=source,asrScore=0.95))
        assertIs<TranslationDecision.ModelTranslation>(d)
    }

    @Test fun newEditedTargetDoesNotInheritOldApprovals() {
        val source="hom nay troi dep"
        val existing=LearningExample(normalizedSource=source,approvedTarget="The weather is nice today.",sourceLanguage=Language.VI,targetLanguage=Language.EN,
            sourceFrame=frame(source,Language.VI),feedbackType=FeedbackType.APPROVED,approvalCount=3)
        val feedback=UserFeedback(sourceText=source,targetText=existing.approvedTarget,approved=true,correctedTargetText="The weather is lovely today.",
            sourceLanguage=Language.VI,targetLanguage=Language.EN,decisionBeforeFeedback="MODEL_TRANSLATION",createdAtMs=1)
        val d=FeedbackPromoter().evaluate(feedback,frame(source,Language.VI),frame(feedback.correctedTargetText!!,Language.EN),existing)
        assertEquals(1,assertIs<FeedbackPromoter.PromotionDecision.HintOnly>(d).example.approvalCount)
    }

    @Test fun staleFramesCannotTeachContradictoryTarget() {
        val source="Dừng máy số ba."
        val feedback=UserFeedback(sourceText=source,targetText="Stop machine number three.",approved=true,correctedTargetText="Start machine number three.",
            sourceLanguage=Language.VI,targetLanguage=Language.EN,decisionBeforeFeedback="VERIFIED_TRANSLATION",createdAtMs=1)
        val d=FeedbackPromoter().evaluate(feedback,frame(source,Language.VI),frame("Stop machine number three.",Language.EN),null)
        assertIs<FeedbackPromoter.PromotionDecision.Blocked>(d)
    }

    @Test fun missingFramesCannotBypassLearningSafetyCheck() {
        val feedback=UserFeedback(sourceText="Dừng máy số ba.",targetText="Start machine number three.",approved=true,
            sourceLanguage=Language.VI,targetLanguage=Language.EN,decisionBeforeFeedback="VERIFIED_TRANSLATION",createdAtMs=1)
        assertIs<FeedbackPromoter.PromotionDecision.Blocked>(FeedbackPromoter().evaluate(feedback,null,null,null))
    }

    @Test fun correctedSourceMustBeReextractedBeforeLearning() {
        val feedback=UserFeedback(sourceText="Dừng máy số ba.",targetText="Stop machine number three.",approved=true,
            correctedSourceText="Dừng máy số năm.",sourceLanguage=Language.VI,targetLanguage=Language.EN,
            decisionBeforeFeedback="VERIFIED_TRANSLATION",createdAtMs=1)
        val old=frame(feedback.sourceText,Language.VI)
        assertIs<FeedbackPromoter.PromotionDecision.Blocked>(FeedbackPromoter().evaluate(feedback,old,frame(feedback.targetText,Language.EN),null))
    }

    @Test fun learnedCollapsedTextIsRejectedAtWriteTime() {
        val feedback=UserFeedback(sourceText="Hôm nay trời đẹp.",targetText=List(12){"Yes"}.joinToString(" "),approved=true,
            sourceLanguage=Language.VI,targetLanguage=Language.EN,decisionBeforeFeedback="MODEL_TRANSLATION",createdAtMs=1)
        assertIs<FeedbackPromoter.PromotionDecision.Blocked>(FeedbackPromoter().evaluate(feedback,null,null,null))
    }

    @Test fun approvingNewTargetWithoutEditFlagDoesNotInheritOldApprovals() {
        val source="hom nay troi dep"
        val existing=LearningExample(normalizedSource=source,approvedTarget="The weather is nice today.",sourceLanguage=Language.VI,targetLanguage=Language.EN,
            feedbackType=FeedbackType.APPROVED,approvalCount=9)
        val feedback=UserFeedback(sourceText=source,targetText="The weather is lovely today.",approved=true,
            sourceLanguage=Language.VI,targetLanguage=Language.EN,decisionBeforeFeedback="MODEL_TRANSLATION",createdAtMs=1)
        assertEquals(1,assertIs<FeedbackPromoter.PromotionDecision.HintOnly>(FeedbackPromoter().evaluate(feedback,null,null,existing)).example.approvalCount)
    }

    @Test fun sameMeasurementListAndLimitsAcrossLanguagesIsPreserved() {
        val a=frame("Giữ áp suất dưới 3,50 bar và nhiệt độ trên 20 C.",Language.VI)
        val b=frame("Keep pressure below 3.5 bar and temperature above 20 C.",Language.EN)
        assertEquals("lt:3.5bar|gt:20c",a.quantity)
        assertTrue(SafetyFrameComparator().compare(a,b).preserved)
    }

    @Test fun validOpenDoorCommandStillPasses() {
        assertIs<TranslationDecision.VerifiedTranslation>(decision("Mở cửa.","Open the door."))
    }

    @Test fun ordinaryNarrativeStillPasses() {
        assertIs<TranslationDecision.ModelTranslation>(decision("Hôm nay trời đẹp.","The weather is nice today."))
    }

    @Test fun ordinaryQdcPairsDoNotBecomeInventedSafetyCommands() {
        for ((source,target) in listOf(
            "Anh cần máy cơ bản hay loại chuyên nghiệp?" to "Do you need a basic machine or a professional one?",
            "Tôi nhập rồi, cửa vẫn chưa mở." to "I'm in, the door's not open.",
            "Địa chỉ đúng là nhà bên cạnh." to "The address is the one next door.",
        )) assertIs<TranslationDecision.ModelTranslation>(decision(source,target))
        val engine=DecisionEngine(FakeTranslator(mapOf("What is your postcode?" to "Mã bưu điện của bạn là gì?")))
        val d=engine.decide(audio,TranscriptResult(sourceLanguage=Language.EN,targetLanguage=Language.VI,text="What is your postcode?",asrScore=0.95))
        assertIs<TranslationDecision.ModelTranslation>(d)
    }

    @Test fun realQdcLongPhraseLoopIsBlocked() {
        val phrase="replace card and card which card you want to replace"
        assertEquals(TranslationDecision.AskRepeat("translation_degenerate"),
            decision("replace card and which card would you like to replace", List(9){phrase}.joinToString(": ")))
    }

    @Test fun notYetOpenCannotBecomeOpenPermission() {
        val source=frame("Chưa mở cửa.",Language.VI)
        val target=frame("Open the door.",Language.EN)
        assertTrue(source.negation==true)
        assertTrue(SafetyViolation.LOST_NEGATION in SafetyFrameComparator().compare(source,target).violations)
    }

    @Test fun standaloneNotMustRemainVisibleToVerifier() {
        assertTrue(frame("The door is not open.",Language.EN).negation==true)
    }

    @Test fun vietnameseSourWordDoesNotCreateNotYetNegation() {
        assertFalse(frame("Canh chua rất ngon.",Language.VI).negation==true)
    }

    @Test fun doNotStopMustNotLoseNegationBecauseStopWordExists() {
        val source=frame("Không dừng máy số ba.",Language.VI)
        assertTrue(source.negation==true)
        val target=frame("Stop machine number three.",Language.EN)
        assertTrue(SafetyViolation.LOST_NEGATION in SafetyFrameComparator().compare(source,target).violations)
    }
}

import com.voxbridge.core.asr.TranscriptResult
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.model.Language
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.pipeline.TranslationDecision
import com.voxbridge.core.safety.SafetyFrameComparator
import com.voxbridge.core.safety.SafetyFrameExtractor
import com.voxbridge.core.safety.SafetyViolation
import com.voxbridge.core.safety.SafetyObject
import com.voxbridge.core.safety.SafetyAction
import com.voxbridge.core.safety.Urgency
import com.voxbridge.core.safety.HazardType
import com.voxbridge.core.learn.FeedbackPromoter
import com.voxbridge.core.learn.FeedbackType
import com.voxbridge.core.learn.LearningExample
import com.voxbridge.core.learn.LearningStore
import com.voxbridge.core.learn.LocalMemoryRetriever
import com.voxbridge.core.learn.UserFeedback
import com.voxbridge.core.license.LicenseCredits
import com.voxbridge.core.translate.TranslationResult
import com.voxbridge.core.translate.Translator

/**
 * Self-verification harness for the VoxBridge design.
 *
 * It drives the real model-first DecisionEngine through the scenarios that encode the
 * design's central claims, and reports PASS/FAIL for each. The neural model is simulated
 * by a lookup table so we can deliberately inject the failure modes (dropped object id,
 * flipped negation, inverted action) that the Safety Preservation Layer must catch.
 */

class TableTranslator(private val table: Map<String, String>) : Translator {
    override fun translate(text: String, src: Language, tgt: Language): TranslationResult =
        TranslationResult(text, table[text.trim()] ?: text, src, tgt, "sim-mt")
}

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) { passed++; println("  PASS  $name") }
    else { failed++; println("  FAIL  $name  $detail") }
}

private fun goodAudio() = AudioEvidence(speechPresent = true, audioScore = 0.95, durationMs = 1600)
private fun vi2en(t: String, asr: Double = 0.92) =
    TranscriptResult(Language.VI, Language.EN, t, asr)
private fun en2vi(t: String, asr: Double = 0.92) =
    TranscriptResult(Language.EN, Language.VI, t, asr)

fun main() {
    println("== VoxBridge design self-verification ==\n")

    println("[Decision state machine]")
    run {
        val e = DecisionEngine(translator = TableTranslator(emptyMap()))
        val d = e.decide(AudioEvidence(false, 0.05, 2000), vi2en(""))
        check("silence -> NO_SPEECH", d is TranslationDecision.NoSpeech, "got $d")
    }
    run {
        val e = DecisionEngine(translator = TableTranslator(emptyMap()))
        val d = e.decide(AudioEvidence(true, 0.35, 1200), vi2en("dung bang chuyen so ba"))
        check("low audio quality -> ASK_REPEAT", d is TranslationDecision.AskRepeat, "got $d")
    }
    run {
        val e = DecisionEngine(translator = TableTranslator(emptyMap()))
        val d = e.decide(goodAudio(), vi2en("dung bang chuyen so ba", asr = 0.40))
        check("low ASR confidence -> ASK_REPEAT", d is TranslationDecision.AskRepeat, "got $d")
    }
    run {
        val e = DecisionEngine(translator = TableTranslator(mapOf("hom nay troi dep" to "The weather is nice today.")))
        val raw = TranscriptResult(
            Language.VI, Language.EN, "hom nay troi dep", 0.40,
            asrScoreCalibrated = false,
        )
        val d = e.decide(goodAudio(), raw)
        check("uncalibrated raw ASR score does not use calibrated 0.60 gate",
            d is TranslationDecision.ModelTranslation, "got $d")
    }
    run {
        val e = DecisionEngine(translator = TableTranslator(mapOf("hom nay troi dep" to "The weather is nice today.")))
        val d = e.decide(goodAudio(), vi2en("hom nay troi dep"))
        check("ordinary sentence -> MODEL_TRANSLATION",
            d is TranslationDecision.ModelTranslation && d.text == "The weather is nice today.", "got $d")
    }

    println("\n[Safety Preservation Layer — verify]")
    run {
        val e = DecisionEngine(translator = TableTranslator(mapOf("dung bang chuyen so ba" to "Stop conveyor belt number three.")))
        val d = e.decide(goodAudio(), vi2en("dung bang chuyen so ba"))
        check("preserved safety slots -> VERIFIED_TRANSLATION", d is TranslationDecision.VerifiedTranslation, "got $d")
    }

    println("\n[Safety Preservation Layer — repair, VI->EN]")
    run {
        // model drops "number three"
        val e = DecisionEngine(translator = TableTranslator(mapOf("dung bang chuyen so ba" to "Stop the conveyor.")))
        val d = e.decide(goodAudio(), vi2en("dung bang chuyen so ba"))
        val ok = d is TranslationDecision.RepairedTranslation
        val txt = (d as? TranslationDecision.RepairedTranslation)?.text?.lowercase() ?: ""
        check("lost object-id -> REPAIRED (restores 'three'/'3')",
            ok && ("three" in txt || "3" in txt) && "conveyor" in txt, "got $d")
    }
    run {
        // model flips prohibition into permission
        val e = DecisionEngine(translator = TableTranslator(mapOf("khong vao khu vuc nguy hiem" to "Enter the hazard zone.")))
        val d = e.decide(goodAudio(), vi2en("khong vao khu vuc nguy hiem"))
        val txt = (d as? TranslationDecision.RepairedTranslation)?.text?.lowercase() ?: ""
        check("lost negation -> REPAIRED (restores 'do not')",
            d is TranslationDecision.RepairedTranslation && ("do not" in txt || "don't" in txt), "got $d")
    }

    println("\n[Safety Preservation Layer — refuse unsafe, no fabrication]")
    run {
        // model inverts the action stop->start; insertion cannot fix this
        val e = DecisionEngine(translator = TableTranslator(mapOf("dung may" to "Start the machine.")))
        val d = e.decide(goodAudio(), vi2en("dung may"))
        check("inverted action (unrepairable) -> ASK_REPEAT", d is TranslationDecision.AskRepeat, "got $d")
    }

    println("\n[SafetyFrame extraction — bilingual, only fires on real slots]")
    run {
        val ex = SafetyFrameExtractor()
        val ordinary = ex.extract("hom nay troi dep", Language.VI)
        check("ordinary VI sentence has no safety signal", !ordinary.hasSafetySignal, "frame=$ordinary")
        val safety = ex.extract("khong vao khu vuc nguy hiem", Language.VI)
        check("VI prohibition: negation + hazard zone detected",
            safety.negation == true && safety.hasSafetySignal, "frame=$safety")
        val en = ex.extract("do not start machine five", Language.EN)
        check("EN: negation + object-id 5 detected",
            en.negation == true && en.objectId == "5", "frame=$en")
    }

    println("\n[Review fixes — alphanumeric id, CHANGED_OBJECT, grammatical repair, demotion]")
    run {
        // Alphanumeric object id ("dock B") must be representable (objectId is String now).
        val ex = SafetyFrameExtractor()
        val f = ex.extract("evacuate loading dock b", Language.EN)
        check("alphanumeric object-id 'B' extracted", f.objectId == "B", "frame=$f")
    }
    run {
        // In-lexicon object substitution (conveyor -> forklift) must be flagged CHANGED_OBJECT.
        val ex = SafetyFrameExtractor(); val cmp = SafetyFrameComparator()
        val src = ex.extract("dung bang chuyen", Language.VI)
        val tgt = ex.extract("Stop the forklift.", Language.EN)
        val v = cmp.compare(src, tgt).violations
        check("object substitution -> CHANGED_OBJECT", SafetyViolation.CHANGED_OBJECT in v, "violations=$v")
    }
    run {
        // Repaired output must be grammatical: id anchored to the object, not tail-dumped.
        val e = DecisionEngine(translator = TableTranslator(mapOf("dung bang chuyen so ba" to "Stop the conveyor.")))
        val d = e.decide(goodAudio(), vi2en("dung bang chuyen so ba"))
        val txt = (d as? TranslationDecision.RepairedTranslation)?.text ?: ""
        check("repaired text is grammatical ('conveyor number three')",
            txt.contains(Regex("conveyor\\s+number\\s+three", RegexOption.IGNORE_CASE)), "got '$txt'")
    }
    run {
        // Reversible trust: a rejection must demote (rejectionCount++ -> not reusable).
        val promoter = FeedbackPromoter()
        val existing = LearningExample(
            normalizedSource = "dung may ep so hai", approvedTarget = "Stop stamping press number two.",
            sourceLanguage = Language.VI, targetLanguage = Language.EN,
            feedbackType = com.voxbridge.core.learn.FeedbackType.APPROVED,
            approvalCount = 2, rejectionCount = 0
        )
        val reject = UserFeedback(
            sourceText = "dung may ep so hai", targetText = "Stop stamping press number two.",
            approved = false, sourceLanguage = Language.VI, targetLanguage = Language.EN,
            decisionBeforeFeedback = "MEMORY_TRANSLATION", createdAtMs = 1L
        )
        val decision = promoter.evaluate(reject, null, null, existing)
        val demoted = decision is FeedbackPromoter.PromotionDecision.Demoted &&
            (decision as FeedbackPromoter.PromotionDecision.Demoted).example.rejectionCount == 1 &&
            !decision.example.lowRiskTrusted
        check("rejection demotes a trusted example (reversible trust)", demoted, "got $decision")
    }

    println("\n[Floor realism — Lockout-Tagout + quantity slots]")
    run {
        // LOTO action-flip (de-energize -> energize) is a CHANGED_ACTION -> ASK_REPEAT, never spoken.
        val e = DecisionEngine(translator = TableTranslator(mapOf("ngat dien may so ba" to "Energize machine number three.")))
        val d = e.decide(goodAudio(), vi2en("ngat dien may so ba"))
        check("LOTO de-energize→energize flip -> ASK_REPEAT", d is TranslationDecision.AskRepeat, "got $d")
    }
    run {
        // A dropped measurement ("30 bar") is a LOST_QUANTITY critical violation -> ASK_REPEAT.
        val e = DecisionEngine(translator = TableTranslator(mapOf("set pressure to 30 bar" to "Đặt áp suất.")))
        val d = e.decide(goodAudio(), en2vi("set pressure to 30 bar"))
        check("dropped quantity '30 bar' -> ASK_REPEAT", d is TranslationDecision.AskRepeat, "got $d")
    }
    run {
        // LOST_ACTION regression (found by running the REAL OPUS-MT, doc 08 §6.5): a critical
        // energy-control verb dropped ENTIRELY with no slot bridging it must be caught, not spoken.
        // "verify dead before work" -> "already dead before working" loses VERIFY_DEAD; the model
        // output carries no recognizable action. Without the LOST_ACTION rule this was VERIFIED and
        // spoken — a destroyed electrical-isolation instruction reaching the worker.
        val e = DecisionEngine(translator = TableTranslator(mapOf("verify dead before work" to "Đã chết trước khi làm việc.")))
        val d = e.decide(goodAudio(), en2vi("verify dead before work"))
        check("LOST_ACTION: critical verb dropped entirely (verify-dead) -> ASK_REPEAT", d is TranslationDecision.AskRepeat, "got $d")
    }
    run {
        // LOST_ACTION false-positive guard (same real-MT run): a GENERIC verb (MOVE) dropped with no
        // guarded slot is an ordinary instruction, NOT a hazard, and must NOT be refused. This is
        // why criticalWhenDroppedAlone gates the rule — generic verbs ("move", "call", "wait") do
        // not carry safety content on their own. A spurious ASK_REPEAT here would be a usability
        // regression that erodes trust in the refusal contract.
        val e = DecisionEngine(translator = TableTranslator(mapOf("di chuyen cac thung hang" to "The boxes.")))
        val d = e.decide(goodAudio(), vi2en("di chuyen cac thung hang"))
        check(
            "LOST_ACTION guard: generic verb (move) dropped, no slot -> MODEL (not refused)",
            d is TranslationDecision.ModelTranslation,
            "got $d"
        )
    }
    run {
        // LOST_ACTION + surviving-object hole (round-2 finding): a CRITICAL energy-control verb
        // dropped entirely must be caught EVEN WHEN the object survives. "de-energize the breaker"
        // -> "The breaker." keeps BREAKER but loses DE_ENERGIZE, leaving a verbless, ambiguous LOTO
        // command (de-energize? energize?) — exactly the lethal confusion the rule exists to prevent.
        // A surviving object must NOT bridge a critical verb (the earlier bridge exemption was a hole).
        val e = DecisionEngine(translator = TableTranslator(mapOf("de-energize the breaker" to "Cầu dao.")))
        val d = e.decide(goodAudio(), en2vi("de-energize the breaker"))
        check("LOST_ACTION: critical verb dropped but object survives -> ASK_REPEAT", d is TranslationDecision.AskRepeat, "got $d")
    }
    run {
        // đừng/dừng fold collision (judging round, MAJOR): the diacritic fold maps đ->d, so "đừng"
        // (don't) folds to "dung" = "dừng" (stop). The folded negation detector excludes bare "dung"
        // to avoid firing on every "stop" — which made it STRUCTURALLY BLIND to "đừng", one of the
        // two most common spoken VI prohibitions. Fixed by detecting "đừng"/"chớ" on the RAW
        // (pre-fold) text. This regression pins that a VI-source "đừng" prohibition is seen as
        // negation, while a plain "dừng" (stop) is NOT falsely flagged as a prohibition.
        val ex = SafetyFrameExtractor()
        val dung = ex.extract("Đừng vào khu vực nguy hiểm.", Language.VI)
        check("đừng (don't) detected as negation on raw VI text", dung.negation == true, "got negation=${dung.negation}")
        val stop = ex.extract("Dừng băng chuyền số ba.", Language.VI)
        check("dừng (stop) NOT falsely flagged as negation", stop.negation != true, "got negation=${stop.negation}")
        val cho = ex.extract("Chớ chạm vào dây điện.", Language.VI)
        check("chớ (don't) detected as negation on raw VI text", cho.negation == true, "got negation=${cho.negation}")
        // ACTION-FOLD class fix (round-2 judge finding): folded "đừng"->"dung" also collided with the
        // STOP lexicon surface, so "Đừng mở van" (don't OPEN) wrongly extracted action=STOP — a
        // phantom that fires a false CHANGED_ACTION refusal on a correct translation. The prohibition
        // auxiliary is now stripped before the lexicon folds, so the REAL verb survives.
        val open = ex.extract("Đừng mở van khí.", Language.VI)
        check("đừng does NOT corrupt the action verb (mở=OPEN, not STOP)", open.action == SafetyAction.OPEN, "got action=${open.action}")
        // CONTENT-WORD fold-collision guard (round-3 judge finding): benign accented words fold onto
        // action surfaces — động "motor"->"dong"=đóng "close", tất "all"->"tat"=tắt "off", cho
        // "give"->"cho"=chờ "wait" — fabricating a phantom action and a false CHANGED_ACTION refusal
        // on ordinary Vietnamese. The decoy guard uses the raw accents (động≠đóng) to suppress these.
        val motor = ex.extract("Động cơ đang chạy.", Language.VI)
        check("động (motor) does NOT fabricate CLOSE action", motor.action != SafetyAction.CLOSE, "got action=${motor.action}")
        val give = ex.extract("Cho tôi xem báo cáo.", Language.VI)
        check("cho (give) does NOT fabricate WAIT action", give.action != SafetyAction.WAIT, "got action=${give.action}")
        // Legit verbs that share the folded surface MUST still be detected (guard is not over-broad).
        val close = ex.extract("Đóng cửa lại.", Language.VI)
        check("đóng (close) still detected as CLOSE", close.action == SafetyAction.CLOSE, "got action=${close.action}")
        val off = ex.extract("Tắt nguồn điện.", Language.VI)
        check("tắt (turn off) still detected as TURN_OFF", off.action == SafetyAction.TURN_OFF, "got action=${off.action}")
        // INVARIANT: on diacritic-STRIPPED input (the stress-benchmark path) the guard is inert, so a
        // stripped legit verb is never suppressed. "dong bang chuyen" must still read STOP.
        val strippedStop = ex.extract("dung bang chuyen so ba", Language.VI)
        check("guard inert on stripped input (dung=STOP preserved)", strippedStop.action == SafetyAction.STOP, "got action=${strippedStop.action}")
        // Highest-frequency members of the "dung"=STOP collision (round-4 judge finding): dùng "use",
        // đúng "correct", đụng "touch" all fold to "dung". Benign accented input must NOT fabricate STOP.
        val use = ex.extract("Tôi dùng máy này.", Language.VI)
        check("dùng (use) does NOT fabricate STOP action", use.action != SafetyAction.STOP, "got action=${use.action}")
        val correct = ex.extract("Đúng rồi.", Language.VI)
        check("đúng (correct) does NOT fabricate STOP action", correct.action != SafetyAction.STOP, "got action=${correct.action}")
        // ALLOWLIST-POLICY regression (round-6 finding): the fold guard is an allowlist, not a denylist,
        // so it suppresses ANY benign homograph of a guarded surface — including ones never enumerated.
        // "sử dụng"/"áp dụng"/"tận dụng"/"dung dịch" (use/apply/utilize/solution) all fold to the STOP
        // surface "dung" and were NOT in the decoy list, yet must NOT fabricate a phantom STOP because
        // none contains the canonical "dừng". This pins that the class is closed by construction.
        for (s in listOf("Tôi sử dụng máy này.", "Hãy áp dụng quy trình.", "Chúng ta tận dụng thời gian.", "Dung dịch này rất tốt.")) {
            val f = ex.extract(s, Language.VI)
            check("allowlist suppresses unenumerated homograph: '$s' -> no phantom STOP", f.action != SafetyAction.STOP, "got action=${f.action}")
        }
        // URGENCY fold collision (round-4, same class one detector deeper): "ngay" (immediately) is
        // the fold of "ngày" (day) — "mỗi ngày" (every day) fabricated phantom urgency=HIGH and a
        // false LOST_URGENCY refusal; "gặp" (meet) folds to the "gap" urgent cue. Guard suppresses
        // both via raw accents while keeping the real urgent "ngay"/"ngay lập tức".
        val everyday = ex.extract("Tôi dùng máy này mỗi ngày.", Language.VI)
        check("mỗi ngày (every day) does NOT fabricate urgency", everyday.urgency != Urgency.HIGH, "got urgency=${everyday.urgency}")
        val urgent = ex.extract("Gọi quản lý ngay.", Language.VI)
        check("ngay (immediately) still detected as urgency", urgent.urgency == Urgency.HIGH, "got urgency=${urgent.urgency}")
        // "ngay" COLLOCATION (round-7 finding): "ngay" is the only toneless safety cue, so it is a true
        // homograph of benign compounds "ngay cả" (even) / "ngay thẳng" (honest) / "ngay ngắn" (tidy)
        // even at the stripped level — no diacritic allowlist can separate them. Handled by collocation
        // (viUrgentNgay strips benign "ngay X" compounds, fires only on a standalone urgent "ngay").
        val even = ex.extract("Ngay cả giám đốc cũng đồng ý.", Language.VI)
        check("ngay cả (even) does NOT fabricate urgency", even.urgency != Urgency.HIGH, "got urgency=${even.urgency}")
        val honest = ex.extract("Anh ấy rất ngay thẳng.", Language.VI)
        check("ngay thẳng (honest) does NOT fabricate urgency", honest.urgency != Urgency.HIGH, "got urgency=${honest.urgency}")
        val immed = ex.extract("Dừng máy ngay lập tức.", Language.VI)
        check("ngay lập tức (immediately) still detected as urgency", immed.urgency == Urgency.HIGH, "got urgency=${immed.urgency}")
        // NEGATION + HAZARD fold collisions (round-5 judge finding — same class on the 2 remaining
        // folded-text detectors): "tranh" cue fires on tranh "painting"/tranh tài "compete" (only
        // tránh "avoid" is a real prohibition); "chay" cue fires on chảy "flow"/chạy "run" (only
        // cháy "fire" is a hazard). The shared decoy guard now covers negation + hazard too.
        val painting = ex.extract("Bức tranh này rất đẹp.", Language.VI)
        check("tranh (painting) does NOT fabricate negation", painting.negation != true, "got negation=${painting.negation}")
        val compete = ex.extract("Hai đội tranh tài.", Language.VI)
        check("tranh tài (compete) does NOT fabricate negation", compete.negation != true, "got negation=${compete.negation}")
        val flow = ex.extract("Nước chảy ra ngoài.", Language.VI)
        check("chảy (flow) does NOT fabricate FIRE hazard", flow.hazard != HazardType.FIRE, "got hazard=${flow.hazard}")
        // Legit prohibition/hazard sharing the folded surface MUST still fire (guard not over-broad).
        val avoid = ex.extract("Tránh xa khu vực này.", Language.VI)
        check("tránh (avoid) still detected as negation", avoid.negation == true, "got negation=${avoid.negation}")
        val fire = ex.extract("Có cháy ở kho.", Language.VI)
        check("cháy (fire) still detected as FIRE hazard", fire.hazard == HazardType.FIRE, "got hazard=${fire.hazard}")
    }
    run {
        // ELECTRICAL_PANEL coverage (round-8 real-MT finding): "Do not touch panel number six" had its
        // panel object dropped by the real model and the layer could not catch it because the object
        // taxonomy lacked an electrical panel. A "do not touch panel" is a live-electrical hazard. The
        // object is now guarded (EN: panel/electrical panel/control cabinet; VI: tủ điện/bảng điện).
        val ex = SafetyFrameExtractor()
        val enPanel = ex.extract("Do not touch panel number six.", Language.EN)
        check("EN electrical panel detected as object", enPanel.objectType == SafetyObject.ELECTRICAL_PANEL, "got object=${enPanel.objectType}")
        val viPanel = ex.extract("Không chạm vào tủ điện số sáu.", Language.VI)
        check("VI tủ điện detected as ELECTRICAL_PANEL object", viPanel.objectType == SafetyObject.ELECTRICAL_PANEL, "got object=${viPanel.objectType}")
        // Dropped panel object across EN->VI must be a critical violation (LOST_OBJECT), not spoken.
        val cmp = SafetyFrameComparator()
        val src = ex.extract("Do not touch panel number six.", Language.EN)
        val tgtDropped = ex.extract("Đừng chạm vào số sáu.", Language.VI)
        check("dropped electrical panel -> critical violation (not silently spoken)", cmp.compare(src, tgtDropped).criticalViolations.isNotEmpty(), "got ${cmp.compare(src, tgtDropped).violations}")
        // Benign-"panel" negative controls (round-8 judge finding): the bare "panel" token was
        // ambiguous (solar panel, panel of experts) and would fabricate ELECTRICAL_PANEL. Now keyed on
        // unambiguous industrial collocations ("electrical/control panel", "panel number N"), so
        // benign uses must NOT fire — closing the false-positive asymmetry the dùng/ngay guards have.
        val solar = ex.extract("The solar panel on the roof is dirty.", Language.EN)
        check("solar panel does NOT fabricate ELECTRICAL_PANEL", solar.objectType != SafetyObject.ELECTRICAL_PANEL, "got object=${solar.objectType}")
        val experts = ex.extract("A panel of experts will review it.", Language.EN)
        check("panel of experts does NOT fabricate ELECTRICAL_PANEL", experts.objectType != SafetyObject.ELECTRICAL_PANEL, "got object=${experts.objectType}")
        val ctrlPanel = ex.extract("Do not touch the control panel.", Language.EN)
        check("control panel still detected as ELECTRICAL_PANEL", ctrlPanel.objectType == SafetyObject.ELECTRICAL_PANEL, "got object=${ctrlPanel.objectType}")
    }

    println("\n[Local Learning Loop — read path: reuse, trust ladder, safety re-check, fault isolation]")
    run {
        val ex = SafetyFrameExtractor()
        // Non-safety sentence, exact-match approved example trusted at low-risk (2 approvals).
        val store = LearningStore {
            listOf(LearningExample(
                normalizedSource = "hom nay troi dep", approvedTarget = "The weather is nice today.",
                sourceLanguage = Language.VI, targetLanguage = Language.EN,
                sourceFrame = ex.extract("hom nay troi dep", Language.VI),
                feedbackType = FeedbackType.APPROVED, approvalCount = 2, rejectionCount = 0
            ))
        }
        val e = DecisionEngine(
            translator = TableTranslator(mapOf("hom nay troi dep" to "MT fallback (should not be used).")),
            memory = LocalMemoryRetriever(store)
        )
        val d = e.decide(goodAudio(), vi2en("hom nay troi dep"))
        check("low-risk trusted memory hit -> MEMORY_TRANSLATION",
            d is TranslationDecision.MemoryTranslation && d.text == "The weather is nice today.", "got $d")
    }
    run {
        val ex = SafetyFrameExtractor()
        // Same example but only 1 approval -> NOT lowRiskTrusted -> must fall through to MT.
        val store = LearningStore {
            listOf(LearningExample(
                normalizedSource = "hom nay troi dep", approvedTarget = "Stale unapproved reuse.",
                sourceLanguage = Language.VI, targetLanguage = Language.EN,
                sourceFrame = ex.extract("hom nay troi dep", Language.VI),
                feedbackType = FeedbackType.APPROVED, approvalCount = 1, rejectionCount = 0
            ))
        }
        val e = DecisionEngine(
            translator = TableTranslator(mapOf("hom nay troi dep" to "The weather is nice today.")),
            memory = LocalMemoryRetriever(store)
        )
        val d = e.decide(goodAudio(), vi2en("hom nay troi dep"))
        check("single-approval memory hit is NOT reused (promotion ladder enforced)",
            d !is TranslationDecision.MemoryTranslation, "got $d")
    }
    run {
        val ex = SafetyFrameExtractor()
        // Safety sentence, 3 approvals (safetyTrusted); replayed target preserves every slot -> reuse.
        val store = LearningStore {
            listOf(LearningExample(
                normalizedSource = "dung bang chuyen so ba", approvedTarget = "Stop conveyor belt number three.",
                sourceLanguage = Language.VI, targetLanguage = Language.EN,
                sourceFrame = ex.extract("dung bang chuyen so ba", Language.VI),
                feedbackType = FeedbackType.APPROVED, approvalCount = 3, rejectionCount = 0
            ))
        }
        val e = DecisionEngine(
            translator = TableTranslator(mapOf("dung bang chuyen so ba" to "Stop conveyor belt number three.")),
            memory = LocalMemoryRetriever(store)
        )
        val d = e.decide(goodAudio(), vi2en("dung bang chuyen so ba"))
        check("safety-trusted memory hit, slots preserved -> MEMORY_TRANSLATION",
            d is TranslationDecision.MemoryTranslation, "got $d")
    }
    run {
        val ex = SafetyFrameExtractor()
        // Safety sentence, safetyTrusted, but the stored target DROPS the object id -> must NOT be
        // spoken from memory; falls through to MT + verify (no free pass on safety).
        val store = LearningStore {
            listOf(LearningExample(
                normalizedSource = "dung bang chuyen so ba", approvedTarget = "Stop the conveyor.",
                sourceLanguage = Language.VI, targetLanguage = Language.EN,
                sourceFrame = ex.extract("dung bang chuyen so ba", Language.VI),
                feedbackType = FeedbackType.APPROVED, approvalCount = 3, rejectionCount = 0
            ))
        }
        val e = DecisionEngine(
            translator = TableTranslator(mapOf("dung bang chuyen so ba" to "Stop conveyor belt number three.")),
            memory = LocalMemoryRetriever(store)
        )
        val d = e.decide(goodAudio(), vi2en("dung bang chuyen so ba"))
        val spoken = (d as? TranslationDecision.MemoryTranslation)?.text
        check("unsafe memory hit (dropped id) is NOT spoken -> falls through to MT",
            d !is TranslationDecision.MemoryTranslation && spoken == null, "got $d")
    }
    run {
        // A store fault must degrade to neural MT, never crash decide().
        val store = LearningStore { throw RuntimeException("db corrupt") }
        val e = DecisionEngine(
            translator = TableTranslator(mapOf("hom nay troi dep" to "The weather is nice today.")),
            memory = LocalMemoryRetriever(store)
        )
        val d = runCatching { e.decide(goodAudio(), vi2en("hom nay troi dep")) }.getOrNull()
        check("memory store fault degrades to MT, decide() does not throw",
            d is TranslationDecision.ModelTranslation && d.text == "The weather is nice today.", "got $d")
    }

    println("\n[Local Learning Loop — write path: slot-conflict block, promotion]")
    run {
        val ex = SafetyFrameExtractor()
        // A correction that drops the source negation must be BLOCKED, never learned.
        val src = ex.extract("khong vao khu vuc nguy hiem", Language.VI)
        val badTarget = ex.extract("Enter the hazard zone.", Language.EN)
        val feedback = UserFeedback(
            sourceText = "khong vao khu vuc nguy hiem", targetText = "Enter the hazard zone.",
            approved = false, correctedTargetText = "Enter the hazard zone.",
            sourceLanguage = Language.VI, targetLanguage = Language.EN,
            decisionBeforeFeedback = "REPAIRED_TRANSLATION", createdAtMs = 1L
        )
        val decision = FeedbackPromoter().evaluate(feedback, src, badTarget, null)
        check("correction that drops a negation -> Blocked(slot_conflict)",
            decision is FeedbackPromoter.PromotionDecision.Blocked, "got $decision")
    }
    run {
        // Second approval promotes a low-risk example into reusable memory.
        val existing = LearningExample(
            normalizedSource = "hom nay troi dep", approvedTarget = "The weather is nice today.",
            sourceLanguage = Language.VI, targetLanguage = Language.EN,
            feedbackType = FeedbackType.APPROVED, approvalCount = 1, rejectionCount = 0
        )
        val approve = UserFeedback(
            sourceText = "hom nay troi dep", targetText = "The weather is nice today.",
            approved = true, sourceLanguage = Language.VI, targetLanguage = Language.EN,
            decisionBeforeFeedback = "MODEL_TRANSLATION", createdAtMs = 2L
        )
        val decision = FeedbackPromoter().evaluate(approve, null, null, existing)
        val promoted = decision is FeedbackPromoter.PromotionDecision.Promote &&
            decision.example.approvalCount == 2 && decision.example.lowRiskTrusted
        check("second approval -> Promote (approvalCount 2, low-risk trusted)", promoted, "got $decision")
    }

    println("\n[Multilingual — VI↔ZH / VI↔KO on the SAME canonical engine]")
    run {
        val ex = SafetyFrameExtractor()
        // ZH extraction: object-id "三号" -> "3", conveyor object, stop action all surface.
        val f = ex.extract("停止三号传送带", Language.ZH)
        check("ZH frame: objectId '三号'->3 + conveyor + stop",
            f.objectId == "3" && f.objectType == SafetyObject.CONVEYOR_BELT && f.action == SafetyAction.STOP, "frame=$f")
    }
    run {
        val ex = SafetyFrameExtractor()
        // ZH negation "禁止" detected (CJK substring match, no word boundary).
        val f = ex.extract("禁止进入危险区", Language.ZH)
        check("ZH negation '禁止' + hazard zone detected",
            f.negation == true && f.objectType == SafetyObject.HAZARD_ZONE, "frame=$f")
    }
    run {
        val ex = SafetyFrameExtractor()
        // KO object-id "3번" -> "3"; Korean stop "정지".
        val f = ex.extract("3번 컨베이어 정지", Language.KO)
        check("KO frame: objectId '3번'->3 + conveyor + stop",
            f.objectId == "3" && f.objectType == SafetyObject.CONVEYOR_BELT && f.action == SafetyAction.STOP, "frame=$f")
    }
    run {
        // ZH source, VI target: model flips the prohibition -> repair into VI restores negation.
        val e = DecisionEngine(translator = TableTranslator(mapOf("禁止进入危险区" to "Vào khu vực nguy hiểm.")))
        val d = e.decide(goodAudio(), TranscriptResult(Language.ZH, Language.VI, "禁止进入危险区", 0.92))
        val txt = (d as? TranslationDecision.RepairedTranslation)?.text?.lowercase() ?: ""
        check("ZH->VI flipped negation -> REPAIRED restores 'không' (or ASK_REPEAT)",
            d is TranslationDecision.RepairedTranslation && "khong" in txt.let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "") } ||
                d is TranslationDecision.AskRepeat, "got $d")
    }
    run {
        // VI source, ZH target: negation loss detected, but repair into ZH is gated off (no
        // validated renderer) -> ASK_REPEAT, never splice VI/EN fragments into a Chinese sentence.
        val e = DecisionEngine(translator = TableTranslator(mapOf("khong vao khu vuc nguy hiem" to "进入危险区。")))
        val d = e.decide(goodAudio(), TranscriptResult(Language.VI, Language.ZH, "khong vao khu vuc nguy hiem", 0.92))
        check("VI->ZH lost negation -> ASK_REPEAT (repair gated, no fabricated ZH)",
            d is TranslationDecision.AskRepeat, "got $d")
    }

    println("\n[Multilingual regressions — collision & object-id fixes (audit-locked)]")
    run {
        // NumberNormalizer regression: a 4-digit id must NOT be truncated to a wrong 3-digit id
        // that could be spoken. "stop machine 2024" -> objectId null, never "202".
        val ex = SafetyFrameExtractor()
        val f = ex.extract("stop machine 2024", Language.EN)
        check("4-digit number not truncated to a phantom id ('2024' -> not '202')",
            f.objectId != "202" && f.objectId != "2024", "frame=$f")
    }
    run {
        // Large CJK id via explicit marker: "1000号" -> "1000", not "000".
        val ex = SafetyFrameExtractor()
        val f = ex.extract("停止1000号机器", Language.ZH)
        check("ZH marker keeps full id ('1000号' -> '1000', not '000')", f.objectId == "1000", "frame=$f")
    }
    run {
        // CJK substring-collision negatives: phone/division/question must NOT fabricate a slot.
        val ex = SafetyFrameExtractor()
        val phone = ex.extract("打电话给主管", Language.ZH)       // 电话=phone contains 电; must not be POWER
        val division = ex.extract("通知部门主管", Language.ZH)     // 部门=division contains 门; must not be DOOR
        val question = ex.extract("질문 있습니다", Language.KO)     // 질문=question contains 문; must not be DOOR
        check("ZH '电话'(phone) does not fabricate POWER object", phone.objectType != SafetyObject.POWER, "frame=$phone")
        check("ZH '部门'(division) does not fabricate DOOR object", division.objectType != SafetyObject.DOOR, "frame=$division")
        check("KO '질문'(question) does not fabricate DOOR object", question.objectType != SafetyObject.DOOR, "frame=$question")
    }
    run {
        // Duplicate-surface regression: longest-match must classify these correctly.
        val ex = SafetyFrameExtractor()
        val openValve = ex.extract("打开阀门", Language.ZH)        // OPEN the valve, NOT TURN_ON
        val energize = ex.extract("通电", Language.ZH)             // ENERGIZE, NOT TURN_ON
        check("ZH '打开阀门' classifies as OPEN (not TURN_ON)",
            openValve.action == SafetyAction.OPEN && openValve.objectType == SafetyObject.PRESSURE_VALVE, "frame=$openValve")
        check("ZH '通电' classifies as ENERGIZE (not TURN_ON)", energize.action == SafetyAction.ENERGIZE, "frame=$energize")
    }

    println("\n[Model license credits — CC-BY-4.0 attribution obligation]")
    run {
        // Component set must mirror models/MODEL_MANIFEST.md (SSOT): 5 Apache-2.0 standard
        // hops + 1 CC-BY-4.0 tc-big hop + Silero VAD (MIT) = 7 shipped components.
        check("credits cover 6 MT hops + VAD (7 components)",
            LicenseCredits.credits.size == 7, "got ${LicenseCredits.credits.size}")
        val attr = LicenseCredits.attributionRequired
        check("attribution required ONLY for tc-big-en-ko (CC-BY-4.0)",
            attr.size == 1 && attr[0].source == "Helsinki-NLP/opus-mt-tc-big-en-ko" && attr[0].license == "CC-BY-4.0",
            "got $attr")
        val text = LicenseCredits.aboutText()
        check("about text carries CC-BY-4.0 attribution for tc-big-en-ko",
            "CC-BY-4.0" in text && "opus-mt-tc-big-en-ko" in text && "Helsinki-NLP" in text, "text=$text")
        check("no shipped component has a blank license",
            LicenseCredits.credits.all { it.license.isNotBlank() },
            "got ${LicenseCredits.credits.map { it.component to it.license }}")
    }

    println("\n== RESULT: $passed passed, $failed failed ==")
    if (failed > 0) kotlin.system.exitProcess(1)
}

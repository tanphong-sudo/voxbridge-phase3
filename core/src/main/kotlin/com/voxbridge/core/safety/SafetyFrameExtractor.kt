package com.voxbridge.core.safety

import com.voxbridge.core.model.Language
import com.voxbridge.core.normalize.NumberNormalizer
import com.voxbridge.core.normalize.VietnameseNormalizer

/**
 * Extracts a [SafetyFrame] from a sentence in either Vietnamese or English.
 *
 * This is a VERIFIER, not a translator. It uses a bilingual domain lexicon plus dedicated
 * detectors for the slots whose loss is dangerous (negation, object id, hazard, urgency).
 * It deliberately only fires when real industrial slots are present; ordinary sentences
 * produce a low-confidence/empty frame and are left to the neural translation untouched.
 */
class SafetyFrameExtractor(
    private val viNormalizer: VietnameseNormalizer = VietnameseNormalizer(),
    private val numberNormalizer: NumberNormalizer = NumberNormalizer()
) {
    fun extract(text: String, language: Language): SafetyFrame {
        val normalized = normalizeFor(text, language)
        if (normalized.isBlank()) return SafetyFrame(language = language, confidence = 0.0)

        val lexicon = lexiconFor(language)
        val negation = detectNegation(normalized, language, text)
        // VI: "đừng"/"chớ" are prohibition AUXILIARIES, not content verbs. They fold to "dung"/"cho"
        // and would otherwise be mis-read by the lexicon — "đừng" matches the STOP surface "dung"
        // (so "Đừng mở van" = "don't OPEN valve" wrongly extracts action=STOP), causing a false
        // CHANGED_ACTION over-refusal. Negation is captured from the raw text above, so we strip
        // these auxiliaries BEFORE the lexicon detectors see the folded surface.
        //
        // SEPARATELY, the diacritic fold also collapses BENIGN content words onto action surfaces
        // (động "motor" -> "dong" = đóng "close"; tất "all" -> "tat" = tắt "turn off"; cho "give" ->
        // "cho" = chờ "wait"). detectAction uses the RAW accented text to suppress those phantom
        // matches (see VI_ACTION_FOLD_DECOYS). This is NOT a claim that every possible VI fold
        // collision is covered — it is a curated guard over the high-frequency ones that caused
        // measured false over-refusals; the suppression list is grown from real cases like the lexicon.
        val lexText = if (language == Language.VI) normalizeFor(stripViProhibitionAux(text), language) else normalized
        val action = detectAction(lexText, lexicon, language, text)
        val objectType = detectObject(lexText, lexicon, language, text)
        // A bare number ("machine five", "5") is only trusted as an object id when a guarded object
        // is present for it to attach to — otherwise an incidental number ("wait two hours",
        // "đóng cửa sau") would fabricate a phantom machine id and trigger a false repair/refusal.
        // IDs are meaningful to this verifier only when attached to a guarded industrial object.
        // This applies to explicit markers too: ordinary prose such as "tổng số 117" must not
        // become an equipment id merely because it contains the folded marker "so".
        val objectId = numberNormalizer.extractObjectId(
            lexText,
            allowBareNumber = objectType != null,
            allowNumericMarker = objectType != null,
        )
        val urgency = if (detectUrgency(lexText, language, text)) Urgency.HIGH else Urgency.NORMAL
        val hazard = detectHazard(lexText, language, text)
        val quantity = SafetyMeasurements.extract(text) ?: detectQuantity(lexText, text)
        val activationEligible = activationEligible(
            text = lexText,
            rawText = text,
            language = language,
            lexicon = lexicon,
            action = action,
            objectType = objectType,
            objectId = objectId,
            negation = negation,
            urgency = urgency,
            hazard = hazard,
            quantity = quantity,
        )

        val confidence = computeConfidence(action, objectType, objectId, negation, urgency, hazard, quantity)
        return SafetyFrame(
            language = language,
            action = action,
            objectType = objectType,
            objectId = objectId,
            location = null,
            negation = negation,
            urgency = urgency,
            hazard = hazard,
            quantity = quantity,
            confidence = confidence,
            activationEligible = activationEligible,
        )
    }

    /**
     * Keep lexical extraction broad enough for source/target comparison, but only activate the
     * VI/EN safety gate when the source resembles an instruction or a compact alert. This prevents
     * ordinary news/reference prose containing words such as "fire", "manager", or "stop" from
     * being treated as a live shop-floor command. ZH/KO retain legacy conservative activation until
     * equivalent language-specific context rules are validated.
     */
    private fun activationEligible(
        text: String,
        rawText: String,
        language: Language,
        lexicon: Lexicon,
        action: SafetyAction?,
        objectType: SafetyObject?,
        objectId: String?,
        negation: Boolean,
        urgency: Urgency,
        hazard: HazardType?,
        quantity: String?,
    ): Boolean? {
        if (language != Language.VI && language != Language.EN) return null

        val slotSignal = action?.criticalWhenDroppedAlone == true ||
            hazard != null || quantity != null || objectId != null ||
            (action != null && objectType != null) ||
            (objectType != null && (negation || urgency == Urgency.HIGH))
        if (!slotSignal) return false

        // Equipment IDs are already strongly anchored by their parser.
        if (objectId != null) return true
        if (looksLikeCommand(text, rawText, language, lexicon, action)) return true

        val words = text.split(' ').count { it.isNotBlank() }
        // Compact measurements/hazard utterances are treated as terse shop-floor instructions or
        // alerts. Long descriptive prose that happens to contain "15 metres" or "fire" stays on MT.
        if (quantity != null && (words <= 8 || objectType != null ||
                Regex("\\b(?:pressure|temperature|voltage|ap suat|nhiet do|dien ap)\\b").containsMatchIn(text))) return true
        if (hazard != null && (words <= 8 || objectType != null)) return true
        return false
    }

    private fun looksLikeCommand(
        text: String,
        rawText: String,
        language: Language,
        lexicon: Lexicon,
        action: SafetyAction?,
    ): Boolean {
        val trimmed = text.trim()
        val raw = rawText.trim().lowercase()
        val leadingCue = when (language) {
            Language.VI -> raw == "đừng" || raw.startsWith("đừng ") || raw == "chớ" || raw.startsWith("chớ ") ||
                listOf("khong", "cam", "tranh xa", "mien vao", "khong duoc", "dung co").any {
                    trimmed == it || trimmed.startsWith("$it ")
                }
            Language.EN -> listOf(
                "do not", "don t", "dont", "never", "no entry", "keep out", "keep away",
                "keep clear", "stay away", "stay clear", "avoid", "please",
            ).any { trimmed == it || trimmed.startsWith("$it ") }
            else -> false
        }
        if (leadingCue) return true
        if (action == null) return false

        // Spoken commands overwhelmingly put the operative verb near the front. A matching verb
        // buried deep in a long declarative sentence is lexical evidence for comparison, not an
        // instruction to activate the source-side safety gate.
        for (surface in lexicon.actions[action].orEmpty()) {
            val firstWord = surface.substringBefore(' ')
            val match = Regex("\\b${Regex.escape(firstWord)}\\b").find(text) ?: continue
            val prefix = text.substring(0, match.range.first).trim()
            val wordsBefore = if (prefix.isBlank()) 0 else prefix.split(Regex("\\s+")).size
            if (wordsBefore <= 3) return true
        }
        return false
    }

    private fun normalizeFor(text: String, language: Language): String = when (language) {
        Language.VI -> viNormalizer.normalize(text)
        // CJK scripts have no word spacing and must NOT be stripped to ASCII (the old
        // [^a-z0-9 ] rule erased every Han/Hangul char, leaving ZH/KO with no safety signal at
        // all). Keep letters (incl. Han/Hangul), digits, and spaces; lowercase folds any Latin.
        Language.ZH, Language.KO ->
            text.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
        else -> text.lowercase()
            .replace(Regex("['’]s\\b"), "")
            .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Remove the Vietnamese prohibition auxiliaries "đừng" and "chớ" from raw text before the
     * lexicon detectors fold it. These words mean "don't"/"never" and are never content verbs, but
     * after the đ→d / tone fold they collide with content-verb surfaces ("đừng"→"dung" matches the
     * STOP lexicon entry, "chớ"→"cho" matches WAIT/give), which would fabricate a phantom
     * action/object and trigger a false CHANGED_ACTION refusal on a correctly-translated command.
     * Negation itself is captured separately from the raw text (see detectNegation), so stripping
     * these auxiliaries here loses no safety signal — it only removes the fold-collision source.
     */
    private fun stripViProhibitionAux(text: String): String =
        text.replace("đừng", " ", ignoreCase = true)
            .replace("Đừng", " ")
            .replace("chớ", " ", ignoreCase = true)
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun detectNegation(text: String, language: Language, rawText: String = text): Boolean = when (language) {
        Language.VI -> {
            // "đừng" (don't) and "chớ" (don't/never) are diacritic-distinct from "dừng" (stop) and
            // "cho" (give/let) ONLY before the đ→d / diacritic fold. The folded surface of "đừng" is
            // "dung", identical to "dừng" (stop) — so we cannot recover it from `text` (folded)
            // without also firing on every "stop". We therefore detect these two high-frequency
            // prohibitions on the RAW lowercased text, where đ≠d keeps them separable. This closes a
            // real hole: "đừng" is one of the two most common spoken VI prohibitions, and without
            // this the folded negation detector was structurally blind to it.
            val raw = rawText.lowercase()
            // Substring match (not \b word boundary): Java/Kotlin \b is ASCII-\w-based, so a boundary
            // never fires next to a Vietnamese diacritic char like "ừ"/"ớ". The diacritics keep these
            // distinct from benign neighbours anyway — "chớ" ≠ "chợ" (market) ≠ "chở" (carry).
            val rawNegation = raw.contains("đừng") || raw.contains("chớ")
            // A content verb such as dừng cannot cancel a separate prohibition (không dừng).
            val cues = listOf("khong", "chua", "dung lai viec", "cam", "khong duoc", "dung co",
                "tranh xa", "tranh", "mien vao", "khong phan su")
            val cueHit = cues.any { cue ->
                matchesTerm(text, cue) && !isViFoldDecoy(cue, raw, VI_NEGATION_FOLD_GUARD) &&
                    !(cue == "cam" && containsAny(text, listOf("cam on", "cam ta", "cam kich", "cam nhan")))
            }
            rawNegation || cueHit
        }
        // NOTE (ZH/KO): the cue sets below are a careful first pass and MUST be validated by a
        // native EHS reviewer in Phase 3 (see ZH_LEXICON / KO_LEXICON note). They cover the
        // high-frequency industrial prohibitions, not the full language.
        Language.ZH -> containsAny(text, listOf("不要", "请勿", "勿", "禁止", "严禁", "不准", "不可", "切勿", "远离"))
        Language.KO -> containsAny(text, listOf("하지 마", "하지마", "금지", "엄금", "안 돼", "안돼", "마세요", "출입금지", "멀리"))
        else -> Regex("\\b(?:not|do not|don t|dont|never)\\b").containsMatchIn(text) || containsAny(text, listOf(
            "do not", "don t", "dont", "never", "not", "no entry", "no ", "must not", "cannot",
            // Common safety prohibitions that carry no explicit "not": these forbid an action
            // just as strongly, and a model that flips them ("keep out" -> "enter") must be caught.
            "keep out", "keep away", "keep clear", "stay away", "stay clear", "do no", "avoid"
        ))
    }

    // Detection is LONGEST-MATCH, not insertion-order: across every surface of every entry, the
    // longest surface contained in the text wins. This removes any dependence on map order and
    // resolves genuinely ambiguous short forms by their more specific compound — e.g. "打开电源"
    // matches TURN_ON via "开电源" (len 3) over OPEN via "打开" (len 2), while "打开阀门" stays OPEN.
    private fun detectAction(text: String, lex: Lexicon, language: Language, rawText: String = text): SafetyAction? {
        val raw = rawText.lowercase()
        // VI diacritic-fold guard: a folded action surface (e.g. "dong"/"tat"/"cho") can be the strip
        // of a BENIGN content word (động "motor", tất "all", cho "give") rather than the safety verb
        // (đóng "close", tắt "off", chờ "wait"). Use the raw accented text to reject such a phantom:
        // if the raw shows a decoy form and NOT the legit form, the surface does not count. Stripped
        // input (the stress benchmark) contains neither accented form, so it is unaffected.
        val accept: (String) -> Boolean = { surface ->
            language != Language.VI ||
                (!isViActionFoldDecoy(surface, raw) && !isViActionContextDecoy(surface, raw))
        }
        longestMatch(text, lex.actions, accept)?.let { return it }

        // Whisper commonly collapses the verb phrase "shut down" into the single token
        // "shutdown". Treat that ASR spelling as STOP only when it is immediately attached to a
        // guarded industrial object, so the noun in ordinary prose ("the shutdown lasted...") does
        // not become a critical command by itself.
        if (language == Language.EN && Regex(
                "\\bshutdown\\b\\s+(?:the\\s+)?(?:press|machine|equipment|unit|conveyor|forklift)\\b"
            ).containsMatchIn(text)
        ) {
            return SafetyAction.STOP
        }

        return detectSeparablePhrasalVerb(text, language)
    }

    /** Contextual VI collisions whose words are correctly spelled but commonly occur in prose. */
    private fun isViActionContextDecoy(surface: String, rawLower: String): Boolean = when (surface) {
        "ra" -> listOf("ngoài ra", "đưa ra", "diễn ra", "xảy ra", "tạo ra", "gây ra", "ra mắt").any(rawLower::contains)
        "vao" -> listOf("vào năm", "vào tháng", "vào ngày", "vào lúc").any(rawLower::contains)
        "mac" -> rawLower.contains("mặc dù") || rawLower.contains("mặc dầu")
        "mang" -> listOf("mang đến", "mang lại", "mang mầm", "mang tính").any(rawLower::contains)
        else -> false
    }

    /**
     * True when a matched (diacritic-folded) VI action surface is actually a benign content word,
     * judged from the raw accented text. For a guarded surface, if the raw contains the legitimate
     * accented verb it is kept; if it contains only a known decoy it is suppressed; if it contains
     * neither (stripped input) it is kept, preserving the no-diacritics path. The decoy table is a
     * curated guard over the high-frequency collisions that caused measured false over-refusals, not
     * a claim of exhaustive coverage — it grows from real cases exactly like the lexicon.
     */
    private fun isViActionFoldDecoy(foldedSurface: String, rawLower: String): Boolean =
        isViFoldDecoy(foldedSurface, rawLower, VI_ACTION_FOLD_GUARD)

    /**
     * Generic VI diacritic-fold guard, shared by all four folded-text detectors (action, urgency,
     * negation, hazard). Policy is an ALLOWLIST, not a denylist: a folded cue on a guarded surface
     * is suppressed when the raw text carries VI diacritics but does NOT contain the canonical
     * accented safety word (`guard.legit`). This closes the whole homograph class at once — any
     * benign accented word that folds onto the surface (động, dùng, sử dụng, dung dịch, chảy, tranh,
     * …, enumerated or not) is suppressed because it lacks the legit spelling — rather than chasing
     * an open-ended denylist one word at a time.
     *
     * Two invariants keep it safe:
     *  - On diacritic-stripped input (the stress-benchmark path) `hasViDiacritics` is false, so the
     *    guard is inert and a stripped legit cue still fires. The stress benchmark is untouched.
     *  - The legit list is the canonical accented form(s) of the real safety word ("dừng", "cháy",
     *    "tránh", "ngay" …). A genuine safety instruction in accented VI contains that form, so the
     *    cue still fires for it; only accented inputs lacking the legit spelling are suppressed.
     * The `decoy` lists are retained as documentation of the known homographs but no longer gate.
     */
    private fun isViFoldDecoy(foldedSurface: String, rawLower: String, guards: Map<String, FoldGuard>): Boolean {
        val guard = guards[foldedSurface] ?: return false
        if (!hasViDiacritics(rawLower)) return false
        // Allowlist: suppress unless the canonical accented safety word is present in the raw text.
        return guard.legit.none { rawLower.contains(it) }
    }

    private fun hasViDiacritics(s: String): Boolean =
        s.any { it in VI_DIACRITIC_CHARS }

    /**
     * English separable phrasal verbs ("turn the machine off", "turn that off", "shut it down")
     * split the verb and its particle around the object, so the contiguous lexicon surface
     * ("turn off") never matches. A dropped particle here would falsely read as LOST_ACTION, so we
     * recover the verb+particle pair even when separated by a short object/pronoun. English only:
     * VI/ZH/KO are not separable in this way.
     */
    private fun detectSeparablePhrasalVerb(text: String, language: Language): SafetyAction? {
        if (language != Language.EN) return null
        // verb ... particle within a small window. The particle carries the safety sense
        // (off=TURN_OFF, on=TURN_ON, down=STOP), so map on the particle for the known verbs.
        val patterns = listOf(
            Triple("turn", "off", SafetyAction.TURN_OFF),
            Triple("switch", "off", SafetyAction.TURN_OFF),
            Triple("power", "off", SafetyAction.TURN_OFF),
            Triple("turn", "on", SafetyAction.TURN_ON),
            Triple("switch", "on", SafetyAction.TURN_ON),
            Triple("power", "on", SafetyAction.TURN_ON),
            Triple("shut", "down", SafetyAction.STOP),
            Triple("lock", "out", SafetyAction.LOCK_OUT),
            Triple("tag", "out", SafetyAction.TAG_OUT)
        )
        for ((verb, particle, action) in patterns) {
            // up to 3 intervening words (the/that/it/this machine, etc.)
            if (Regex("\\b$verb\\b(?:\\s+\\w+){0,3}\\s+\\b$particle\\b").containsMatchIn(text)) {
                return action
            }
        }
        return null
    }

    private fun detectObject(
        text: String,
        lex: Lexicon,
        language: Language,
        rawText: String = text,
    ): SafetyObject? {
        val raw = rawText.lowercase()
        val accept: (String) -> Boolean = { surface ->
            language != Language.VI ||
                (!isViFoldDecoy(surface, raw, VI_OBJECT_FOLD_GUARD) &&
                    !isViObjectContextDecoy(surface, raw))
        }
        longestMatch(text, lex.objects, accept)?.let { return it }

        // A bare English "press" is ambiguous (news/media, button press, industrial machine), so
        // do not add it to the global MACHINE lexicon. "press number N" is a strong equipment-id
        // construction and is exactly how the real ASR renders the industrial command corpus.
        if (language == Language.EN && Regex(
                "\\bpress\\b\\s+(?:(?:number|no)\\s+)?(?:\\d+|one|two|three|four|five|six|seven|eight|nine|ten)\\b"
            ).containsMatchIn(text)
        ) {
            return SafetyObject.MACHINE
        }

        return null
    }

    /**
     * Vietnamese object words can be perfectly accented yet belong to a common non-equipment
     * compound. Diacritic guards cannot resolve these because both senses use the same spelling.
     * Keep the list narrow and evidence-driven: every entry below comes from ordinary FLEURS prose
     * that previously activated the industrial safety gate.
     */
    private fun isViObjectContextDecoy(surface: String, rawLower: String): Boolean = when (surface) {
        // "cửa hàng" = shop/store, not a physical door instruction target.
        "cua" -> listOf("cửa hàng", "cửa hiệu").any(rawLower::contains)
        // Academic/fiscal uses of "khóa" are not a lock/padlock.
        "khoa" -> listOf(
            "tài khóa", "niên khóa", "khóa học", "khóa đào tạo", "khóa luận",
        ).any(rawLower::contains)
        else -> false
    }

    private fun <T> longestMatch(text: String, forms: Map<T, List<String>>, accept: (String) -> Boolean = { true }): T? {
        var best: T? = null
        var bestLen = 0
        for ((key, surfaces) in forms) {
            for (s in surfaces) {
                if (s.length > bestLen && matchesTerm(text, s) && accept(s)) {
                    best = key
                    bestLen = s.length
                }
            }
        }
        return best
    }

    private fun detectUrgency(text: String, language: Language, rawText: String = text): Boolean = when (language) {
        Language.VI -> {
            val raw = rawText.lowercase()
            // "ngay" (immediately) is the ONE toneless safety cue, so the diacritic allowlist cannot
            // separate it from benign compounds "ngay cả" (even) / "ngay thẳng" (honest) / "ngay ngắn"
            // (tidy) / "ngay khi" (as soon as) — this single surface needs COLLOCATION handling, not
            // a fold guard. viUrgentNgay strips the benign compounds and fires only if a standalone
            // urgent "ngay" (or "ngay lập tức") survives. The "ngày" (day) fold is handled implicitly:
            // raw carries "ngày", never the bare "ngay" substring (à≠a).
            val ngayUrgent = viUrgentNgay(raw)
            val otherCues = listOf("lap tuc", "khan cap", "gap")
            ngayUrgent || otherCues.any { matchesTerm(text, it) && !isViFoldDecoy(it, raw, VI_URGENCY_FOLD_GUARD) }
        }
        Language.ZH -> containsAny(text, listOf("立即", "马上", "立刻", "紧急", "赶紧", "尽快"))
        Language.KO -> containsAny(text, listOf("즉시", "지금", "당장", "긴급", "빨리", "바로"))
        else -> containsAny(text, listOf("immediately", "now", "emergency", "urgent", "right away", "at once"))
    }

    /**
     * Collocation disambiguation for the toneless urgency cue "ngay". The urgent sense ("gọi ... ngay"
     * = "call ... immediately", "ngay lập tức") is a true homograph of benign compounds at the
     * stripped level ("ngay cả" = even, "ngay thẳng" = honest, "ngay ngắn" = tidy, "ngay khi" = as
     * soon as), so no diacritic allowlist can separate them. We remove the known benign "ngay X"
     * compounds and fire only if a standalone "ngay" (the urgent imperative use) survives — or if an
     * explicit "ngay lập tức" is present. Works on both accented and diacritic-stripped raw text.
     */
    private fun viUrgentNgay(raw: String): Boolean {
        if (!raw.contains("ngay")) return false
        if (raw.contains("ngay lập tức") || raw.contains("ngay lap tuc")) return true
        var stripped = raw
        for (compound in listOf(
            "ngay cả", "ngay ca", "ngay thẳng", "ngay thang", "ngay ngắn", "ngay ngan",
            "ngay khi", "ngay lúc", "ngay luc", "ngay từ", "ngay tu", "ngay trong", "ngay thật", "ngay that"
        )) stripped = stripped.replace(compound, " ")
        return Regex("\\bngay\\b").containsMatchIn(stripped)
    }

    private fun detectHazard(text: String, language: Language, rawText: String = text): HazardType? = when (language) {
        Language.VI -> {
            val raw = rawText.lowercase()
            // Fold-collision guards on the single-syllable hazard cues: "chay" fires on chảy (flow) /
            // chạy (run) when only cháy (fire) is a hazard; "bong" on bóng/bông (ball/cotton) when
            // only bỏng (burn) is; "tran" on trần/trận (ceiling/match) when only tràn (overflow) is.
            // Guard suppresses the benign homographs via raw accents (inert on stripped input).
            fun hit(cues: List<String>) = containsAnyGuarded(text, raw, cues, VI_HAZARD_FOLD_GUARD)
            when {
                hit(listOf("chay", "hoa hoan")) -> HazardType.FIRE
                hit(listOf("ro khi", "ro ri khi", "ri gas", "ro gas")) -> HazardType.GAS_LEAK
                hit(listOf("dien giat", "giat dien")) -> HazardType.ELECTRIC_SHOCK
                hit(listOf("dien cao the", "cao the")) -> HazardType.HIGH_VOLTAGE
                hit(listOf("tran", "do tran")) -> HazardType.SPILL
                hit(listOf("ke ket", "ket may", "nghien", "cuon vao")) -> HazardType.CRUSH
                hit(listOf("vat roi", "roi tu tren")) -> HazardType.STRUCK_BY
                hit(listOf("nga cao", "te nga", "roi nga")) -> HazardType.FALL
                hit(listOf("khong gian kin", "khong gian han che")) -> HazardType.CONFINED_SPACE
                hit(listOf("be mat nong", "bong")) -> HazardType.HOT_SURFACE
                hit(listOf("bo phan chuyen dong", "may dang chay")) -> HazardType.MOVING_PARTS
                hit(listOf("nguy hiem")) -> HazardType.GENERIC_DANGER
                else -> null
            }
        }
        // NOTE (ZH/KO): first-pass cue sets — validate with a native EHS reviewer in Phase 3.
        Language.ZH -> when {
            containsAny(text, listOf("火灾", "着火", "起火")) -> HazardType.FIRE
            containsAny(text, listOf("漏气", "气体泄漏", "燃气泄漏")) -> HazardType.GAS_LEAK
            containsAny(text, listOf("高压", "高电压")) -> HazardType.HIGH_VOLTAGE
            containsAny(text, listOf("触电", "电击")) -> HazardType.ELECTRIC_SHOCK
            containsAny(text, listOf("泄漏", "溢出")) -> HazardType.SPILL
            containsAny(text, listOf("挤压", "夹伤", "卷入")) -> HazardType.CRUSH
            containsAny(text, listOf("坠落物", "落物", "高空坠物")) -> HazardType.STRUCK_BY
            containsAny(text, listOf("坠落", "跌落", "高处坠落")) -> HazardType.FALL
            containsAny(text, listOf("密闭空间", "受限空间")) -> HazardType.CONFINED_SPACE
            containsAny(text, listOf("高温表面", "烫伤")) -> HazardType.HOT_SURFACE
            containsAny(text, listOf("运动部件", "转动部件")) -> HazardType.MOVING_PARTS
            containsAny(text, listOf("危险")) -> HazardType.GENERIC_DANGER
            else -> null
        }
        Language.KO -> when {
            containsAny(text, listOf("화재", "불")) -> HazardType.FIRE
            containsAny(text, listOf("가스 누출", "가스누출", "가스 유출")) -> HazardType.GAS_LEAK
            containsAny(text, listOf("고압", "고전압")) -> HazardType.HIGH_VOLTAGE
            containsAny(text, listOf("감전", "전기 충격")) -> HazardType.ELECTRIC_SHOCK
            containsAny(text, listOf("유출", "누출")) -> HazardType.SPILL
            containsAny(text, listOf("끼임", "압착", "말림")) -> HazardType.CRUSH
            containsAny(text, listOf("낙하물", "떨어지는 물체")) -> HazardType.STRUCK_BY
            containsAny(text, listOf("추락", "넘어짐")) -> HazardType.FALL
            containsAny(text, listOf("밀폐 공간", "밀폐공간")) -> HazardType.CONFINED_SPACE
            containsAny(text, listOf("고온 표면", "화상")) -> HazardType.HOT_SURFACE
            containsAny(text, listOf("회전체", "가동부")) -> HazardType.MOVING_PARTS
            containsAny(text, listOf("위험")) -> HazardType.GENERIC_DANGER
            else -> null
        }
        else -> when {
            containsAny(text, listOf("fire")) -> HazardType.FIRE
            containsAny(text, listOf("gas leak", "gas")) -> HazardType.GAS_LEAK
            containsAny(text, listOf("high voltage")) -> HazardType.HIGH_VOLTAGE
            containsAny(text, listOf("electric shock", "electrocution")) -> HazardType.ELECTRIC_SHOCK
            containsAny(text, listOf("spill", "leak")) -> HazardType.SPILL
            containsAny(text, listOf("crush", "pinch point", "entangle")) -> HazardType.CRUSH
            containsAny(text, listOf("struck by", "falling object", "overhead load")) -> HazardType.STRUCK_BY
            containsAny(text, listOf("fall", "fall from height")) -> HazardType.FALL
            containsAny(text, listOf("confined space")) -> HazardType.CONFINED_SPACE
            containsAny(text, listOf("hot surface", "burn")) -> HazardType.HOT_SURFACE
            containsAny(text, listOf("moving parts")) -> HazardType.MOVING_PARTS
            containsAny(text, listOf("danger", "hazard")) -> HazardType.GENERIC_DANGER
            else -> null
        }
    }

    /**
     * Detect a safety-critical measurement and canonicalize it as "<value><unit>" (language
     * independent, e.g. "30bar", "200c"). Losing or changing a quantity ("30 bar" -> "bar",
     * "200 C" -> "20 C") is a dangerous slot loss, so it is guarded like an object id.
     */
    private fun detectQuantity(text: String, rawText: String = text): String? {
        // Multi-char unit names and degrees ("do"/"c") are unambiguous enough to allow a space
        // between the number and the unit ("30 bar", "200 c").
        val spaced = Regex("(?<![\\d.])(\\d{1,4}(?:[.,]\\d+)?)\\s*(bar|psi|kpa|mpa|kg|tan|tons?|rpm|volt|amp|celsius|metre|met|do c|c)\\b")
        // Bare Vietnamese "độ" is too generic (Richter, angle, degree). Only interpret it as
        // Celsius when the utterance explicitly anchors it with "nhiệt độ".
        val viBareDegrees = if (matchesTerm(text, "nhiet do")) {
            Regex("(?<![\\d.])(\\d{1,4}(?:[.,]\\d+)?)\\s*do\\b").find(text)
        } else {
            null
        }
        // Single-letter units (v=volt, a=amp, m=metre) require CONTIGUITY with the number, so a
        // time expression like "8 a.m." (normalized "8 a m") or a stray letter can never fabricate
        // a phantom measurement that forces a needless ASK_REPEAT.
        val contiguous = Regex("(?<![\\d.])(\\d{1,4})(v|a|m)\\b")
        val contiguousMatch = contiguous.find(text)?.takeUnless { m ->
            val token = Regex.escape(m.value)
            Regex("\\d+\\.$token\\b", RegexOption.IGNORE_CASE).containsMatchIn(rawText)
        }
        val spacedMatch = spaced.find(text)
        if (spacedMatch == null && viBareDegrees != null) {
            return "${viBareDegrees.groupValues[1]}c"
        }
        val m = spacedMatch ?: contiguousMatch ?: return null
        val value = m.groupValues[1].replace(',', '.')
        val unit = when (val u = m.groupValues[2]) {
            "do c", "do", "celsius" -> "c"
            "volt" -> "v"
            "amp" -> "a"
            "tons", "ton", "tan" -> "t"
            "metre", "met" -> "m"
            else -> u
        }
        return "$value$unit"
    }

    private fun computeConfidence(
        action: SafetyAction?,
        objectType: SafetyObject?,
        objectId: String?,
        negation: Boolean,
        urgency: Urgency,
        hazard: HazardType?,
        quantity: String?
    ): Double {
        var score = 0.0
        if (action != null) score += 0.35
        if (objectType != null) score += 0.30
        if (objectId != null) score += 0.15
        if (negation) score += 0.10
        if (urgency == Urgency.HIGH) score += 0.05
        if (hazard != null) score += 0.10
        if (quantity != null) score += 0.15
        // Round to 2 decimals so threshold comparisons (e.g. confidence >= 0.80) are not
        // defeated by IEEE-754 drift such as 0.35 + 0.30 + 0.15 == 0.7999999999999999.
        return (kotlin.math.round(score * 100.0) / 100.0).coerceIn(0.0, 1.0)
    }

    /**
     * Script-aware term matching. Latin/Vietnamese terms use word boundaries (`\b`) so "no" does
     * not match inside "now". CJK terms (Han/Hangul) have no word spacing, so `\b` would never
     * fire between two ideographs — those are matched as plain substrings instead. The script of
     * each TERM decides the rule, so a bilingual lexicon entry list can mix both safely.
     */
    private fun matchesTerm(text: String, term: String): Boolean =
        if (isCjk(term)) text.contains(term)
        else Regex("\\b${Regex.escape(term)}\\b").containsMatchIn(text)

    private fun containsAny(text: String, terms: List<String>): Boolean =
        terms.any { term -> matchesTerm(text, term) }

    /** Like [containsAny], but a folded cue is suppressed when [isViFoldDecoy] judges it a benign
     *  homograph from the raw accented text. Inert on diacritic-stripped input. */
    private fun containsAnyGuarded(text: String, rawLower: String, terms: List<String>, guards: Map<String, FoldGuard>): Boolean =
        terms.any { term -> matchesTerm(text, term) && !isViFoldDecoy(term, rawLower, guards) }

    private fun isCjk(s: String): Boolean =
        s.any { it.code in 0x3040..0x9FFF || it.code in 0xAC00..0xD7A3 }

    private fun lexiconFor(language: Language): Lexicon = when (language) {
        Language.VI -> VI_LEXICON
        Language.ZH -> ZH_LEXICON
        Language.KO -> KO_LEXICON
        else -> EN_LEXICON
    }

    private data class Lexicon(
        val actions: Map<SafetyAction, List<String>>,
        val objects: Map<SafetyObject, List<String>>
    )

    /**
     * Disambiguation guard for VI diacritic-fold collisions on action surfaces. Keyed by the
     * FOLDED surface; `legit` lists the accented form(s) that are the real safety verb, `decoy`
     * lists accented benign words that fold to the same surface. Every decoy/legit string carries
     * a Vietnamese accented character that the normalizer's [a-z0-9 ] strip can never produce, so
     * this guard activates ONLY on raw accented text (what Whisper VI actually emits) and is inert
     * on diacritic-stripped input — the stress benchmark path is therefore unaffected.
     */
    private class FoldGuard(val legit: List<String>, val decoy: List<String>)

    private companion object {
        // The Vietnamese tone/vowel diacritic characters that the normalizer's [a-z0-9 ] strip
        // removes. If a raw string contains NONE of these, it is already diacritic-stripped and the
        // fold guard must stay inert (it cannot tell động from đóng without the accents).
        val VI_DIACRITIC_CHARS: Set<Char> =
            ("àáảãạăằắẳẵặâầấẩẫậ" + "èéẻẽẹêềếểễệ" + "ìíỉĩị" + "òóỏõọôồốổỗộơờớởỡợ" +
                "ùúủũụưừứửữự" + "ỳýỷỹỵ" + "đ").toSet()

        // Curated, grows from real false-over-refusal cases (not exhaustive — same discipline as the
        // lexicon). Covers the high-frequency content-word/safety-verb folds: động/đông "motor,crowd"
        // vs đóng "close"; tất "all" vs tắt "off"; mơ/mỏ "dream,mine" vs mở "open".
        val VI_ACTION_FOLD_GUARD: Map<String, FoldGuard> = mapOf(
            // "dung" (STOP surface) collides with the highest-frequency benign words in the language:
            // dùng "use", đúng "correct/right", đụng "touch/bump" — all fold to "dung". Without this
            // entry "Tôi dùng máy này" (I use this machine) fabricates a phantom STOP.
            "dung" to FoldGuard(legit = listOf("dừng"), decoy = listOf("dùng", "đúng", "đụng")),
            "dung lai" to FoldGuard(legit = listOf("dừng lại"), decoy = listOf("dựng lại")),
            "dong" to FoldGuard(legit = listOf("đóng"), decoy = listOf("động", "đông")),
            "tat" to FoldGuard(legit = listOf("tắt"), decoy = listOf("tất")),
            "mo" to FoldGuard(legit = listOf("mở"), decoy = listOf("mơ", "mỏ")),
            // "cho" (WAIT surface, "chờ") collides with cho "give/let". The legit verb "chờ" carries
            // the ờ diacritic and never contains the bare substring "cho", so a bare "cho" decoy
            // suppresses the give-sense at any position (incl. end-of-string) without touching "chờ".
            "cho" to FoldGuard(legit = listOf("chờ"), decoy = listOf("cho")),
            "doi" to FoldGuard(legit = listOf("đợi"), decoy = listOf("đội", "đổi", "đới", "dõi")),
            "bat" to FoldGuard(legit = listOf("bật", "bắt đầu"), decoy = listOf("bất")),
            "goi" to FoldGuard(legit = listOf("gọi"), decoy = listOf("gói")),
            "deo" to FoldGuard(legit = listOf("đeo"), decoy = listOf("đèo")),
            "ngat" to FoldGuard(legit = listOf("ngắt"), decoy = listOf("ngạt")),
            // mang "wear/carry" is toneless, while mạng "network" folds onto the same surface.
            // FLEURS contains many ordinary Internet/network sentences, so keep the safety verb
            // only when the raw accented transcript actually contains bare "mang".
            "mang" to FoldGuard(legit = listOf("mang"), decoy = listOf("mạng"))
        )

        // Object surfaces suffer the same VI diacritic-fold problem as actions. These guards are
        // intentionally inert on fully stripped input, preserving the stress-benchmark path, while
        // real Whisper transcripts with Vietnamese accents retain enough information to reject
        // ordinary homographs such as diễn→dien (electricity), của→cua (door), and văn→van (valve).
        val VI_OBJECT_FOLD_GUARD: Map<String, FoldGuard> = mapOf(
            "bang tai" to FoldGuard(legit = listOf("băng tải"), decoy = listOf("băng tại")),
            "van" to FoldGuard(legit = listOf("van"), decoy = listOf("văn", "vãn")),
            "dien" to FoldGuard(legit = listOf("điện"), decoy = listOf("diễn", "diện", "điên")),
            "nguon" to FoldGuard(legit = listOf("nguồn"), decoy = emptyList()),
            "cua" to FoldGuard(legit = listOf("cửa"), decoy = listOf("của")),
            "may" to FoldGuard(legit = listOf("máy"), decoy = listOf("mày", "mây")),
            "khoa" to FoldGuard(legit = listOf("khóa"), decoy = listOf("khoa")),
            "the khoa" to FoldGuard(legit = listOf("thẻ khóa"), decoy = emptyList()),
            "the canh bao" to FoldGuard(legit = listOf("thẻ cảnh báo"), decoy = emptyList()),
            "quan ly" to FoldGuard(legit = listOf("quản lý"), decoy = emptyList()),
            "giam sat" to FoldGuard(legit = listOf("giám sát"), decoy = emptyList()),
            "to truong" to FoldGuard(legit = listOf("tổ trưởng"), decoy = emptyList()),
            "cau dao" to FoldGuard(legit = listOf("cầu dao"), decoy = emptyList()),
            "tu dien" to FoldGuard(legit = listOf("tủ điện"), decoy = emptyList()),
            "bang dien" to FoldGuard(legit = listOf("bảng điện"), decoy = emptyList()),
            "tu dieu khien" to FoldGuard(legit = listOf("tủ điều khiển"), decoy = emptyList()),
            "bang dieu khien" to FoldGuard(legit = listOf("bảng điều khiển"), decoy = emptyList())
        )

        // Urgency-cue fold collisions: "ngay" (immediately) is the fold of "ngày" (day) — "mỗi ngày"
        // (every day) fabricates phantom urgency; "gap" (urgent) is the fold of "gặp" (meet). Allowlist
        // policy (see isViFoldDecoy): the cue fires only when the raw carries the canonical accented
        // safety spelling. Note "ngày" does NOT contain the substring "ngay" (à≠a), so a bare "ngay"
        // legit entry fires on the urgent sense and suppresses "ngày"; every real urgent form
        // ("ngay", "ngay lập tức", "làm ngay") contains bare "ngay".
        val VI_URGENCY_FOLD_GUARD: Map<String, FoldGuard> = mapOf(
            "ngay" to FoldGuard(legit = listOf("ngay"), decoy = listOf("ngày", "mỗi ngày")),
            "gap" to FoldGuard(legit = listOf("gấp"), decoy = listOf("gặp"))
        )

        // Negation-cue fold collisions: the folded prohibition cue "tranh" matches tránh (avoid, a
        // real prohibition) but ALSO tranh (painting) / tranh tài (compete); "cam" matches cấm
        // (forbid) but also cảm (feel, as in cảm thấy). Suppress the benign homographs via raw
        // accents. ("tranh xa" / "khong*" cues are unambiguous and intentionally unguarded.)
        val VI_NEGATION_FOLD_GUARD: Map<String, FoldGuard> = mapOf(
            "khong" to FoldGuard(legit = listOf("không"), decoy = listOf("khổng")),
            "chua" to FoldGuard(legit = listOf("chưa"), decoy = listOf("chua", "chùa")),
            "tranh" to FoldGuard(legit = listOf("tránh"), decoy = listOf("tranh")),
            "cam" to FoldGuard(legit = listOf("cấm"), decoy = listOf("cảm"))
        )

        // Hazard-cue fold collisions on single-syllable cues: "chay" matches cháy (fire) but also
        // chảy (flow) / chạy (run); "bong" matches bỏng (burn) but also bóng (ball/shadow) / bông
        // (cotton); "tran" matches tràn (overflow) but also trần (ceiling) / trận (match/battle).
        val VI_HAZARD_FOLD_GUARD: Map<String, FoldGuard> = mapOf(
            "chay" to FoldGuard(legit = listOf("cháy"), decoy = listOf("chảy", "chạy")),
            "bong" to FoldGuard(legit = listOf("bỏng"), decoy = listOf("bóng", "bông")),
            "tran" to FoldGuard(legit = listOf("tràn"), decoy = listOf("trần", "trận")),
            "nghien" to FoldGuard(legit = listOf("nghiền"), decoy = listOf("nghiên"))
        )

        // Vietnamese surfaces are diacritic-stripped to match VietnameseNormalizer output.
        val VI_LEXICON = Lexicon(
            actions = mapOf(
                SafetyAction.STOP to listOf("dung", "ngung", "dung lai"),
                SafetyAction.START to listOf("bat", "khoi dong", "chay may"),
                SafetyAction.TURN_OFF to listOf("tat", "ngat"),
                SafetyAction.TURN_ON to listOf("mo dien", "bat dien"),
                SafetyAction.ENTER to listOf("vao", "di vao"),
                SafetyAction.EXIT to listOf("ra", "di ra", "roi khoi"),
                SafetyAction.OPEN to listOf("mo"),
                SafetyAction.CLOSE to listOf("dong"),
                SafetyAction.CALL to listOf("goi", "lien he"),
                SafetyAction.WAIT to listOf("cho", "doi"),
                SafetyAction.EVACUATE to listOf("so tan", "thoat hiem"),
                SafetyAction.WEAR to listOf("mang", "deo", "mac"),
                SafetyAction.MOVE to listOf("di chuyen"),
                // Lockout-Tagout / energy isolation
                SafetyAction.LOCK_OUT to listOf("khoa may", "khoa lai"),
                SafetyAction.TAG_OUT to listOf("treo the", "gan the"),
                SafetyAction.DE_ENERGIZE to listOf("ngat dien", "cat dien", "ngat nguon"),
                SafetyAction.ENERGIZE to listOf("dong dien", "cap dien", "dong nguon"),
                SafetyAction.VERIFY_DEAD to listOf("kiem tra het dien", "xac nhan het dien"),
                SafetyAction.ISOLATE to listOf("co lap", "cach ly")
            ),
            objects = mapOf(
                SafetyObject.CONVEYOR_BELT to listOf("bang chuyen", "bang truyen", "bang tai"),
                SafetyObject.FORKLIFT to listOf("xe nang"),
                SafetyObject.HAZARD_ZONE to listOf("khu vuc nguy hiem", "vung nguy hiem", "khu cam", "vung cam"),
                SafetyObject.PRESSURE_VALVE to listOf("van ap suat", "van"),
                SafetyObject.EMERGENCY_STOP to listOf("nut dung khan cap", "nut khan cap"),
                // Bare "nguồn" overwhelmingly means generic "source/origin" in normal VI prose.
                // Electrical commands remain covered by "nguồn điện" plus action phrases such as
                // ngắt nguồn / đóng nguồn in the action lexicon.
                SafetyObject.POWER to listOf("nguon dien", "dien"),
                SafetyObject.DOOR to listOf("cua"),
                SafetyObject.PROTECTIVE_EQUIPMENT to listOf("do bao ho", "bao ho", "mu bao ho"),
                SafetyObject.SUPERVISOR to listOf("quan ly", "giam sat", "to truong"),
                SafetyObject.LOCK to listOf("khoa"),
                SafetyObject.TAG to listOf("the khoa", "the canh bao"),
                SafetyObject.BREAKER to listOf("cau dao", "aptomat"),
                SafetyObject.ISOLATION_POINT to listOf("diem co lap", "diem cach ly"),
                SafetyObject.ELECTRICAL_PANEL to listOf("tu dien", "bang dien", "tu dieu khien", "bang dieu khien"),
                SafetyObject.MACHINE to listOf("may", "may ep", "dong co", "thiet bi")
            )
        )

        val EN_LEXICON = Lexicon(
            actions = mapOf(
                SafetyAction.STOP to listOf("stop", "halt", "shut down"),
                SafetyAction.START to listOf("start", "run"),
                SafetyAction.TURN_OFF to listOf("turn off", "switch off", "power off"),
                SafetyAction.TURN_ON to listOf("turn on", "switch on", "power on"),
                SafetyAction.ENTER to listOf("enter", "go into", "step into"),
                SafetyAction.EXIT to listOf("exit", "leave", "get out"),
                SafetyAction.OPEN to listOf("open"),
                SafetyAction.CLOSE to listOf("close", "shut"),
                SafetyAction.CALL to listOf("call", "contact"),
                SafetyAction.WAIT to listOf("wait", "hold"),
                SafetyAction.EVACUATE to listOf("evacuate"),
                SafetyAction.WEAR to listOf("wear", "put on"),
                SafetyAction.MOVE to listOf("moving", "move"),
                // Lockout-Tagout / energy isolation
                SafetyAction.LOCK_OUT to listOf("lock out", "lockout"),
                SafetyAction.TAG_OUT to listOf("tag out", "tagout"),
                SafetyAction.DE_ENERGIZE to listOf("de energize", "de-energize", "isolate power"),
                SafetyAction.ENERGIZE to listOf("energize", "re energize", "restore power"),
                SafetyAction.VERIFY_DEAD to listOf("verify dead", "verify zero energy", "test for dead"),
                SafetyAction.ISOLATE to listOf("isolate")
            ),
            objects = mapOf(
                SafetyObject.CONVEYOR_BELT to listOf("conveyor belt", "conveyor", "belt line"),
                SafetyObject.FORKLIFT to listOf("forklift"),
                SafetyObject.HAZARD_ZONE to listOf("hazard zone", "danger area", "danger zone", "restricted area"),
                SafetyObject.PRESSURE_VALVE to listOf("pressure valve", "valve"),
                SafetyObject.EMERGENCY_STOP to listOf("emergency stop", "e stop"),
                SafetyObject.POWER to listOf("power", "electricity"),
                SafetyObject.DOOR to listOf("door"),
                SafetyObject.PROTECTIVE_EQUIPMENT to listOf("protective equipment", "ppe", "safety gear", "hard hat"),
                SafetyObject.SUPERVISOR to listOf("supervisor", "manager"),
                SafetyObject.LOCK to listOf("lock", "padlock"),
                SafetyObject.TAG to listOf("tag", "warning tag"),
                SafetyObject.BREAKER to listOf("breaker", "circuit breaker"),
                SafetyObject.ISOLATION_POINT to listOf("isolation point"),
                SafetyObject.ELECTRICAL_PANEL to listOf("electrical panel", "control panel", "control cabinet", "switchboard", "panel number", "panel no"),
                SafetyObject.MACHINE to listOf("machine", "press machine", "stamping press", "unit", "equipment")
            )
        )

        // ────────────────────────────────────────────────────────────────────────────────────
        // ZH / KO lexicons — FIRST PASS, NATIVE-REVIEW REQUIRED (Phase 3).
        // These cover the high-frequency industrial actions/objects so the SAME canonical
        // SafetyFrame + comparator + UNSAFE-PASS metric apply to VI↔ZH and VI↔KO. They are NOT
        // claimed to be a complete or native-verified vocabulary; a native EHS reviewer must vet
        // every entry before any ZH/KO deployment. Detection is universal; repair into ZH/KO is
        // deliberately gated off until a renderer is validated (see SafetyRepairer / docs).
        // ────────────────────────────────────────────────────────────────────────────────────
        val ZH_LEXICON = Lexicon(
            actions = mapOf(
                // Surfaces are kept UNIQUE across actions and multi-char where possible. CJK is
                // matched by substring (no word boundary) and resolved by LONGEST match, so a
                // single-char surface that is a fragment of a compound must be avoided. 打开/关闭
                // map to OPEN/CLOSE (the literal aperture sense); energy on/off uses unambiguous
                // 接通/切断/通电(ENERGIZE). NATIVE-REVIEW REQUIRED (Phase 3).
                SafetyAction.STOP to listOf("停止", "停下", "停机"),
                SafetyAction.START to listOf("启动", "开动", "开机"),
                SafetyAction.TURN_OFF to listOf("关掉", "关电源", "切断电源"),
                SafetyAction.TURN_ON to listOf("开启", "接通电源", "开电源"),
                SafetyAction.ENTER to listOf("进入"),
                SafetyAction.EXIT to listOf("离开", "退出", "出去"),
                SafetyAction.OPEN to listOf("打开", "开阀", "开门"),
                SafetyAction.CLOSE to listOf("关闭", "关上"),
                SafetyAction.CALL to listOf("呼叫", "联系", "通知"),
                SafetyAction.WAIT to listOf("等待"),
                SafetyAction.EVACUATE to listOf("疏散", "撤离"),
                SafetyAction.WEAR to listOf("佩戴", "穿戴"),
                SafetyAction.MOVE to listOf("移动"),
                SafetyAction.LOCK_OUT to listOf("锁定", "上锁"),
                SafetyAction.TAG_OUT to listOf("挂牌", "挂签"),
                SafetyAction.DE_ENERGIZE to listOf("断电", "切断电源"),
                SafetyAction.ENERGIZE to listOf("通电", "送电", "恢复供电"),
                SafetyAction.VERIFY_DEAD to listOf("验电", "确认断电"),
                SafetyAction.ISOLATE to listOf("隔离")
            ),
            objects = mapOf(
                // Multi-char only: a bare 电/门/锁 substring-matches inside 触电/部门/阀门/锁定 and
                // would fabricate a phantom object. Longest-match in detectObject then picks 阀门
                // over any shorter accidental hit.
                SafetyObject.CONVEYOR_BELT to listOf("传送带", "输送带", "皮带"),
                SafetyObject.FORKLIFT to listOf("叉车"),
                SafetyObject.HAZARD_ZONE to listOf("危险区", "危险区域", "禁区", "限制区域"),
                SafetyObject.PRESSURE_VALVE to listOf("压力阀", "阀门"),
                SafetyObject.EMERGENCY_STOP to listOf("急停", "紧急停止"),
                SafetyObject.POWER to listOf("电源"),
                SafetyObject.DOOR to listOf("车门", "大门", "舱门"),
                SafetyObject.PROTECTIVE_EQUIPMENT to listOf("防护装备", "防护用品", "安全帽"),
                SafetyObject.SUPERVISOR to listOf("主管", "管理员", "班长"),
                SafetyObject.LOCK to listOf("挂锁"),
                SafetyObject.TAG to listOf("标签牌", "警示牌"),
                SafetyObject.BREAKER to listOf("断路器", "空开"),
                SafetyObject.ISOLATION_POINT to listOf("隔离点"),
                SafetyObject.MACHINE to listOf("机器", "机床", "设备", "冲压机")
            )
        )

        val KO_LEXICON = Lexicon(
            actions = mapOf(
                SafetyAction.STOP to listOf("정지", "멈춰", "중지"),
                SafetyAction.START to listOf("시작", "가동", "기동"),
                SafetyAction.TURN_OFF to listOf("끄기", "차단", "전원 차단"),
                SafetyAction.TURN_ON to listOf("켜기", "전원 켜", "전원 인가"),
                SafetyAction.ENTER to listOf("들어가", "진입"),
                SafetyAction.EXIT to listOf("나가", "퇴출", "이탈"),
                SafetyAction.OPEN to listOf("열어", "열기"),
                SafetyAction.CLOSE to listOf("닫아", "닫기"),
                SafetyAction.CALL to listOf("호출", "연락", "부르"),
                SafetyAction.WAIT to listOf("대기", "기다"),
                SafetyAction.EVACUATE to listOf("대피", "탈출"),
                SafetyAction.WEAR to listOf("착용"),
                SafetyAction.MOVE to listOf("이동"),
                SafetyAction.LOCK_OUT to listOf("잠금", "록아웃"),
                SafetyAction.TAG_OUT to listOf("태그", "표찰 부착"),
                SafetyAction.DE_ENERGIZE to listOf("정전", "전원 차단"),
                SafetyAction.ENERGIZE to listOf("송전", "급전", "전원 인가"),
                SafetyAction.VERIFY_DEAD to listOf("검전", "정전 확인"),
                SafetyAction.ISOLATE to listOf("격리", "분리")
            ),
            objects = mapOf(
                // Multi-char/spaced only: a bare 문(door) substring-matches inside 질문(question),
                // 부문(division), 문제(problem); 전(power) inside 전화(call). Longest-match resolves
                // the rest. NATIVE-REVIEW REQUIRED (Phase 3).
                SafetyObject.CONVEYOR_BELT to listOf("컨베이어", "벨트"),
                SafetyObject.FORKLIFT to listOf("지게차"),
                SafetyObject.HAZARD_ZONE to listOf("위험 구역", "위험구역", "출입 금지 구역", "제한 구역"),
                SafetyObject.PRESSURE_VALVE to listOf("압력 밸브", "밸브"),
                SafetyObject.EMERGENCY_STOP to listOf("비상 정지", "비상정지"),
                SafetyObject.POWER to listOf("전원", "전기"),
                SafetyObject.DOOR to listOf("출입문", "도어"),
                SafetyObject.PROTECTIVE_EQUIPMENT to listOf("보호 장비", "보호구", "안전모"),
                SafetyObject.SUPERVISOR to listOf("관리자", "감독자", "반장"),
                SafetyObject.LOCK to listOf("자물쇠"),
                SafetyObject.TAG to listOf("태그", "경고 표찰"),
                SafetyObject.BREAKER to listOf("차단기"),
                SafetyObject.ISOLATION_POINT to listOf("격리 지점"),
                SafetyObject.MACHINE to listOf("기계", "장비", "프레스")
            )
        )
    }
}

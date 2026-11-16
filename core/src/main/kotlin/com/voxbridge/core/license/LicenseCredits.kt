package com.voxbridge.core.license

/**
 * One third-party model component shipped inside VoxBridge, with its license
 * exactly as recorded in `models/MODEL_MANIFEST.md` (the single source of truth).
 * If the manifest changes, change this list in the same commit — the self-verify
 * harness ([tools/self-verify]) fails the build when the About credits and the
 * manifest drift apart.
 */
data class ModelCredit(
    /** Human-readable component name shown in the About/credits screen. */
    val component: String,
    /** Upstream repository / model id. */
    val source: String,
    /** License identifier as recorded in the manifest (SPDX-style). */
    val license: String,
    /** True when the license obliges the app to display attribution (e.g. CC-BY-4.0). */
    val requiresAttribution: Boolean,
)

/**
 * License credits for the About/credits screen of the app.
 *
 * Why this lives in `core` (pure Kotlin, no Android deps): the attribution text is a
 * legal obligation tied to the shipped model set, so it must be verifiable on the
 * laptop toolchain like every other claim in this project — see the
 * "[Model license credits]" section in `tools/self-verify/Verify.kt`.
 *
 * Scope note: only licenses explicitly recorded in `models/MODEL_MANIFEST.md` are
 * listed here. Components whose license the manifest does not record (e.g. the
 * Whisper weights, Piper voices) must NOT be added with a guessed license — record
 * them in the manifest first, then here.
 */
object LicenseCredits {

    /** 5 Apache-2.0 standard hops + 1 CC-BY-4.0 tc-big hop (the 6-hop MT registry) + Silero VAD. */
    val credits: List<ModelCredit> = listOf(
        ModelCredit("OPUS-MT Vietnamese↔English", "Helsinki-NLP/opus-mt-vi-en", "Apache-2.0", false),
        ModelCredit("OPUS-MT English→Vietnamese", "Helsinki-NLP/opus-mt-en-vi", "Apache-2.0", false),
        ModelCredit("OPUS-MT English→Chinese", "Helsinki-NLP/opus-mt-en-zh", "Apache-2.0", false),
        ModelCredit("OPUS-MT Chinese→Vietnamese", "Helsinki-NLP/opus-mt-zh-vi", "Apache-2.0", false),
        ModelCredit("OPUS-MT Korean→English", "Helsinki-NLP/opus-mt-ko-en", "Apache-2.0", false),
        ModelCredit(
            component = "OPUS-MT tc-big English→Korean",
            source = "Helsinki-NLP/opus-mt-tc-big-en-ko",
            license = "CC-BY-4.0",
            requiresAttribution = true,
        ),
        ModelCredit("Silero VAD", "snakers4/silero-vad", "MIT", false),
    )

    /** Components whose license obliges the app to display attribution (CC-BY-4.0). */
    val attributionRequired: List<ModelCredit> = credits.filter { it.requiresAttribution }

    /**
     * Full text the About/credits screen must render. The UI wiring is a Phase 3 task
     * (needs the Android toolchain); until then this function is the single source of
     * the required attribution text — callers MUST render it verbatim, not paraphrase it.
     */
    fun aboutText(): String {
        val byLicense = credits.groupBy { it.license }
        val lines = buildList {
            add("VoxBridge runs the following open models entirely on-device:")
            byLicense["Apache-2.0"]?.let { group ->
                add("• OPUS-MT machine translation (Helsinki-NLP): " +
                    group.joinToString(", ") { it.source.removePrefix("Helsinki-NLP/") } +
                    " — Apache-2.0")
            }
            byLicense["CC-BY-4.0"]?.forEach { credit ->
                add("• ${credit.component} (${credit.source}) — ${credit.license}. " +
                    "Attribution required: Copyright (c) Helsinki-NLP, licensed under " +
                    "CC-BY-4.0 (https://creativecommons.org/licenses/by/4.0/).")
            }
            byLicense["MIT"]?.forEach { credit ->
                add("• ${credit.component} (${credit.source}) — ${credit.license}.")
            }
            add("All inference runs locally; no audio or text ever leaves the device.")
        }
        return lines.joinToString("\n")
    }
}

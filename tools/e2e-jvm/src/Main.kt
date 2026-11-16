package voxbridge.e2e

import ai.onnxruntime.OrtEnvironment
import com.voxbridge.core.audio.AudioEvidence
import com.voxbridge.core.model.Language
import com.voxbridge.core.normalize.VietnameseNormalizer
import com.voxbridge.core.pipeline.DecisionEngine
import com.voxbridge.core.pipeline.TranslationDecision
import com.voxbridge.core.translate.Translator
import com.voxbridge.runtime.ModelPaths
import com.voxbridge.runtime.OnnxTranslator
import com.voxbridge.runtime.SileroVad
import com.voxbridge.runtime.WhisperAsr
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.text.Normalizer
import java.util.Locale
import kotlin.system.exitProcess

/**
 * JVM end-to-end harness: compiles the REAL `runtime/` Kotlin (WhisperAsr, OnnxTranslator,
 * SileroVad, MelSpectrogram, SpmTokenizer, WhisperBpeDecoder, ModelPaths) together with the
 * REAL `core/` module and runs the app's actual decode code against the REAL exported ONNX
 * models — the exact graphs that colab/02_build_apk.ipynb bundles into the APK assets
 * (`android/app/src/main/assets/models/`).
 *
 * What each stage proves (and honestly what it does not):
 *  1. VAD          — 30 s of digital silence stays silent (max speech prob < 0.5).
 *  2. Whisper ASR  — WhisperAsr.load succeeds on the exported CPU graphs (this runs the full
 *                    host-side decode loop: encoder -> forced prompt -> KV caching -> greedy
 *                    argmax -> BPE). Raw silence decoding is logged as a model diagnostic because
 *                    the shipping pipeline gates with Silero VAD before ASR. Optional real 16 kHz
 *                    mono PCM16 WAVs exercise the exact Kotlin ASR path on voiced speech.
 *  3. OPUS-MT      — both directions load (encoder + decoder-with-past KV chaining), translate
 *                    a fixed product-smoke phrase, preserve its expected semantic anchors, and
 *                    return a valid (0,1] translationScore.
 *
 * Exit code: 0 = all staged checks passed, 1 = at least one FAIL, 2 = setup error.
 */
private var passed = 0
private var failed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
    if (cond) {
        passed++
        println("PASS  $name")
    } else {
        failed++
        val d = detail()
        val suffix = if (d.isBlank()) "" else " — $d"
        failures += "$name$suffix"
        println("FAIL  $name$suffix")
    }
}

private fun section(title: String) = println("\n=== $title ===")

private fun ms(from: Long, to: Long): Long = (to - from) / 1_000_000

fun main(args: Array<String>) {
    val modelsRoot = File(args.getOrElse(0) { "models" }).absoluteFile
    println("VoxBridge — JVM end-to-end against real exported ONNX graphs")
    println("models root: $modelsRoot")
    check("models root exists", modelsRoot.isDirectory) {
        "not found — run colab/01_models_pipeline.ipynb or model-tools-python/scripts/fetch_all.sh first"
    }
    if (failed > 0) exitProcess(2) // setup error, per the documented exit-code contract

    val paths = ModelPaths(modelsRoot)
    val env = OrtEnvironment.getEnvironment()
    val viWav = System.getenv("VOXBRIDGE_VI_WAV")?.takeIf { it.isNotBlank() }?.let(::File)
    val enWav = System.getenv("VOXBRIDGE_EN_WAV")?.takeIf { it.isNotBlank() }?.let(::File)
    val vadWav = System.getenv("VOXBRIDGE_VAD_WAV")?.takeIf { it.isNotBlank() }?.let(::File)
    val viExpected = expectedFragments("VOXBRIDGE_VI_EXPECT", listOf("xin chào", "hỗ trợ"))
    val enExpected = expectedFragments("VOXBRIDGE_EN_EXPECT", listOf("hello", "help"))
    val asrCorpus = System.getenv("VOXBRIDGE_ASR_CORPUS")?.takeIf { it.isNotBlank() }?.let(::File)
    val unifiedManifest = System.getenv("VOXBRIDGE_UNIFIED_MANIFEST")?.takeIf { it.isNotBlank() }?.let(::File)
    val unifiedOnly = System.getenv("VOXBRIDGE_UNIFIED_ONLY") == "1"
    val corpusManifest = System.getenv("VOXBRIDGE_CORPUS_MANIFEST")?.takeIf { it.isNotBlank() }?.let(::File)
    val corpusOnly = System.getenv("VOXBRIDGE_CORPUS_ONLY") == "1"

    if (!unifiedOnly && !corpusOnly) {
        vadStage(env, paths, vadWav)
        preAsrPolicyStage()
        whisperStage(
            env, paths.forLanguage("vi"), Language.VI, Language.EN,
            voicedWav = viWav,
            expectedFragments = viExpected,
        )
        whisperStage(
            env, paths.forLanguage("en"), Language.EN, Language.VI,
            voicedWav = enWav,
            expectedFragments = enExpected,
        )
        opusMtStage(env, paths, "vi", "en", Language.VI, Language.EN, "xin chào", listOf("hello"))
        opusMtStage(
            env, paths, "en", "vi", Language.EN, Language.VI,
            "Hello, I need help.", listOf("xin chào", "giúp"),
        )
        if (asrCorpus != null) asrCorpusStage(env, paths, asrCorpus)
    }
    if (unifiedManifest != null) {
        val unified = UnifiedRealRunner(env, paths).run(unifiedManifest)
        check("unified real sample runner gates", unified.gateFailures == 0) {
            "gateFailures=${unified.gateFailures} log=${unified.logFile.absolutePath}"
        }
    } else if (unifiedOnly) {
        check("unified manifest configured", false) { "VOXBRIDGE_UNIFIED_MANIFEST is required with VOXBRIDGE_UNIFIED_ONLY=1" }
    }
    if (corpusManifest != null) {
        val corpus = CorpusRealRunner(env, paths).run(corpusManifest)
        check("corpus real runner gates", corpus.gateFailures == 0) {
            "gateFailures=${corpus.gateFailures} log=${corpus.logFile.absolutePath}"
        }
    } else if (corpusOnly) {
        check("corpus manifest configured", false) { "VOXBRIDGE_CORPUS_MANIFEST is required with VOXBRIDGE_CORPUS_ONLY=1" }
    }

    finish()
}

/**
 * Host-side lock for the exact configured capture policy used by Android. This stage deliberately
 * does not fabricate ASR or MT output: terminal capture evidence must stop here, and healthy
 * evidence must return null (the sole permission to proceed to Whisper).
 */
private fun preAsrPolicyStage() {
    section("Shared pre-ASR capture policy — DecisionEngine config")
    val engine = DecisionEngine(
        translator = Translator { _, _, _ -> error("pre-ASR policy stage must never translate") },
    )
    val healthy = AudioEvidence(
        speechPresent = true,
        audioScore = 0.95,
        durationMs = 1_500,
        validSpeechDurationMs = 900,
    )

    fun expect(name: String, audio: AudioEvidence, expected: TranslationDecision?) {
        val actual = engine.preAsrDecision(audio)
        check(name, actual == expected) { "expected=$expected actual=$actual" }
    }

    expect(
        "pre-ASR silence terminates as NO_SPEECH",
        healthy.copy(speechPresent = false, validSpeechDurationMs = 0),
        TranslationDecision.NoSpeech,
    )
    expect(
        "pre-ASR clipping terminates before Whisper",
        healthy.copy(clippingDetected = true),
        TranslationDecision.AskRepeat("audio_clipped"),
    )
    expect(
        "pre-ASR noise terminates before Whisper",
        healthy.copy(tooNoisy = true),
        TranslationDecision.AskRepeat("audio_too_noisy"),
    )
    expect(
        "pre-ASR low quality terminates before Whisper",
        healthy.copy(audioScore = 0.35),
        TranslationDecision.AskRepeat("audio_low_quality"),
    )
    expect(
        "pre-ASR short speech terminates before Whisper",
        healthy.copy(validSpeechDurationMs = 200),
        TranslationDecision.AskRepeat("speech_too_short"),
    )
    check("healthy capture is allowed to reach Whisper", engine.preAsrDecision(healthy) == null)
}

private fun expectedFragments(envName: String, defaults: List<String>): List<String> =
    System.getenv(envName)
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        ?.takeIf(List<String>::isNotEmpty)
        ?: defaults

// ---------------------------------------------------------------------------
// 1) Silero VAD — real session, real recurrent state
// ---------------------------------------------------------------------------

private fun vadStage(env: OrtEnvironment, paths: ModelPaths, voicedWav: File?) {
    section("Silero VAD — silence must stay silent")
    if (!paths.vad.exists()) {
        check("VAD model exists", false) { paths.vad.path }
        return
    }
    try {
        SileroVad.load(env, paths.vad).use { vad ->
            val chunk = FloatArray(512) // 32 ms @16k — Silero's native window
            val chunks30s = (30 * 16_000) / 512 // 938 chunks ≈ 30 s of digital silence
            val maxProb = (0 until chunks30s).map { vad.chunkProbability(chunk) }.max()
            println("      30 s digital silence ($chunks30s × 32 ms) -> max speech prob = $maxProb")
            check("silence speech prob stays below 0.5", maxProb < 0.5f) { "max=$maxProb" }

            if (voicedWav != null) {
                check("VAD voiced WAV exists", voicedWav.isFile) { voicedWav.absolutePath }
                if (voicedWav.isFile) {
                    val voiced = readMono16kPcm16Wav(voicedWav)
                    vad.reset()
                    var speechFrames = 0
                    var voicedMax = 0f
                    var offset = 0
                    while (offset + 512 <= voiced.size) {
                        val p = vad.chunkProbability(voiced.copyOfRange(offset, offset + 512))
                        if (p > voicedMax) voicedMax = p
                        if (p >= 0.5f) speechFrames++
                        offset += 512
                    }
                    val speechMs = speechFrames.toLong() * 32L
                    println(
                        "      voiced VAD '${voicedWav.name}' -> max=$voicedMax, " +
                            "speech=${speechMs}ms",
                    )
                    check("real voiced WAV is detected as speech", speechMs >= 250L) {
                        "max=$voicedMax speechMs=$speechMs"
                    }
                }
            }
        }
    } catch (e: Exception) {
        check("VAD load + run", false) { "${e.javaClass.simpleName}: ${e.message}" }
    }
}

// ---------------------------------------------------------------------------
// 2) Whisper ASR — WhisperAsr.load + full host-side decode on silence
// ---------------------------------------------------------------------------

private fun whisperStage(
    env: OrtEnvironment,
    paths: ModelPaths,
    src: Language,
    tgt: Language,
    voicedWav: File?,
    expectedFragments: List<String>,
) {
    section("Whisper ASR ${paths.whisperOnnxDir.name} — real Kotlin decode")
    val cpuEnc = paths.whisperOnnxDir.resolve("encoder_model.onnx").exists()
    if (!cpuEnc && !paths.qnnPairReady()) {
        check("selected ASR encoder exists", false) { paths.whisperOnnxDir.path }
        return
    }
    val tag = if (cpuEnc && !paths.qnnPairReady()) "CPU" else "QNN-pair-present"
    println("      (info) artifacts: cpu_encoder=$cpuEnc qnn_pair=${paths.qnnPairReady()} [$tag]")
    if (paths.qnnPairReady()) {
        // Desktop onnxruntime has no QNN EP and no Hexagon backend (libQnnHtp.so is an Android
        // device library), so the *_ctx.onnx wrappers can only fail here — the desktop gate
        // verifies the CPU ONNX decode contract; the QNN path is exercised on-device.
        println("      (info) QNN ctx pair present — verifying the CPU ONNX contract (desktop JVM)")
    }
    try {
        val t0 = System.nanoTime()
        WhisperAsr.load(env, paths, preferCpu = true).use { asr ->
            // load() already ran the 4-step warm-up decode — reaching here proves encoder +
            // decoder + tokens + mel + BPE all wired to the app's real decode logic.
            println("      load + warm-up OK in ${ms(t0, System.nanoTime())} ms")
            check("WhisperAsr.load succeeds (incl. bounded 4-step warm-up decode)", true)

            val t1 = System.nanoTime()
            val r = asr.transcribe(FloatArray(480_000), src, tgt)
            println(
                "      transcribe(30 s silence) -> text='${r.text}' " +
                    "noSpeech=${r.noSpeechProbability} score=${r.asrScore} (${ms(t1, System.nanoTime())} ms)",
            )
            if (r.text.isBlank()) {
                println("      (diagnostic) raw silence decoded blank")
            } else {
                println(
                    "      (diagnostic) raw silence hallucinated '${r.text}' — shipping path skips ASR " +
                        "when Silero VAD reports no speech",
                )
            }
            println("      (diagnostic) raw silence noSpeechProbability=${r.noSpeechProbability}")

            if (voicedWav == null) {
                println("      (info) no voiced WAV supplied; set ${wavEnvName(src)} for voiced ASR smoke")
            } else {
                check("voiced WAV exists", voicedWav.isFile) { voicedWav.absolutePath }
                if (voicedWav.isFile) {
                    val voiced = readMono16kPcm16Wav(voicedWav)
                    println(
                        "      voiced WAV '${voicedWav.name}': ${voiced.size} samples " +
                            "(${String.format(Locale.US, "%.2f", voiced.size / 16_000.0)} s)",
                    )
                    val t2 = System.nanoTime()
                    val vr = asr.transcribe(voiced, src, tgt)
                    println(
                        "      voiced transcript='${vr.text}' noSpeech=${vr.noSpeechProbability} " +
                            "score=${vr.asrScore} (${ms(t2, System.nanoTime())} ms)",
                    )
                    check("voiced transcript is non-blank", vr.text.isNotBlank()) { "blank output" }
                    val normalized = normalizeForSemanticCheck(vr.text, src)
                    val normalizedExpected = expectedFragments.map { normalizeForSemanticCheck(it, src) }
                    check("voiced transcript preserves smoke-test semantics", normalizedExpected.all(normalized::contains)) {
                        "expected ${expectedFragments.joinToString()} in '${vr.text}'"
                    }
                }
            }
        }
    } catch (e: Exception) {
        check("Whisper load + transcribe", false) { "${e.javaClass.simpleName}: ${e.message}" }
    }
}

private fun normalizeForSemanticCheck(text: String, language: Language): String = when (language) {
    Language.VI -> VietnameseNormalizer().normalize(text)
    else -> text.lowercase(Locale.ROOT)
}

private fun wavEnvName(src: Language): String = when (src) {
    Language.VI -> "VOXBRIDGE_VI_WAV"
    Language.EN -> "VOXBRIDGE_EN_WAV"
    else -> "VOXBRIDGE_${src.name}_WAV"
}

/** Minimal RIFF/WAVE reader for host e2e fixtures: PCM, mono, 16-bit, exactly 16 kHz. */
internal fun readMono16kPcm16Wav(file: File): FloatArray {
    DataInputStream(FileInputStream(file).buffered()).use { input ->
        fun readAscii4(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        fun readLeInt(): Int = Integer.reverseBytes(input.readInt())
        fun readLeShort(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff

        require(readAscii4() == "RIFF") { "${file.name}: missing RIFF header" }
        readLeInt() // container size
        require(readAscii4() == "WAVE") { "${file.name}: missing WAVE signature" }

        var audioFormat: Int? = null
        var channels: Int? = null
        var sampleRate: Int? = null
        var bitsPerSample: Int? = null
        var data: ByteArray? = null

        while (input.available() >= 8) {
            val chunkId = readAscii4()
            val chunkSize = readLeInt()
            require(chunkSize >= 0) { "${file.name}: invalid chunk size $chunkSize" }
            when (chunkId) {
                "fmt " -> {
                    require(chunkSize >= 16) { "${file.name}: fmt chunk too short ($chunkSize)" }
                    audioFormat = readLeShort()
                    channels = readLeShort()
                    sampleRate = readLeInt()
                    readLeInt()   // byte rate
                    readLeShort() // block align
                    bitsPerSample = readLeShort()
                    val remaining = chunkSize - 16
                    if (remaining > 0) input.skipBytes(remaining)
                }
                "data" -> data = ByteArray(chunkSize).also(input::readFully)
                else -> input.skipBytes(chunkSize)
            }
            if (chunkSize and 1 == 1 && input.available() > 0) input.skipBytes(1) // RIFF word alignment
        }

        require(audioFormat == 1) { "${file.name}: expected PCM format 1, got $audioFormat" }
        require(channels == 1) { "${file.name}: expected mono, got $channels channels" }
        require(sampleRate == 16_000) { "${file.name}: expected 16000 Hz, got $sampleRate" }
        require(bitsPerSample == 16) { "${file.name}: expected PCM16, got $bitsPerSample bits" }
        val bytes = requireNotNull(data) { "${file.name}: missing data chunk" }
        require(bytes.size % 2 == 0) { "${file.name}: PCM16 data has odd byte length ${bytes.size}" }
        return FloatArray(bytes.size / 2) { i ->
            val lo = bytes[i * 2].toInt() and 0xff
            val hi = bytes[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            sample.toShort() / 32768.0f
        }
    }
}

// ---------------------------------------------------------------------------
// Optional real-audio corpus — exact Kotlin VAD + ASR with independently computed WER
// ---------------------------------------------------------------------------

private data class AsrCorpusRow(val language: Language, val wav: File, val reference: String)

private fun asrCorpusStage(env: OrtEnvironment, paths: ModelPaths, manifest: File) {
    section("Real ASR corpus — Silero VAD + selected language-routed Whisper models")
    check("ASR corpus manifest exists", manifest.isFile) { manifest.absolutePath }
    if (!manifest.isFile) return

    val rows = manifest.readLines().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
        val cols = line.split('\t', limit = 3)
        require(cols.size == 3) { "${manifest.name}: expected <vi|en>\\t<wav>\\t<reference>: $line" }
        val language = when (cols[0].lowercase(Locale.ROOT)) {
            "vi" -> Language.VI
            "en" -> Language.EN
            else -> error("${manifest.name}: unsupported corpus language '${cols[0]}'")
        }
        val wav = File(cols[1]).let { if (it.isAbsolute) it else File(manifest.parentFile, cols[1]) }
        AsrCorpusRow(language, wav, cols[2])
    }
    check("ASR corpus contains samples", rows.isNotEmpty())
    if (rows.isEmpty()) return

    try {
        SileroVad.load(env, paths.vad).use { vad ->
            for (row in rows) {
                val audio = readMono16kPcm16Wav(row.wav)
                vad.reset()
                var speechFrames = 0
                var offset = 0
                while (offset + 512 <= audio.size) {
                    if (vad.chunkProbability(audio.copyOfRange(offset, offset + 512)) >= 0.5f) speechFrames++
                    offset += 512
                }
                val speechMs = speechFrames * 32L
                check("corpus VAD ${row.language.name}/${row.wav.name}", speechMs >= 250L) {
                    "speechMs=$speechMs"
                }
            }
        }
    } catch (e: Exception) {
        check("corpus VAD runtime", false) { "${e.javaClass.simpleName}: ${e.message}" }
        return
    }

    for (language in listOf(Language.VI, Language.EN)) {
        val selected = rows.filter { it.language == language }
        if (selected.isEmpty()) continue
        val target = if (language == Language.VI) Language.EN else Language.VI
        var totalErrors = 0
        var totalReferenceWords = 0
        try {
            WhisperAsr.load(env, paths.forLanguage(language.name.lowercase(Locale.ROOT)), preferCpu = true).use { asr ->
                for (row in selected) {
                    val audio = readMono16kPcm16Wav(row.wav)
                    val started = System.nanoTime()
                    val result = asr.transcribe(audio, language, target)
                    val counts = wordErrorCounts(row.reference, result.text)
                    totalErrors += counts.first
                    totalReferenceWords += counts.second
                    val sampleWer = if (counts.second == 0) 0.0 else counts.first.toDouble() / counts.second
                    println(
                        "      ${language.name}/${row.wav.name}: WER=" +
                            String.format(Locale.US, "%.1f%%", sampleWer * 100.0) +
                            " (${ms(started, System.nanoTime())} ms) ref='${row.reference}' hyp='${result.text}'",
                    )
                    check("corpus ASR ${language.name}/${row.wav.name} non-blank", result.text.isNotBlank())
                }
            }
        } catch (e: Exception) {
            check("corpus ASR ${language.name} runtime", false) { "${e.javaClass.simpleName}: ${e.message}" }
            continue
        }
        val aggregateWer = if (totalReferenceWords == 0) 0.0 else totalErrors.toDouble() / totalReferenceWords
        println(
            "      ${language.name} aggregate: $totalErrors/$totalReferenceWords word errors = " +
                String.format(Locale.US, "%.2f%% WER", aggregateWer * 100.0),
        )
        // This tiny deterministic sample is a catastrophic-regression gate, not the proposal's
        // full-FLEURS acceptance statistic. The full benchmark numbers remain separate evidence.
        check("corpus ASR ${language.name} aggregate WER < 60%", aggregateWer < 0.60) {
            "WER=${String.format(Locale.US, "%.2f%%", aggregateWer * 100.0)}"
        }
    }
}

private fun wordErrorCounts(reference: String, hypothesis: String): Pair<Int, Int> {
    fun words(text: String): List<String> = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
        .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)

    val ref = words(reference)
    val hyp = words(hypothesis)
    var previous = IntArray(hyp.size + 1) { it }
    for (i in ref.indices) {
        val current = IntArray(hyp.size + 1)
        current[0] = i + 1
        for (j in hyp.indices) {
            val substitution = previous[j] + if (ref[i] == hyp[j]) 0 else 1
            val deletion = previous[j + 1] + 1
            val insertion = current[j] + 1
            current[j + 1] = minOf(substitution, deletion, insertion)
        }
        previous = current
    }
    return previous[hyp.size] to ref.size
}

// ---------------------------------------------------------------------------
// 3) OPUS-MT — both directions, past-KV decode, translationScore
// ---------------------------------------------------------------------------

private fun opusMtStage(
    env: OrtEnvironment,
    paths: ModelPaths,
    srcTag: String,
    tgtTag: String,
    src: Language,
    tgt: Language,
    input: String,
    expectedFragments: List<String>,
) {
    section("OPUS-MT opus-mt-$srcTag-$tgtTag — past-KV greedy decode")
    val dir = paths.translationDir(srcTag, tgtTag)
    val hasEnc = dir.resolve("encoder_model.onnx").exists() ||
        dir.resolve("encoder_model_quantized.onnx").exists()
    if (!hasEnc) {
        check("MT encoder exists", false) { dir.path }
        return
    }
    try {
        val t0 = System.nanoTime()
        OnnxTranslator.load(env, dir, src, tgt).use { mt ->
            val r = mt.translate(input, src, tgt)
            println(
                "      '$input' -> '${r.translatedText}' " +
                    "score=${r.translationScore} (${ms(t0, System.nanoTime())} ms)",
            )
            check("translation is non-blank", r.translatedText.isNotBlank()) { "empty output" }
            val normalized = r.translatedText.lowercase(Locale.ROOT)
            check("translation preserves smoke-test semantics", expectedFragments.all(normalized::contains)) {
                "expected ${expectedFragments.joinToString()} in '${r.translatedText}'"
            }
            check("translationScore in (0,1]", (r.translationScore ?: 0.0) > 0.0) {
                "score=${r.translationScore}"
            }
        }
    } catch (e: Exception) {
        check("opus-mt-$srcTag-$tgtTag load + translate", false) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }
}

private fun finish() {
    println()
    println("========================================================")
    println("e2e-jvm result: $passed passed, $failed failed")
    if (failures.isNotEmpty()) failures.forEach { println("  - $it") }
    if (failed > 0) exitProcess(1)
    exitProcess(0)
}

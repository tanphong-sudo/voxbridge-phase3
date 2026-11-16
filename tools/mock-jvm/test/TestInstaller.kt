import com.voxbridge.runtime.ModelInstallException
import com.voxbridge.runtime.ModelInstaller
import com.voxbridge.runtime.ModelPaths
import java.io.File
import kotlin.system.exitProcess

/**
 * Behavioural tests for [ModelInstaller]'s extraction logic (real file IO on the JVM):
 *  1. full bundle → extracted, byte-exact, marker written ("ok" when the bundle has no version);
 *  2. second call with a DEAD asset source → proves the cpuSetReady() early-return (adb-push /
 *     previous-run path never touches assets);
 *  3. incomplete bundle → ModelInstallException listing the missing files, no marker;
 *  4. absent bundle → actionable error mentioning the adb alternative;
 *  5. resume — pre-existing files kept, missing ones extracted, end state complete;
 *  6. bundle missing the Piper voices → the [ModelPaths.piperMissing] gate fires by name;
 *  7. version.txt refresh — new bundle version wipes the old tree (stale leftovers gone)
 *     and re-extracts the new weights; same version (or no version) stays a no-op.
 */
private var checks = 0
private var failed = false

private fun check(cond: Boolean, what: String) {
    checks++
    if (cond) println("ok    $what") else { println("FAIL  $what"); failed = true }
}

private fun buildBundle(root: File, withWhisper: Boolean = true, withPiper: Boolean = true) {
    File(root, "vad").mkdirs()
    File(root, "vad/silero_vad.onnx").writeBytes(byteArrayOf(8, 1, 2, 3))
    for ((src, tgt) in ModelPaths.MT_DIRECTIONS) {
        val d = "translation/opus-mt-$src-$tgt"
        File(root, d).mkdirs()
        for (f in listOf(
            "encoder_model.onnx", "decoder_model.onnx", "decoder_with_past_model.onnx",
            "source.spm", "target.spm", "vocab.json",
        )) File(root, "$d/$f").writeBytes(f.toByteArray())
    }
    if (withWhisper) {
        File(root, "speech_to_text/phowhisper-base-onnx").mkdirs()
        for (f in listOf("encoder_model.onnx", "decoder_model.onnx", "decoder_with_past_model.onnx", "vocab.json", "whisper_tokens.json", "mel_80_201.f32.bin"))
            File(root, "speech_to_text/phowhisper-base-onnx/$f").writeBytes(f.toByteArray())
        File(root, "speech_to_text/phowhisper-base-onnx").copyRecursively(File(root, "speech_to_text/whisper-small-onnx"), overwrite = true)
    }
    if (withPiper) {
        for (voice in ModelPaths.PIPER_VOICES) {
            val dir = File(root, "text_to_speech/piper/$voice")
            dir.mkdirs()
            File(dir, "$voice.onnx").writeBytes(voice.toByteArray())
            File(dir, "tokens.txt").writeBytes("tokens\n".toByteArray())
            File(dir, "espeak-ng-data").mkdirs()
            File(dir, "espeak-ng-data/phontab").writeBytes("phontab".toByteArray())
        }
    }
}

private fun tmpDir(name: String): File {
    val d = File(System.getProperty("java.io.tmpdir"), "mvitest-$name-${System.nanoTime()}")
    d.mkdirs()
    return d
}

fun main() {
    // ---- case 1: full bundle extracts completely, byte-exact ------------------------------
    val tmp = tmpDir("full")
    val assets = File(tmp, "assets")
    val bundle = File(assets, "models").apply { buildBundle(this) }
    val root = File(tmp, "installed/models")
    val paths = ModelPaths(root)
    ModelInstaller.ensureInstalled(android.content.Context(android.content.res.AssetManager(assets)), paths)
    check(paths.cpuSetReady(), "cpu set ready after extraction")
    check(paths.piperMissing().isEmpty(), "piper voices satisfied after extraction")
    check(File(root, "vad/silero_vad.onnx").readBytes().size == 4, "top-level file copied")
    check(File(root, "translation/opus-mt-vi-en/decoder_with_past_model.onnx").exists(), "nested tree walked")
    check(
        File(root, "speech_to_text/phowhisper-base-onnx/mel_80_201.f32.bin").readBytes()
            .contentEquals(File(bundle, "speech_to_text/phowhisper-base-onnx/mel_80_201.f32.bin").readBytes()),
        "copy is byte-exact",
    )
    check(File(root, ".installed").exists(), "marker written after success")
    check(File(root, ".installed").readText().trim() == "ok", "marker records 'ok' for an unversioned bundle")

    check(paths.forLanguage("vi").whisperOnnxDir.name == "phowhisper-base-onnx", "VI selects PhoWhisper Base")
    check(paths.forLanguage("en").whisperOnnxDir.name == "whisper-small-onnx", "EN selects Whisper Small")
    check(!File(root, "translation/opus-mt-en-zh").exists(), "VI/EN bundle requires no ZH graph")
    val enDecoder = File(root, "speech_to_text/whisper-small-onnx/decoder_model.onnx")
    val enBytes = enDecoder.readBytes()
    enDecoder.delete()
    check(!paths.cpuSetReady(), "missing English bootstrap blocks readiness")
    enDecoder.writeBytes(enBytes)

    // ---- case 2: complete set → early return, assets never consulted ----------------------
    val deadAssets = android.content.res.AssetManager(File(tmp, "no-such-assets"))
    ModelInstaller.ensureInstalled(android.content.Context(deadAssets), paths)
    check(true, "complete set, unversioned bundle: second call is a no-op even with dead assets")

    // ---- case 3: incomplete bundle → exception lists what is missing ----------------------
    val tmp3 = tmpDir("partial")
    val assets3 = File(tmp3, "assets")
    val bundle3 = File(assets3, "models").apply { buildBundle(this, withWhisper = false) }
    val root3 = File(tmp3, "installed/models")
    val caught3 = try {
        ModelInstaller.ensureInstalled(android.content.Context(android.content.res.AssetManager(assets3)), ModelPaths(root3))
        null
    } catch (e: ModelInstallException) { e }
    check(caught3 != null, "incomplete bundle fails")
    check(caught3?.message?.contains("phowhisper-base-onnx/encoder_model.onnx") == true,
        "error names the missing file: ${caught3?.message?.lineSequence()?.firstOrNull()}")
    check(!File(root3, ".installed").exists(), "no marker on failed install")

    // ---- case 4: absent bundle → actionable error -----------------------------------------
    val caught4 = try {
        ModelInstaller.ensureInstalled(android.content.Context(android.content.res.AssetManager(File(tmp, "absent"))),
            ModelPaths(File(tmp, "installed4")))
        null
    } catch (e: ModelInstallException) { e }
    check(caught4?.message?.contains("adb") == true, "absent bundle error mentions the adb alternative")

    // ---- case 5: resume — kept finished files, filled the gaps -----------------------------
    val tmp5 = tmpDir("resume")
    val assets5 = File(tmp5, "assets")
    val bundle5 = File(assets5, "models").apply { buildBundle(this) }
    val root5 = File(tmp5, "installed/models")
    val paths5 = ModelPaths(root5)
    File(root5, "vad").mkdirs()
    File(root5, "vad/silero_vad.onnx").writeBytes(byteArrayOf(8, 1, 2, 3)) // pre-copied before "kill"
    ModelInstaller.ensureInstalled(android.content.Context(android.content.res.AssetManager(assets5)), paths5)
    check(paths5.cpuSetReady(), "resume: pre-existing file kept + missing files extracted")

    // ---- case 6: bundle without the Piper voices → piper gate fires by name ----------------
    val tmp6 = tmpDir("nopiper")
    val assets6 = File(tmp6, "assets")
    File(assets6, "models").apply { buildBundle(this, withPiper = false) }
    val root6 = File(tmp6, "installed/models")
    val caught6 = try {
        ModelInstaller.ensureInstalled(android.content.Context(android.content.res.AssetManager(assets6)), ModelPaths(root6))
        null
    } catch (e: ModelInstallException) { e }
    check(caught6 != null, "bundle missing Piper voices fails")
    check(caught6?.message?.contains("vits-piper-vi_VN-vais1000-medium") == true &&
        caught6?.message?.contains("vits-piper-en_US-lessac-medium") == true,
        "error names BOTH missing Piper voice dirs: ${caught6?.message?.lineSequence()?.drop(1)?.firstOrNull()}")
    check(!File(root6, ".installed").exists(), "no marker when the voice set is incomplete")

    // ---- case 7: version.txt refresh — stale weights never survive an update ---------------
    val tmp7 = tmpDir("version")
    val assets7 = File(tmp7, "assets")
    val bundle7 = File(assets7, "models").apply { buildBundle(this) }
    bundle7.resolve("version.txt").writeText("A\n")
    val root7 = File(tmp7, "installed/models")
    val paths7 = ModelPaths(root7)
    val ctx7 = android.content.Context(android.content.res.AssetManager(assets7))
    ModelInstaller.ensureInstalled(ctx7, paths7)
    check(File(root7, ".installed").readText().trim() == "A", "marker carries bundle version A")

    // same version again → no-op (files untouched)
    val markerBefore = File(root7, ".installed").lastModified()
    ModelInstaller.ensureInstalled(ctx7, paths7)
    check(File(root7, ".installed").lastModified() == markerBefore, "same bundle version: no-op")

    // app update: re-exported weights (new bytes) + bumped stamp
    val tokensB = "bpe-v2-payload".toByteArray()
    bundle7.resolve("speech_to_text/phowhisper-base-onnx/whisper_tokens.json").writeBytes(tokensB)
    bundle7.resolve("version.txt").writeText("B\n")
    File(root7, "STALE-LEFTOVER.tmp").writeText("old update debris") // must be wiped by the refresh
    ModelInstaller.ensureInstalled(ctx7, paths7)
    check(File(root7, ".installed").readText().trim() == "B", "marker refreshed to B")
    check(File(root7, "speech_to_text/phowhisper-base-onnx/whisper_tokens.json").readBytes().contentEquals(tokensB),
        "refresh re-extracted the new weights")
    check(!File(root7, "STALE-LEFTOVER.tmp").exists(), "refresh deleted stale leftovers")
    check(paths7.cpuSetReady(), "cpu set ready after refresh")

    println("installer: $checks checks, failed=$failed")
    if (failed) exitProcess(1)
}

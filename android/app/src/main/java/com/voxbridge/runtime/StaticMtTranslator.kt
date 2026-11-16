package com.voxbridge.runtime

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.voxbridge.core.model.Language
import com.voxbridge.core.translate.TranslationResult
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Actual source64/beam2 static QNN MT (EnViT5, one model for both directions). No network,
 *  ML Kit, CPU fallback or cached answers. VinAI v2 was dropped on 2026-11-15 after the QDC
 *  200-case comparison (garbled/stuttering spoken outputs, a dropped number, slower EN→VI). */
class StaticMtTranslator(
    private val env: OrtEnvironment,
    private val root: File,
    private val libraryDir: String,
    private val family: String,
) : PreparedTranslator {
    init { require(family == "envit5") { "Only the EnViT5 QNN translator ships; got $family" } }
    private var engine: StaticMtEngine? = null
    private var tokenizer: StaticMtTokenizer? = null
    private var active: String? = null
    private var forcedEos = -1
    private var prepared = false
    private var closed = false
    private val manifest: JSONObject by lazy { JSONObject(File(root, "models/models.json").readText()) }

    @Synchronized override fun prepare() {
        check(!closed)
        check(File(root, "models/models.json").isFile) { "Missing static QNN MT manifest" }
        for (part in listOf("encoder", "decoder")) check(manifest.has("$family/$part")) { "Missing $family/$part" }
        check(File(root, "tokenizers/$family/tokenizer.json").isFile) { "Missing $family tokenizer" }
        prepared = true
    }
    @Suppress("UNUSED_PARAMETER")
    private fun model(source: Language) = family
    private fun checkedPath(relative: String): File = File(root, relative).canonicalFile.also {
        require(it.toPath().startsWith(root.canonicalFile.toPath())) { "Model path escapes bundle" }
        check(it.isFile) { "Missing model file: $relative" }
    }
    private fun graph(name: String, part: String): File {
        val entry=manifest.getJSONObject("$name/$part")
        check(entry.getString("qairt").startsWith("2.50")) { "QNN bundle requires QAIRT2.50" }
        val files=entry.getJSONArray("files")
        for(i in 0 until files.length()) {
            val row=files.getJSONObject(i);val file=checkedPath("models/"+row.getString("path"))
            check(file.length()==row.getLong("bytes")) { "Model size mismatch: ${file.name}" }
            val hash=MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer=ByteArray(1024*1024)
                while(true){val count=input.read(buffer);if(count<0)break;hash.update(buffer,0,count)}
            }
            val digest=hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(digest==row.getString("sha256")) { "Model checksum mismatch: ${file.name}" }
        }
        return checkedPath("models/"+entry.getString("onnx"))
    }
    private fun session(file: File): OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(3)
        options.addConfigEntry("session.disable_cpu_ep_fallback", "0")
                // AI Hub precompiled_qnn_onnx graphs contain an external EPContext/model.bin.
                // Tell ORT to load that context instead of treating EPContext as a normal op.
                options.addConfigEntry("ep.context_enable", "1")
        options.addQnn(mapOf("backend_path" to File(libraryDir,"libQnnHtp.so").path, "htp_performance_mode" to "burst"))
        env.createSession(file.path, options)
    }
    @Synchronized override fun preload(source: Language, target: Language): Long {
        check(prepared && !closed)
        require(setOf(source,target)==setOf(Language.VI,Language.EN))
        val name=model(source)
        if(active==name && engine!=null)return 0
        engine?.close();engine=null;active=null;tokenizer=null
        val start=System.nanoTime()
        val encFile=graph(name,"encoder");val decFile=graph(name,"decoder")
        val tok=StaticMtTokenizer(checkedPath("tokenizers/$name/tokenizer.json"))
        val gen=JSONObject(checkedPath("tokenizers/$name/generation_config.json").readText())
        val enc=session(encFile)
        val dec=try { session(decFile) }catch(t:Throwable){enc.close();throw t}
        val loaded=StaticMtEngine(env,enc,dec)
        try {
            val encoded=tok.encode(if(source==Language.VI) "Xin chào." else "Hello.",source.name.lowercase(),64)
            val forced=if(gen.isNull("forced_eos_token_id")) -1 else gen.optInt("forced_eos_token_id",-1)
            loaded.decode(encoded.ids,encoded.mask,tok.startId(target.name.lowercase()),tok.eosId(),false,forced)
            engine=loaded;tokenizer=tok;forcedEos=forced;active=name
        }catch(t:Throwable){loaded.close();throw t}
        return (System.nanoTime()-start)/1_000_000
    }
    @Synchronized override fun translate(text: String, sourceLanguage: Language, targetLanguage: Language): TranslationResult {
        check(prepared && !closed)
        // A missing preload is a setup failure, not an expensive implicit load after PTT release.
        check(active==model(sourceLanguage) && engine!=null) { "Selected QNN MT direction is not preloaded" }
        val tok=checkNotNull(tokenizer)
        val input=tok.encode(text,sourceLanguage.name.lowercase(),64)
        val output=checkNotNull(engine).decode(input.ids,input.mask,tok.startId(targetLanguage.name.lowercase()),tok.eosId(),false,forcedEos)
        return TranslationResult(text,tok.decode(output.tokens),sourceLanguage,targetLanguage,"$active QNN FP16 beam2",hitTokenLimit=output.hitTokenLimit)
    }
    @Synchronized override fun close(){if(closed)return;closed=true;engine?.close();engine=null;tokenizer=null;active=null}
}

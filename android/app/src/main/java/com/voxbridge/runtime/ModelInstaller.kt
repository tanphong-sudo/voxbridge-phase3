package com.voxbridge.runtime

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/** Installs local APK assets transactionally. Every model, including MT, is local. */
object ModelInstaller {
    @Synchronized
    fun ensureInstalled(context: Context, paths: ModelPaths, assetDir: String = "models") {
        ModelInstallTransaction.recover(paths.root)
        val assets = context.assets
        val version = try {
            assets.open("$assetDir/version.txt").bufferedReader().use { it.readText().trim() }
        } catch (_: FileNotFoundException) { null }
        val installed = File(paths.root, ".installed").takeIf { it.isFile }?.readText()?.trim()
        if (paths.cpuSetReady() && (version == null || version == installed)) return
        if (assets.list(assetDir).isNullOrEmpty()) throw ModelInstallException(
            "Bộ model thiếu hoặc chưa xác thực. Chạy lại tools/dev/sync-models-device.sh.\n" +
                paths.missing().joinToString("\n")
        )
        try {
            val manifest = assets.open("$assetDir/integrity.sha256").bufferedReader().use { it.readLines() }
            val bytes = manifest.filter { it.isNotBlank() }.fold(0L) { total, line ->
                val size = line.split(' ', limit = 3).getOrNull(1)?.toLongOrNull()
                require(size != null && size >= 0) { "Invalid bundle integrity manifest" }
                Math.addExact(total, size)
            }
            val parent = paths.root.absoluteFile.parentFile!!
            check(parent.isDirectory || parent.mkdirs()) { "Cannot create model parent directory" }
            check(parent.usableSpace > bytes + 16L * 1024 * 1024) {
                "Không đủ dung lượng để cập nhật an toàn: cần thêm $bytes bytes; giữ nguyên model cũ."
            }
            ModelInstallTransaction.replace(paths.root, { stage ->
                copyTree(assets, assetDir, stage)
            }, { stage ->
                val missing = ModelPaths(stage, paths.profile, paths.asrLanguage).missing()
                check(missing.isEmpty()) { "Model bundle invalid:\n${missing.joinToString("\n")}" }
                FileOutputStream(File(stage, ".installed")).use {
                    it.write((version ?: "ok").toByteArray()); it.fd.sync()
                }
            })
        } catch (e: Exception) {
            throw ModelInstallException("Cài model thất bại; bản cũ được giữ lại. ${e.message}", e)
        }
    }

    private fun copyTree(assets: AssetManager, path: String, destination: File) {
        for (entry in assets.list(path).orEmpty()) {
            require(entry != "." && entry != ".." && !entry.contains('/'))
            val source = "$path/$entry"
            val target = File(destination, entry)
            if (!assets.list(source).isNullOrEmpty()) {
                check(target.isDirectory || target.mkdirs())
                copyTree(assets, source, target)
            } else {
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                // Never reuse an interrupted nonempty file; populate fresh staging from APK.
                FileOutputStream(target).use { output ->
                    assets.open(source).use { it.copyTo(output, 65536) }
                    output.fd.sync()
                }
            }
        }
    }
}

class ModelInstallException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

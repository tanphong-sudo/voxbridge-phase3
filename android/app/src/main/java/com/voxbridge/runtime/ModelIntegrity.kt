package com.voxbridge.runtime

import java.io.File
import java.security.MessageDigest

/** Delivery manifest: SHA256, decimal byte length, relative POSIX path, one file per line. */
internal object ModelIntegrity {
    private const val MANIFEST = "integrity.sha256"
    private data class Entry(val hash: String, val size: Long, val path: String)

    fun validate(root: File, requiredPaths: List<String> = emptyList()): List<String> = try {
        val manifest = File(root, MANIFEST)
        require(manifest.isFile) { "Missing integrity.sha256; re-sync the model set" }
        val entries = manifest.readLines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split(' ', limit = 3)
            require(parts.size == 3 && parts[0].matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid integrity manifest" }
            val size = parts[1].toLong()
            val path = parts[2]
            require(size >= 0 && path.isNotBlank() && !path.startsWith('/') &&
                path.split('/').none { it == ".." || it == "." } && !path.contains('\\')) { "Unsafe integrity path" }
            require(File(root, path).canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Path escapes model root" }
            Entry(parts[0].lowercase(), size, path)
        }
        require(entries.isNotEmpty() && entries.map { it.path }.distinct().size == entries.size) { "Empty or duplicate manifest" }
        val covered = entries.map { it.path }.toSet()
        val uncovered = requiredPaths.filter { it !in covered }
        require(uncovered.isEmpty()) { "Files absent from integrity manifest: ${uncovered.joinToString()}" }
        val files = entries.map { File(root, it.path) }
        val sizes = entries.zip(files).filter { (entry, file) -> !file.isFile || file.length() != entry.size }
        require(sizes.isEmpty()) { "Missing/truncated model: ${sizes.joinToString { it.first.path }}" }
        val fingerprint = digest((hash(manifest) + entries.zip(files).joinToString("\n") {
            (entry, file) -> "${entry.path} ${file.length()} ${file.lastModified()}"
        }).toByteArray())
        val marker = File(root, ".verified-integrity")
        if (!marker.isFile || marker.readText() != fingerprint) {
            for ((entry, file) in entries.zip(files)) require(hash(file) == entry.hash) { "SHA-256 mismatch: ${entry.path}" }
            // Cache is only a startup optimization; failure to cache does not invalidate verified data.
            runCatching { marker.writeText(fingerprint) }
        }
        emptyList()
    } catch (e: Exception) { listOf(e.message ?: "Model integrity validation failed") }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}

package android.content.res

import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/** See AndroidContextStubs.kt — JVM stand-in backed by a real directory tree. */
open class AssetManager(private val root: File) {
    open fun list(path: String): Array<String>? {
        val f = File(root, path)
        if (!f.isDirectory) return emptyArray()
        return f.list()?.sortedArray()
    }
    open fun open(path: String): InputStream {
        val f = File(root, path)
        if (!f.isFile) throw FileNotFoundException(path)
        return f.inputStream()
    }
}

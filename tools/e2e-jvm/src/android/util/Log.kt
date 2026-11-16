package android.util

/**
 * JVM shim for android.util.Log — the ONLY android symbol the runtime Kotlin references.
 * On a plain JVM (tools/e2e-jvm, Colab verification) log lines go to stderr instead of
 * logcat, so WhisperAsr compiles and runs verbatim, byte-for-byte the same code that ships
 * in the APK.
 */
object Log {
    @JvmStatic fun i(tag: String, msg: String): Int = printLine("I", tag, msg)
    @JvmStatic fun w(tag: String, msg: String): Int = printLine("W", tag, msg)
    @JvmStatic fun e(tag: String, msg: String): Int = printLine("E", tag, msg)
    @JvmStatic fun d(tag: String, msg: String): Int = printLine("D", tag, msg)

    private fun printLine(level: String, tag: String, msg: String): Int {
        System.err.println("[$level/$tag] $msg")
        return 0
    }
}

package android.util
object Log {
    @JvmStatic fun i(tag: String, msg: String) {}
    @JvmStatic fun w(tag: String, msg: String) {}
    @JvmStatic fun e(tag: String, msg: String, t: Throwable? = null) {}
}

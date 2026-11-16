package com.voxbridge.core.translate

/**
 * EnViT5 sometimes wraps a whole translation in quote marks (4 of 100 EN->VI benchmark outputs,
 * e.g. `" Mười hai mét khối. Khi nào xe chở bê tông đến? "`). The quotes were shown on screen and
 * the speech chunker left a stray `"` on the last sentence, which Piper turned into noise.
 * Only a quote pair around the whole text is removed; quotes inside the text are kept.
 */
object MtOutputCleanup {
    private val wrapped = Regex("^\\s*[\"“”]\\s*([^\"“”]*?)\\s*[\"“”]\\s*$")

    fun clean(text: String): String = wrapped.matchEntire(text)?.groupValues?.get(1) ?: text
}

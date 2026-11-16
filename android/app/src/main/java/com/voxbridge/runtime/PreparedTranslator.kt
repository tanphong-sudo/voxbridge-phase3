package com.voxbridge.runtime

import com.voxbridge.core.model.Language
import com.voxbridge.core.translate.Translator

/** All preparation happens before PTT is enabled; inference never downloads model files. */
interface PreparedTranslator : Translator, AutoCloseable {
    fun prepare()
    fun preload(source: Language, target: Language): Long = 0L
}

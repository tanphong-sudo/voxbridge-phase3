package com.voxbridge.core.asr
import com.voxbridge.core.model.Language
data class TranscriptResult(
    val sourceLanguage: Language,
    val targetLanguage: Language,
    val text: String,
    val asrScore: Double,
    val noSpeechProbability: Double?,
    val asrScoreCalibrated: Boolean = true,
)

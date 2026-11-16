package com.voxbridge.core.normalize

import java.text.Normalizer

class VietnameseNormalizer {
    fun normalize(input: String): String {
        val lower = input.lowercase().trim()
        val noDiacritics = Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('đ', 'd')
        return noDiacritics
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

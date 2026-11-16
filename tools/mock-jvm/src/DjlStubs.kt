package com.sentencepiece

import java.nio.file.Path

enum class Scoring { FEWEST_SEGMENTS, HIGHEST_SCORE }

class SentencePieceAlgorithm(
    @Suppress("UNUSED_PARAMETER") normalize: Boolean,
    @Suppress("UNUSED_PARAMETER") scoring: Scoring,
)

/** Pure-Java SentencePiece4J facade stub used only by the no-model mock harness. */
class Model {
    private val idToToken = LinkedHashMap<Int, String>()
    private val tokenToId = LinkedHashMap<String, Int>()

    fun encodeNormalized(text: String, @Suppress("UNUSED_PARAMETER") algorithm: SentencePieceAlgorithm): List<Int> =
        text.split(' ', '\u00A0')
            .filter { it.isNotEmpty() }
            .map { idFor("▁$it") }

    fun getTokenById(id: Int): String = idToToken[id] ?: "<unk>"

    fun getIdForToken(token: String): Int = idFor(token)

    fun decodeSmart(ids: List<Int>): String = buildString {
        for (id in ids) {
            val piece = getTokenById(id)
            if (piece.startsWith("▁")) {
                if (isNotEmpty()) append(' ')
                append(piece.drop(1))
            } else {
                append(piece)
            }
        }
    }

    private fun idFor(token: String): Int = tokenToId.getOrPut(token) {
        val id = tokenToId.size
        idToToken[id] = token
        id
    }

    companion object {
        @JvmStatic
        fun parseFrom(@Suppress("UNUSED_PARAMETER") path: Path): Model = Model()
    }
}

package com.voxbridge.runtime

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import sentencepiece.SentencepieceModel
import java.nio.file.Files

class SpmTokenizerTest {
    private fun tokenizer(): SpmTokenizer {
        val root = Files.createTempDirectory("marian-tokenizer").toFile()
        root.deleteOnExit()
        val pieces = listOf("<unk>", "</s>", "<pad>", "▁Không", "▁không", "▁van", "▁Áp", "▁A", "▁a")
        val model = SentencepieceModel.ModelProto.newBuilder()
        pieces.forEachIndexed { index, piece ->
            model.addPieces(SentencepieceModel.ModelProto.SentencePiece.newBuilder()
                .setPiece(piece).setScore(-1f).setType(when (index) {
                    0 -> SentencepieceModel.ModelProto.SentencePiece.Type.UNKNOWN
                    1, 2 -> SentencepieceModel.ModelProto.SentencePiece.Type.CONTROL
                    else -> SentencepieceModel.ModelProto.SentencePiece.Type.NORMAL
                }))
        }
        val source = root.resolve("source.spm").apply { writeBytes(model.build().toByteArray()); deleteOnExit() }
        val target = root.resolve("target.spm").apply { writeBytes(model.build().toByteArray()); deleteOnExit() }
        val vocab = root.resolve("vocab.json").apply {
            writeText(JSONObject(pieces.withIndex().associate { it.value to it.index }).toString())
            deleteOnExit()
        }
        return SpmTokenizer(source, target, vocab)
    }

    @Test fun sourcePreservesCaseExpectedByMarianWeights() {
        tokenizer().use { tok ->
            assertArrayEquals(longArrayOf(3, 5, 1), tok.encode("Không van"))
            assertArrayEquals(longArrayOf(4, 5, 1), tok.encode("không van"))
            assertEquals("Không van", tok.decode(listOf(3, 5, 1)))
        }
    }

    @Test fun unicodeAndWhitespaceNormalizationDoNotLowercaseText() {
        tokenizer().use { tok ->
            assertArrayEquals(longArrayOf(6, 5, 1), tok.encode("  A\u0301p\tvan\n"))
            assertArrayEquals(longArrayOf(7, 5, 1), tok.encode("Ａ\u00a0van"))
        }
    }
}

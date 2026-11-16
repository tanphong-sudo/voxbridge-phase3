package com.voxbridge.core

import com.voxbridge.core.pipeline.SpeechChunker
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechChunkerTest {
    @Test
    fun `single sentence stays one chunk`() {
        assertEquals(listOf("Stop machine five now."), SpeechChunker.split("Stop machine five now."))
    }

    @Test
    fun `two sentences become two chunks so the first can play early`() {
        assertEquals(
            listOf("Dừng máy ép số bốn ngay.", "Có rò rỉ khí ở khu vực B."),
            SpeechChunker.split("Dừng máy ép số bốn ngay. Có rò rỉ khí ở khu vực B."),
        )
    }

    @Test
    fun `question and exclamation marks split too`() {
        assertEquals(
            listOf("Did you turn off the valve?", "Check it again before you leave!"),
            SpeechChunker.split("Did you turn off the valve? Check it again before you leave!"),
        )
    }

    @Test
    fun `decimal numbers and identifiers are never split`() {
        val text = "Pressure must stay below 3.5 bar on line A-12."
        assertEquals(listOf(text), SpeechChunker.split(text))
    }

    @Test
    fun `very short fragments merge into the following sentence`() {
        assertEquals(
            listOf("OK. Stop the conveyor belt now."),
            SpeechChunker.split("OK. Stop the conveyor belt now."),
        )
    }

    @Test
    fun `short trailing fragment merges into the previous chunk`() {
        assertEquals(
            listOf("Stop the conveyor belt now. OK."),
            SpeechChunker.split("Stop the conveyor belt now. OK."),
        )
    }

    @Test
    fun `whitespace is normalized and blank input yields nothing`() {
        assertEquals(listOf("Wear your helmet."), SpeechChunker.split("  Wear   your\nhelmet.  "))
        assertEquals(emptyList(), SpeechChunker.split("   "))
    }

    @Test
    fun `chunks rejoin to the original normalized text`() {
        val text = "Khẩn cấp! Dừng máy ép số bốn. Mọi người rời khỏi khu vực ngay lập tức. Gọi giám sát."
        assertEquals(text, SpeechChunker.split(text).joinToString(" "))
    }

    @Test
    fun `long first sentence splits at its first comma so audio starts sooner`() {
        assertEquals(
            listOf("I bought this headset two days ago,", "but it's not working on the left."),
            SpeechChunker.split("I bought this headset two days ago, but it's not working on the left."),
        )
    }

    @Test
    fun `long first sentence without comma splits before a clause conjunction`() {
        assertEquals(
            listOf("We wanted to split it up into three batches", "because the warehouse couldn't take all the goods at once."),
            SpeechChunker.split("We wanted to split it up into three batches because the warehouse couldn't take all the goods at once."),
        )
        assertEquals(
            listOf("Bên tôi muốn chia thành ba đợt", "vì kho không thể nhận toàn bộ hàng cùng lúc."),
            SpeechChunker.split("Bên tôi muốn chia thành ba đợt vì kho không thể nhận toàn bộ hàng cùng lúc."),
        )
    }

    @Test
    fun `short sentences, decimals and noun lists are not split`() {
        assertEquals(listOf("Excuse me, is this the train?"), SpeechChunker.split("Excuse me, is this the train?"))
        val decimal = "Áp suất phải giữ dưới 3,5 bar trên dây chuyền A-12 trong suốt ca làm việc."
        assertEquals(listOf(decimal), SpeechChunker.split(decimal))
        val list = "Bring the gloves and the helmet and the safety glasses to station four now."
        assertEquals(listOf(list), SpeechChunker.split(list))
    }

    @Test
    fun `only the first sentence is split and chunks still rejoin to the text`() {
        val text = "Please check the pressure gauge on line two, then report back to me. Wait here until the supervisor arrives, because the line is locked."
        val chunks = SpeechChunker.split(text)
        assertEquals(listOf("Please check the pressure gauge on line two,", "then report back to me.",
            "Wait here until the supervisor arrives, because the line is locked."), chunks)
        assertEquals(text, chunks.joinToString(" "))
    }
}

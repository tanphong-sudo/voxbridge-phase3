package com.voxbridge.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.io.StringReader
import java.nio.file.Files

class CsvRowsTest {
    @Test fun quotesCommasMultilineAndCrLf() {
        assertEquals(listOf(listOf("a", "b"), listOf("hi, there", "a\"b\nc")),
            csvRows(StringReader("a,b\r\n\"hi, there\",\"a\"\"b\nc\"\r\n")).toList())
    }
    @Test fun malformedQuotesRejected() {
        assertFailsWith<IllegalArgumentException> { csvRows(StringReader("\"unfinished")).toList() }
        assertFailsWith<IllegalArgumentException> { csvRows(StringReader("\"a\"bad,b")).toList() }
    }
    @Test fun trailingEmptyFieldAndBlankRecords() {
        assertEquals(listOf(listOf("a", "")), csvRows(StringReader("\n\na,\n")).toList())
    }
    @Test fun parserIsLazyAndRejectsOversizedFields() {
        assertEquals(listOf("a", "b"), csvRows(StringReader("a,b\n\"unfinished")).first())
        assertFailsWith<IllegalArgumentException> {
            csvRows(StringReader("x".repeat(1_048_577))).toList()
        }
    }
    @Test fun provenanceAndJsonNewlinesAreExplicit() {
        val directory = Files.createTempDirectory("synthetic-cli-test").toFile()
        try {
            val input = directory.resolve("input.csv")
            val output = directory.resolve("output.jsonl")
            input.writeText("file,asr_text,source_lang,target_lang\na,\"hello\nworld\",en,vi\n")
            main(arrayOf("--manifest", input.path, "--out", output.path))
            val lines = output.readLines()
            assertEquals(1, lines.size)
            kotlin.test.assertTrue(lines.single().contains("\"real_model_inference\":false"))
            kotlin.test.assertTrue(lines.single().contains("hello\\u000aworld"))
        } finally { directory.deleteRecursively() }
    }
}

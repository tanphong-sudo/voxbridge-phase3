package com.voxbridge.benchmark

import java.io.PushbackReader
import java.io.Reader

/** RFC 4180 quoted fields, escaped quotes and multiline records; one bounded row in memory. */
internal fun csvRows(source: Reader): Sequence<List<String>> = sequence {
    val reader = PushbackReader(source, 1)
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var afterQuote = false
    var started = false
    var recordChars = 0
    while (true) {
        val next = reader.read()
        if (next == -1) {
            require(!quoted) { "Unclosed CSV quote" }
            if (started || fields.isNotEmpty() || field.isNotEmpty()) yield(fields + field.toString())
            break
        }
        val c = next.toChar()
        recordChars++
        require(recordChars <= 4_194_304) { "CSV record exceeds 4 MiB character limit" }
        if (quoted) {
            if (c == '"') {
                val following = reader.read()
                if (following == '"'.code) field.append('"') else {
                    quoted = false
                    afterQuote = true
                    if (following != -1) reader.unread(following)
                }
            } else field.append(c)
        } else when (c) {
            '"' -> {
                require(field.isEmpty() && !afterQuote) { "Quote inside unquoted CSV field" }
                quoted = true
                started = true
            }
            ',' -> {
                fields += field.toString()
                field.setLength(0)
                afterQuote = false
                started = true
            }
            '\r', '\n' -> {
                if (c == '\r') {
                    val following = reader.read()
                    if (following != '\n'.code && following != -1) reader.unread(following)
                }
                if (started || fields.isNotEmpty() || field.isNotEmpty()) yield(fields + field.toString())
                fields.clear()
                field.setLength(0)
                afterQuote = false
                started = false
                recordChars = 0
            }
            else -> {
                require(!afterQuote) { "Unexpected character after CSV closing quote" }
                field.append(c)
                started = true
            }
        }
        require(field.length <= 1_048_576 && fields.size <= 1024) { "CSV record exceeds harness limits" }
    }
}

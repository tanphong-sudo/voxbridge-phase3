package com.voxbridge.core.normalize

/** Conservative text-only VI punctuation aid. Never changes lexical tokens or infers ASR words. */
object ViPunctuationRestorer {
    private val indirect = Regex("^(?:hãy |vui lòng |đừng |cấm |không |ai cũng |kiểm tra xem |.*(?:không biết |chưa biết |muốn biết |cần biết |tìm hiểu |chưa rõ |không rõ |muốn xác nhận |hỏi |trả lời |câu trả lời ))")
    private val wh = Regex("(?:^| )(?:bao lâu|bao nhiêu|mấy|tại sao|vì sao|làm sao|ở đâu|khi nào|gì|nào|cho ai|cùng ai)(?: |$)")
    private val distributive = Regex("(?:nào|gì|ai) cũng")
    private val ending = Regex("(?:^| )(?:không|chưa|à|hả|ư)$")
    private val alternative = Regex("^(?:anh|chị|bạn) (?:cần|muốn|uống|lấy|chọn) .+ hay .+")
    private val abbreviation = Regex("(?:^|\\s)(?:bs|ts|ths|pgs|gs|mr|mrs|dr)$", RegexOption.IGNORE_CASE)

    fun restore(text: String): String {
        val input = text.trim()
        if (input.isEmpty()) return ""
        val out = StringBuilder()
        var start = 0
        var i = 0
        while (i < input.length) {
            val c = input[i]
            val decimal = c == '.' && i > 0 && i + 1 < input.length && input[i - 1].isDigit() && input[i + 1].isDigit()
            val abbr = c == '.' && abbreviation.containsMatchIn(input.substring(start, i))
            if (c in ".?!" && !decimal && !abbr) {
                var end = i + 1
                while (end < input.length && input[end] in ".?!\"”’')]") end++
                if (end == input.length || input[end].isWhitespace()) {
                    out.append(sentence(input.substring(start, end)))
                    while (end < input.length && input[end].isWhitespace()) out.append(input[end++])
                    start = end
                    i = end - 1
                }
            }
            i++
        }
        if (start < input.length) out.append(sentence(input.substring(start)))
        return out.toString()
    }

    private fun sentence(text: String): String {
        val first = text.indexOfFirst { it.isLetter() }
        if (first < 0) return text
        var result = text.substring(0, first) + text[first].uppercaseChar() + text.substring(first + 1)
        val closing = result.takeLastWhile { it in "\"”’')]" }
        val body = result.dropLast(closing.length)
        // Explicit question/exclamation/ellipsis already carries speaker intent.
        if (body.endsWith('?') || body.endsWith('!') || body.endsWith("..")) return result
        val words = body.removeSuffix(".").trim().lowercase().trimStart('"', '“', '‘', '\'')
        val question = !indirect.containsMatchIn(words) && !words.contains("cũng được") && !words.contains("đều được") && !distributive.containsMatchIn(words) &&
            (words.startsWith("ai ") || wh.containsMatchIn(words) || ending.containsMatchIn(words) || alternative.matches(words))
        result = body.removeSuffix(".") + (if (question) "?" else ".") + closing
        return result
    }
}

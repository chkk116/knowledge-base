package com.kb.ocr

// 数字后处理（与 python/ocr_runtime/postprocess.py、web/src/postprocess.ts 一致）
private val CONFUSABLE = mapOf(
    'O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0',
    'l' to '1', 'I' to '1', 'i' to '1', '|' to '1',
    'Z' to '2', 'z' to '2',
    'S' to '5', 's' to '5',
    'B' to '8', 'g' to '8',
)

private val PATTERNS = listOf(
    Regex("1[3-9]\\d{1,2}[-\\s]?\\d{4}[-\\s]?\\d{4}"),
    Regex("¥?\\s?\\d{1,3}(,\\d{3})*(\\.\\d{1,2})?"),
    Regex("\\d{4}[-/年.]\\d{1,2}[-/月.]\\d{1,2}"),
    Regex("(?<![A-Za-z])\\d{6,}(?![A-Za-z])"),
    Regex("\\d{3,4}-\\d{3,4}-\\d{3,4}"),
)

fun fixNumbers(text: String): String {
    if (text.isEmpty()) return text
    var out = text
    for (pat in PATTERNS) {
        out = pat.replace(out) { m -> m.value.map { CONFUSABLE[it] ?: it }.joinToString("") }
    }
    return out
}

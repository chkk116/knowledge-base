package com.kb.ocr

import java.io.File

// 与 Python 基准、Web 对齐测试完全相同的指标：字符级准确率 = 1 - CER（去空白归一化）
private fun norm(s: String): String = s.filter { !it.isWhitespace() }

private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    for (i in a.indices) {
        val cur = IntArray(b.length + 1)
        cur[0] = i + 1
        for (j in b.indices) {
            val cost = if (a[i] == b[j]) 0 else 1
            cur[j + 1] = minOf(prev[j + 1] + 1, cur[j] + 1, prev[j] + cost)
        }
        prev = cur
    }
    return prev[b.length]
}

private fun charAcc(ref: String, pred: String): Double {
    val r = norm(ref); val p = norm(pred)
    if (r.isEmpty()) return 1.0
    return maxOf(0.0, 1.0 - levenshtein(r, p).toDouble() / r.length)
}

private val CAT_LABEL = mapOf(
    "clean_line" to "干净单行", "paragraph" to "多行段落", "scanned" to "扫描噪声",
    "two_column" to "双栏", "mixed" to "中英数混排",
)
private val PYTHON_RUNTIME = mapOf(
    "clean_line" to 0.929, "paragraph" to 0.973, "scanned" to 0.984,
    "two_column" to 0.946, "mixed" to 0.746,
)
private const val PYTHON_RUNTIME_OVERALL = 0.918
private val WEB_RUNTIME = mapOf(
    "clean_line" to 0.9554, "paragraph" to 0.9727, "scanned" to 0.976,
    "two_column" to 0.9257, "mixed" to 0.7391,
)

data class Gt(val file: String, val text: String, val category: String)

// 极简 JSON 数组解析（仅用于 ground_truth.json 这类扁平结构，避免引入 JSON 依赖）
private fun parseGt(json: String): List<Gt> {
    val out = ArrayList<Gt>()
    val blocks = Regex("\\{[^}]*}").findAll(json)
    fun field(b: String, key: String): String =
        Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(b)?.groupValues?.get(1) ?: ""
    for (m in blocks) {
        val b = m.value
        out.add(Gt(field(b, "file"), field(b, "text").replace("\\u", "\\u"), field(b, "category")))
    }
    return out
}

fun main() {
    val dataset = File(System.getProperty("kb.ocr.dataset"))
    val modelDir = File(System.getProperty("kb.ocr.models"))
    val gtFile = File(dataset, "ground_truth.json")
    val gt = parseGt(gtFile.readText(Charsets.UTF_8))
    // 中文为 BMP 字符，JSON 里直接是明文（无转义），无需额外解码
    println("样本数: ${gt.size}")

    println("加载 Kotlin 运行时（onnxruntime JVM）…")
    val full = OcrEngine.fromDirectory(modelDir, OcrOptions(), threads = 1)
    val bare = OcrEngine.fromDirectory(modelDir, OcrOptions(columnAware = false, postprocess = false), threads = 1)

    data class Agg(var chars: Int = 0, var accSum: Double = 0.0)
    val perFull = HashMap<String, Agg>(); val perBare = HashMap<String, Agg>()
    var fullChars = 0; var fullAcc = 0.0; var bareChars = 0; var bareAcc = 0.0

    // warm-up
    full.recognize(loadRawImage(File(dataset, gt[0].file).absolutePath))

    for (item in gt) {
        val path = File(dataset, item.file).absolutePath
        val img = loadRawImage(path)
        val rf = full.recognize(img)
        val rb = bare.recognize(img)
        val aFull = charAcc(item.text, rf.text)
        val aBare = charAcc(item.text, rb.text)
        val c = norm(item.text).length
        val f = perFull.getOrPut(item.category) { Agg() }
        val b = perBare.getOrPut(item.category) { Agg() }
        f.chars += c; f.accSum += aFull * c
        b.chars += c; b.accSum += aBare * c
        fullChars += c; fullAcc += aFull * c
        bareChars += c; bareAcc += aBare * c
        println("  ${item.file.padEnd(14)} bare=${"%.1f".format(aBare * 100)}%  runtime=${"%.1f".format(aFull * 100)}%  \"${rf.text.replace("\n", " ").take(32)}\"")
    }

    val overallFull = fullAcc / fullChars
    val overallBare = bareAcc / bareChars
    println("\n=== 分类对比（字符准确率）===")
    println("类别        裸Kotlin  Kotlin运行时  Web运行时  Python运行时")
    val cats = listOf("clean_line", "paragraph", "scanned", "two_column", "mixed")
    val json = StringBuilder("{\n  \"overall_full\": ${"%.4f".format(overallFull)},\n  \"overall_bare\": ${"%.4f".format(overallBare)},\n  \"per_category\": {\n")
    for ((idx, cat) in cats.withIndex()) {
        val f = perFull[cat]!!.accSum / perFull[cat]!!.chars
        val b = perBare[cat]!!.accSum / perBare[cat]!!.chars
        val web = WEB_RUNTIME[cat]!!; val py = PYTHON_RUNTIME[cat]!!
        println("${CAT_LABEL[cat]!!.padEnd(10)} ${"%.1f".format(b * 100).padStart(7)}%  ${"%.1f".format(f * 100).padStart(10)}%  ${"%.1f".format(web * 100).padStart(8)}%  ${"%.1f".format(py * 100).padStart(10)}%")
        json.append("    \"$cat\": {\"bare\": ${"%.4f".format(b)}, \"runtime\": ${"%.4f".format(f)}, \"web_runtime\": $web, \"python_runtime\": $py}")
        if (idx < cats.size - 1) json.append(",")
        json.append("\n")
    }
    json.append("  }\n}\n")
    println("\n整体：裸Kotlin ${"%.1f".format(overallBare * 100)}%  Kotlin运行时 ${"%.1f".format(overallFull * 100)}%  Python运行时 ${"%.1f".format(PYTHON_RUNTIME_OVERALL * 100)}%  差异 ${"%.1f".format((overallFull - PYTHON_RUNTIME_OVERALL) * 100)}pt")
    val outFile = File("alignment_results.json")
    outFile.writeText(json.toString(), Charsets.UTF_8)
    println("结果已写入 ${outFile.absolutePath}")
}

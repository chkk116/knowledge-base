package com.kb.ocr

import kotlin.math.ceil

data class RecConfig(
    val imageHeight: Int = 48,
    val channels: Int = 3,
    val batchNum: Int = 6,
)

/** 字符表：['blank', ...chars, ' ']，与 rec 输出维度一致（6625）。 */
fun buildCharacterList(metaChars: String): List<String> {
    val lines = metaChars.split("\n").map { it.trimEnd('\r') }
    return listOf("blank") + lines + listOf(" ")
}

data class RecOut(val data: FloatArray, val width: Int)

/**
 * 识别前处理：resize 到 (resizedW, 48)，归一化 [-1,1]，CHW 右填充。
 * 关键：填充宽度是动态 batchW = int(48 * maxWhRatio)，不是固定 320（否则长行被静默截断）。
 */
fun recPreprocess(img: BgrImage, maxWhRatio: Double, cfg: RecConfig): RecOut {
    val c = cfg.channels; val ih = cfg.imageHeight
    val w = img.width; val h = img.height
    val ratio = w.toDouble() / h
    val imgW = (ih * maxWhRatio).toInt() // int() 向下取整
    var resizedW = ceil(ih * ratio).toInt()
    if (resizedW > imgW) resizedW = imgW
    if (resizedW < 1) resizedW = 1
    val resized = resizeBgr(img.bgr, w, h, resizedW, ih)
    val out = FloatArray(c * ih * imgW)
    for (y in 0 until ih) {
        for (x in 0 until resizedW) {
            for (cc in 0 until c) {
                val v = resized[(y * resizedW + x) * 3 + cc] / 255.0
                out[cc * ih * imgW + y * imgW + x] = (v * 2 - 1).toFloat()
            }
        }
    }
    return RecOut(out, imgW)
}

data class RecLine(val text: String, val score: Double)

/** CTC 贪婪解码：跳过 blank(0) + 去重复。 */
fun ctcDecode(preds: FloatArray, n: Int, t: Int, classes: Int, charList: List<String>): List<RecLine> {
    val res = ArrayList<RecLine>(n)
    for (b in 0 until n) {
        val sb = StringBuilder()
        var conf = 0.0; var cnt = 0; var prev = -1
        for (i in 0 until t) {
            val base = (b * t + i) * classes
            var maxI = 0; var maxV = Float.NEGATIVE_INFINITY
            for (c in 0 until classes) {
                val v = preds[base + c]
                if (v > maxV) { maxV = v; maxI = c }
            }
            if (maxI == 0) { prev = 0; continue }
            if (maxI == prev) { prev = maxI; continue }
            if (maxI < charList.size) sb.append(charList[maxI])
            conf += maxV; cnt++
            prev = maxI
        }
        res.add(RecLine(sb.toString(), if (cnt > 0) conf / cnt else 0.0))
    }
    return res
}

/** 批量识别：按宽高比排序加速，分批送入会话。 */
fun recognize(session: OcrSession, crops: List<BgrImage>, charList: List<String>, cfg: RecConfig): List<RecLine> {
    if (crops.isEmpty()) return emptyList()
    val c = cfg.channels; val ih = cfg.imageHeight
    val ratios = crops.map { it.width.toDouble() / it.height }
    val indices = ratios.indices.sortedBy { ratios[it] }
    val res = Array(crops.size) { RecLine("", 0.0) }
    var beg = 0
    while (beg < crops.size) {
        val end = minOf(crops.size, beg + cfg.batchNum)
        var maxWh = 0.0
        for (i in beg until end) maxWh = maxOf(maxWh, ratios[indices[i]])
        val batch = ArrayList<RecOut>()
        for (i in beg until end) batch.add(recPreprocess(crops[indices[i]], maxWh, cfg))
        val n = batch.size
        val batchW = batch[0].width
        val tensor = FloatArray(n * c * ih * batchW)
        for (i in 0 until n) System.arraycopy(batch[i].data, 0, tensor, i * c * ih * batchW, batch[i].data.size)
        val (data, dims) = session.run(tensor, longArrayOf(n.toLong(), c.toLong(), ih.toLong(), batchW.toLong()))
        val t = dims[1].toInt(); val classes = dims[2].toInt()
        val dec = ctcDecode(data, n, t, classes, charList)
        for (r in dec.indices) res[indices[beg + r]] = dec[r]
        beg = end
    }
    return res.toList()
}

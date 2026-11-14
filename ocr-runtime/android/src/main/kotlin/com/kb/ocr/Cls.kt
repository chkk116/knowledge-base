package com.kb.ocr

import kotlin.math.ceil
import kotlin.math.min

data class ClsConfig(
    val imageHeight: Int = 48,
    val imageWidth: Int = 192,
    val channels: Int = 3,
    val batchNum: Int = 6,
    val thresh: Double = 0.9,
    val labels: List<String> = listOf("0", "180"),
)

fun clsPreprocess(img: BgrImage, cfg: ClsConfig): FloatArray {
    val c = cfg.channels; val ih = cfg.imageHeight; val iw = cfg.imageWidth
    val w = img.width; val h = img.height
    val ratio = w.toDouble() / h
    var resizedW = min(ceil(ih * ratio).toInt(), iw)
    if (resizedW < 1) resizedW = 1
    val resized = resizeBgr(img.bgr, w, h, resizedW, ih)
    val out = FloatArray(c * ih * iw)
    for (y in 0 until ih) {
        for (x in 0 until resizedW) {
            for (cc in 0 until c) {
                val v = resized[(y * resizedW + x) * 3 + cc] / 255.0
                out[cc * ih * iw + y * iw + x] = (v * 2 - 1).toFloat()
            }
        }
    }
    return out
}

private fun rotate180Bgr(src: IntArray, w: Int, h: Int): IntArray {
    val out = IntArray(w * h * 3)
    for (y in 0 until h) {
        for (x in 0 until w) {
            for (c in 0..2) out[(y * w + x) * 3 + c] = src[((h - 1 - y) * w + (w - 1 - x)) * 3 + c]
        }
    }
    return out
}

/** 方向分类，返回（可能旋转后的）裁剪图。 */
fun classify(session: OcrSession, crops: List<BgrImage>, cfg: ClsConfig): List<BgrImage> {
    if (crops.isEmpty()) return crops
    val c = cfg.channels; val ih = cfg.imageHeight; val iw = cfg.imageWidth
    val out = ArrayList<BgrImage>(crops)
    var beg = 0
    while (beg < crops.size) {
        val end = minOf(crops.size, beg + cfg.batchNum)
        val n = end - beg
        val tensor = FloatArray(n * c * ih * iw)
        for (i in 0 until n) {
            val d = clsPreprocess(crops[beg + i], cfg)
            System.arraycopy(d, 0, tensor, i * c * ih * iw, d.size)
        }
        val (data, _) = session.run(tensor, longArrayOf(n.toLong(), c.toLong(), ih.toLong(), iw.toLong()))
        for (i in 0 until n) {
            val p0 = data[i * 2]; val p1 = data[i * 2 + 1]
            val idx = if (p1 > p0) 1 else 0
            val score = (if (idx == 1) p1 else p0).toDouble()
            val label = cfg.labels[idx]
            if (label == "180" && score > cfg.thresh) {
                val crop = crops[beg + i]
                out[beg + i] = BgrImage(crop.width, crop.height, rotate180Bgr(crop.bgr, crop.width, crop.height))
            }
        }
        beg = end
    }
    return out
}

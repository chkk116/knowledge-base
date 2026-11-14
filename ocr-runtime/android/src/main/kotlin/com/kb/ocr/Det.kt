package com.kb.ocr

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class DetConfig(
    val limitSideLen: Int = 736,
    val limitType: String = "min",
    val thresh: Double = 0.3,
    val boxThresh: Double = 0.5,
    val maxCandidates: Int = 1000,
    val unclipRatio: Double = 1.6,
    val useDilation: Boolean = true,
)

/** 检测前处理：按 limit_side_len 缩放（32 倍数），归一化 (x/255 - mean)/std，布局 CHW。 */
fun detPreprocess(raw: RawImage, cfg: DetConfig): DetInput {
    val w = raw.width; val h = raw.height
    val bgr = rgbaToBgr(raw.rgba, w, h)
    val (rh, rw, ratioH, ratioW) = detResizeShape(h, w, cfg.limitSideLen, cfg.limitType)
    val resized = resizeBgr(bgr, w, h, rw, rh)
    val mean = doubleArrayOf(0.485, 0.456, 0.406)
    val std = doubleArrayOf(0.229, 0.224, 0.225)
    val chw = FloatArray(3 * rh * rw)
    for (y in 0 until rh) {
        for (x in 0 until rw) {
            for (c in 0..2) {
                val v = resized[(y * rw + x) * 3 + c] / 255.0
                chw[c * rh * rw + y * rw + x] = ((v - mean[c]) / std[c]).toFloat()
            }
        }
    }
    return DetInput(chw, rh, rw, ratioH, ratioW, h, w)
}

data class DetInput(
    val tensor: FloatArray, val rh: Int, val rw: Int,
    val ratioH: Double, val ratioW: Double, val h: Int, val w: Int,
)

/** cv2.dilate 2x2：锚点 (1,1)，看左上 2x2 窗口。 */
private fun dilate2x2(mask: ByteArray, w: Int, h: Int): ByteArray {
    val out = ByteArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var v: Byte = 0
            outer@ for (dy in -1..0) {
                for (dx in -1..0) {
                    val xx = x + dx; val yy = y + dy
                    if (xx >= 0 && yy >= 0 && mask[yy * w + xx].toInt() != 0) { v = 1; break@outer }
                }
            }
            out[y * w + x] = v
        }
    }
    return out
}

/** 多边形区域内 pred 均值（等价 cv2.mean(pred, mask)）。 */
private fun boxScoreFast(pred: FloatArray, w: Int, h: Int, box: List<Pt>): Double {
    var xmin = Double.POSITIVE_INFINITY; var xmax = Double.NEGATIVE_INFINITY
    var ymin = Double.POSITIVE_INFINITY; var ymax = Double.NEGATIVE_INFINITY
    for (p in box) {
        if (p[0] < xmin) xmin = p[0]; if (p[0] > xmax) xmax = p[0]
        if (p[1] < ymin) ymin = p[1]; if (p[1] > ymax) ymax = p[1]
    }
    val xi = max(0, floor(xmin).toInt()); val xa = min(w - 1, ceil(xmax).toInt())
    val yi = max(0, floor(ymin).toInt()); val ya = min(h - 1, ceil(ymax).toInt())
    if (xa < xi || ya < yi) return 0.0
    val poly = box.map { pt(kotlin.math.round(it[0]) - xi, kotlin.math.round(it[1]) - yi) }
    var sum = 0.0; var cnt = 0
    for (y in yi..ya) {
        for (x in xi..xa) {
            if (pointInPoly((x - xi).toDouble(), (y - yi).toDouble(), poly)) {
                sum += pred[y * w + x]; cnt++
            }
        }
    }
    return if (cnt > 0) sum / cnt else 0.0
}

data class DetOutput(val boxes: List<Array<DoubleArray>>, val scores: DoubleArray)

/** DB 后处理：pred 为 [H,W] 概率图，返回原图坐标系下的文本框。 */
fun detPostprocess(pred: FloatArray, w: Int, h: Int, destW: Int, destH: Int, cfg: DetConfig): DetOutput {
    val seg = ByteArray(w * h)
    for (i in 0 until w * h) seg[i] = if (pred[i] > cfg.thresh) 1 else 0
    val mask = if (cfg.useDilation) dilate2x2(seg, w, h) else seg
    val comps = connectedComponents(mask, w, h)
    val boxes = ArrayList<Array<DoubleArray>>()
    val scores = ArrayList<Double>()
    val limit = min(comps.size, cfg.maxCandidates)
    for (ci in 0 until limit) {
        val (box, sside) = getMiniBoxes(comps[ci])
        if (sside < 3) continue
        val score = boxScoreFast(pred, w, h, box)
        if (cfg.boxThresh > score) continue
        val expanded = unclip(box, cfg.unclipRatio)
        val (box2, sside2) = getMiniBoxes(expanded)
        if (sside2 < 5) continue
        val scaled = Array(4) { i ->
            doubleArrayOf(
                max(0.0, min(destW.toDouble(), npRound(box2[i][0] * destW / w))),
                max(0.0, min(destH.toDouble(), npRound(box2[i][1] * destH / h))),
            )
        }
        boxes.add(scaled)
        scores.add(score)
    }
    return DetOutput(boxes, scores.toDoubleArray())
}

/** 复刻 sorted_boxes：按 (y0,x0) 排序，相邻 y 差 <10 且 x 逆序则交换。 */
fun sortedBoxes(boxes: List<Array<DoubleArray>>): List<Array<DoubleArray>> {
    val sorted = boxes.sortedWith(compareBy<Array<DoubleArray>> { it[0][1] }.thenBy { it[0][0] }).toMutableList()
    for (i in 0 until sorted.size - 1) {
        if (kotlin.math.abs(sorted[i + 1][0][1] - sorted[i][0][1]) < 10 && sorted[i + 1][0][0] < sorted[i][0][0]) {
            val tmp = sorted[i]; sorted[i] = sorted[i + 1]; sorted[i + 1] = tmp
        }
    }
    return sorted
}

/** 复刻 filter_tag_det_res：顺时针排序 + 裁剪到图像内 + 过滤过小的框。 */
fun filterDetBoxes(boxes: List<Array<DoubleArray>>, h: Int, w: Int): List<Array<DoubleArray>> {
    val out = ArrayList<Array<DoubleArray>>()
    for (box in boxes) {
        val ordered = orderPointsClockwise(box.toList())
        val clip = Array(4) { i ->
            doubleArrayOf(
                max(0.0, min(w - 1.0, ordered[i][0])),
                max(0.0, min(h - 1.0, ordered[i][1])),
            )
        }
        val rw = kotlin.math.round(hypot(clip[0][0] - clip[1][0], clip[0][1] - clip[1][1]))
        val rh = kotlin.math.round(hypot(clip[0][0] - clip[3][0], clip[0][1] - clip[3][1]))
        if (rw <= 3 || rh <= 3) continue
        out.add(clip)
    }
    return out
}

/** 运行检测会话。 */
fun detect(session: OcrSession, raw: RawImage, cfg: DetConfig): DetOutput {
    val input = detPreprocess(raw, cfg)
    val (data, dims) = session.run(input.tensor, longArrayOf(1, 3, input.rh.toLong(), input.rw.toLong()))
    val h = dims[2].toInt(); val w = dims[3].toInt()
    val map = FloatArray(h * w) { data[it] }
    return detPostprocess(map, w, h, input.w, input.h, cfg)
}

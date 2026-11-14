package com.kb.ocr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** RGBA -> BGR（cv2 约定）。 */
fun rgbaToBgr(rgba: IntArray, w: Int, h: Int): IntArray {
    val out = IntArray(w * h * 3)
    for (i in 0 until w * h) {
        out[i * 3] = rgba[i * 4 + 2]
        out[i * 3 + 1] = rgba[i * 4 + 1]
        out[i * 3 + 2] = rgba[i * 4]
    }
    return out
}

/** 双线性缩放 BGR（近似 cv2 INTER_LINEAR）。 */
fun resizeBgr(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
    val out = IntArray(dw * dh * 3)
    if (dw == 0 || dh == 0) return out
    val xr = sw.toDouble() / dw
    val yr = sh.toDouble() / dh
    for (y in 0 until dh) {
        val sy = min(sh - 1, (y * yr).toInt())
        val sy1 = min(sh - 1, sy + 1)
        val fy = y * yr - sy
        for (x in 0 until dw) {
            val sx = min(sw - 1, (x * xr).toInt())
            val sx1 = min(sw - 1, sx + 1)
            val fx = x * xr - sx
            for (c in 0..2) {
                val p00 = src[(sy * sw + sx) * 3 + c].toDouble()
                val p01 = src[(sy * sw + sx1) * 3 + c].toDouble()
                val p10 = src[(sy1 * sw + sx) * 3 + c].toDouble()
                val p11 = src[(sy1 * sw + sx1) * 3 + c].toDouble()
                val top = p00 + (p01 - p00) * fx
                val bot = p10 + (p11 - p10) * fx
                out[(y * dw + x) * 3 + c] = clamp255(top + (bot - top) * fy)
            }
        }
    }
    return out
}

fun rawToBgr(raw: RawImage): BgrImage =
    BgrImage(raw.width, raw.height, rgbaToBgr(raw.rgba, raw.width, raw.height))

data class DetShape(val rh: Int, val rw: Int, val ratioH: Double, val ratioW: Double)

/** 限制短边为 limitSideLen（min 模式），并取整到 32 的倍数。 */
fun detResizeShape(h: Int, w: Int, limitSideLen: Int, limitType: String): DetShape {
    val ratio = if (limitType == "max") {
        val m = max(h, w)
        if (m > limitSideLen) limitSideLen.toDouble() / m else 1.0
    } else {
        val m = min(h, w)
        if (m < limitSideLen) limitSideLen.toDouble() / m else 1.0
    }
    var rh = (kotlin.math.round(h * ratio / 32) * 32).roundToInt()
    var rw = (kotlin.math.round(w * ratio / 32) * 32).roundToInt()
    if (rh <= 0) rh = 32
    if (rw <= 0) rw = 32
    return DetShape(rh, rw, rh.toDouble() / h, rw.toDouble() / w)
}

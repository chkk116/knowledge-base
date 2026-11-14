package com.kb.ocr

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** 透视裁剪（复刻 get_rotate_crop_image）：把检测四边形裁成正立矩形，过高/过窄时旋转 90°。 */
fun getRotateCropImage(src: BgrImage, points: Array<DoubleArray>): BgrImage {
    val p = Array(4) { i -> pt(points[i][0], points[i][1]) }
    fun d(a: Pt, b: Pt) = hypot(b[0] - a[0], b[1] - a[1])
    val cropW = max(d(p[0], p[1]), d(p[2], p[3])).roundToInt()
    val cropH = max(d(p[0], p[3]), d(p[1], p[2])).roundToInt()
    if (cropW <= 0 || cropH <= 0) return BgrImage(0, 0, IntArray(0))
    val dst = listOf(pt(0, 0), pt(cropW, 0), pt(cropW, cropH), pt(0, cropH))
    val hMat = getPerspectiveTransform(p.toList(), dst)
    var bgr = warpPerspective(src.bgr, src.width, src.height, hMat, cropW, cropH)
    var w = cropW; var h = cropH
    if (h.toDouble() / w >= 1.5) {
        bgr = rot90Bgr(bgr, w, h)
        val oldW = w; w = h; h = oldW
    }
    return BgrImage(w, h, bgr)
}

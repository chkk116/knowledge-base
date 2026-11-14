package com.kb.ocr

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

typealias Pt = DoubleArray

fun pt(x: Double, y: Double): Pt = doubleArrayOf(x, y)
fun pt(x: Number, y: Number): Pt = doubleArrayOf(x.toDouble(), y.toDouble())

private fun cross(o: Pt, a: Pt, b: Pt): Double =
    (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

/** Andrew monotone-chain 凸包（逆时针）。 */
fun convexHull(pts: List<Pt>): List<Pt> {
    val p = pts.sortedWith(compareBy<Pt> { it[0] }.thenBy { it[1] })
    if (p.size < 3) return p
    val lower = ArrayList<Pt>()
    for (q in p) {
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], q) <= 0) lower.removeAt(lower.size - 1)
        lower.add(q)
    }
    val upper = ArrayList<Pt>()
    for (i in p.indices.reversed()) {
        val q = p[i]
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], q) <= 0) upper.removeAt(upper.size - 1)
        upper.add(q)
    }
    lower.removeAt(lower.size - 1)
    upper.removeAt(upper.size - 1)
    return lower + upper
}

data class MinRect(val cx: Double, val cy: Double, val w: Double, val h: Double, val corners: List<Pt>)

/** 旋转卡壳求最小面积外接矩形（等价 cv2.minAreaRect + boxPoints）。 */
fun minAreaRect(pts: List<Pt>): MinRect {
    val hull = convexHull(pts)
    val n = hull.size
    if (n == 0) return MinRect(0.0, 0.0, 0.0, 0.0, emptyList())
    if (n == 1) {
        val a = hull[0]
        return MinRect(a[0], a[1], 0.0, 0.0, listOf(a, a, a, a))
    }
    if (n == 2) {
        val a = hull[0]; val b = hull[1]
        return MinRect((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, hypot(b[0] - a[0], b[1] - a[1]), 0.0,
            listOf(a, b, b, a))
    }
    var minArea = Double.POSITIVE_INFINITY
    var bu = doubleArrayOf(1.0, 0.0)
    var bv = doubleArrayOf(0.0, 1.0)
    var bMinU = 0.0; var bMaxU = 0.0; var bMinV = 0.0; var bMaxV = 0.0
    for (i in 0 until n) {
        val p1 = hull[i]; val p2 = hull[(i + 1) % n]
        val dx = p2[0] - p1[0]; val dy = p2[1] - p1[1]
        val len = hypot(dx, dy)
        if (len == 0.0) continue
        val ux = dx / len; val uy = dy / len
        val vx = -uy; val vy = ux
        var minU = Double.POSITIVE_INFINITY; var maxU = Double.NEGATIVE_INFINITY
        var minV = Double.POSITIVE_INFINITY; var maxV = Double.NEGATIVE_INFINITY
        for (p in hull) {
            val pu = p[0] * ux + p[1] * uy
            val pv = p[0] * vx + p[1] * vy
            if (pu < minU) minU = pu; if (pu > maxU) maxU = pu
            if (pv < minV) minV = pv; if (pv > maxV) maxV = pv
        }
        val area = (maxU - minU) * (maxV - minV)
        if (area < minArea) {
            minArea = area
            bu = doubleArrayOf(ux, uy); bv = doubleArrayOf(vx, vy)
            bMinU = minU; bMaxU = maxU; bMinV = minV; bMaxV = maxV
        }
    }
    val centerU = (bMinU + bMaxU) / 2
    val centerV = (bMinV + bMaxV) / 2
    val cx = centerU * bu[0] + centerV * bv[0]
    val cy = centerU * bu[1] + centerV * bv[1]
    val hw = (bMaxU - bMinU) / 2
    val hh = (bMaxV - bMinV) / 2
    val c: (Double, Double) -> Pt = { su, sv ->
        pt(cx + su * hw * bu[0] + sv * hh * bv[0], cy + su * hw * bu[1] + sv * hh * bv[1])
    }
    return MinRect(cx, cy, bMaxU - bMinU, bMaxV - bMinV,
        listOf(c(-1.0, -1.0), c(1.0, -1.0), c(1.0, 1.0), c(-1.0, 1.0)))
}

/** 复刻 get_mini_boxes：minAreaRect 后按 x 排序得到 [tl, tr, br, bl]。 */
fun getMiniBoxes(points: List<Pt>): Pair<List<Pt>, Double> {
    val r = minAreaRect(points)
    val sorted = r.corners.sortedBy { it[0] }
    val i1: Int; val i4: Int
    if (sorted[1][1] > sorted[0][1]) { i1 = 0; i4 = 1 } else { i1 = 1; i4 = 0 }
    val i2: Int; val i3: Int
    if (sorted[3][1] > sorted[2][1]) { i2 = 2; i3 = 3 } else { i2 = 3; i3 = 2 }
    return listOf(sorted[i1], sorted[i2], sorted[i3], sorted[i4]) to min(r.w, r.h)
}

fun polyArea(poly: List<Pt>): Double {
    var s = 0.0
    for (i in poly.indices) {
        val a = poly[i]; val b = poly[(i + 1) % poly.size]
        s += a[0] * b[1] - b[0] * a[1]
    }
    return abs(s) / 2
}

fun polyPerimeter(poly: List<Pt>): Double {
    var s = 0.0
    for (i in poly.indices) {
        val a = poly[i]; val b = poly[(i + 1) % poly.size]
        s += hypot(b[0] - a[0], b[1] - a[1])
    }
    return s
}

fun pointInPoly(x: Double, y: Double, poly: List<Pt>): Boolean {
    var inside = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val xi = poly[i][0]; val yi = poly[i][1]
        val xj = poly[j][0]; val yj = poly[j][1]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

/** 保证 [tl, tr, br, bl] 顺序。 */
fun orderPointsClockwise(box: List<Pt>): List<Pt> {
    val xs = box.sortedBy { it[0] }
    val left = xs.take(2).sortedBy { it[1] }
    val right = xs.drop(2).sortedBy { it[1] }
    return listOf(left[0], right[0], right[1], left[1])
}

/** 解 4 点透视变换 H（src -> dst），3x3 行主序。 */
fun getPerspectiveTransform(src: List<Pt>, dst: List<Pt>): DoubleArray {
    val a = Array(8) { DoubleArray(8) }
    val b = DoubleArray(8)
    for (i in 0..3) {
        val x = src[i][0]; val y = src[i][1]
        val u = dst[i][0]; val v = dst[i][1]
        a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y); b[2 * i] = u
        a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y); b[2 * i + 1] = v
    }
    val h = solve8x8(a, b)
    return doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
}

private fun solve8x8(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
    val n = 8
    val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
    for (col in 0 until n) {
        var piv = col
        for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
        val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
        val d = m[col][col]
        if (abs(d) < 1e-12) continue
        for (c in col..n) m[col][c] /= d
        for (r in 0 until n) {
            if (r == col) continue
            val f = m[r][col]
            if (f == 0.0) continue
            for (c in col..n) m[r][c] -= f * m[col][c]
        }
    }
    return DoubleArray(n) { m[it][n] }
}

/** 透视变换 + 双线性采样（BORDER_REPLICATE）。 */
fun warpPerspective(src: IntArray, sw: Int, sh: Int, h: DoubleArray, dw: Int, dh: Int): IntArray {
    val inv = invert3x3(h)
    val out = IntArray(dw * dh * 3)
    for (y in 0 until dh) {
        for (x in 0 until dw) {
            val w = inv[6] * x + inv[7] * y + inv[8]
            val sx = (inv[0] * x + inv[1] * y + inv[2]) / w
            val sy = (inv[3] * x + inv[4] * y + inv[5]) / w
            val cx = max(0.0, min(sw - 1.0, sx))
            val cy = max(0.0, min(sh - 1.0, sy))
            val x0 = kotlin.math.floor(cx).toInt(); val y0 = kotlin.math.floor(cy).toInt()
            val x1 = min(sw - 1, x0 + 1); val y1 = min(sh - 1, y0 + 1)
            val fx = cx - x0; val fy = cy - y0
            for (c in 0..2) {
                val p00 = src[(y0 * sw + x0) * 3 + c].toDouble()
                val p01 = src[(y0 * sw + x1) * 3 + c].toDouble()
                val p10 = src[(y1 * sw + x0) * 3 + c].toDouble()
                val p11 = src[(y1 * sw + x1) * 3 + c].toDouble()
                val top = p00 + (p01 - p00) * fx
                val bot = p10 + (p11 - p10) * fx
                out[(y * dw + x) * 3 + c] = clamp255(top + (bot - top) * fy)
            }
        }
    }
    return out
}

private fun invert3x3(m: DoubleArray): DoubleArray {
    val a = m[0]; val b = m[1]; val c = m[2]
    val d = m[3]; val e = m[4]; val f = m[5]
    val g = m[6]; val h = m[7]; val i = m[8]
    val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
    val det = a * A + b * B + c * C
    val id = 1 / det
    return doubleArrayOf(
        A * id, (c * h - b * i) * id, (b * f - c * e) * id,
        B * id, (a * i - c * g) * id, (c * d - a * f) * id,
        C * id, (b * g - a * h) * id, (a * e - b * d) * id,
    )
}

fun clamp255(v: Double): Int = max(0, min(255, kotlin.math.round(v).toInt()))

/** 逆时针旋转 90°（等价 numpy.rot90），宽高互换。 */
fun rot90Bgr(src: IntArray, w: Int, h: Int): IntArray {
    val out = IntArray(w * h * 3)
    for (y2 in 0 until w) {
        for (x2 in 0 until h) {
            val srcRow = x2; val srcCol = w - 1 - y2
            for (c in 0..2) out[(y2 * h + x2) * 3 + c] = src[(srcRow * w + srcCol) * 3 + c]
        }
    }
    return out
}

/** 8 邻接连通分量。mask 为 W*H 的 0/1；返回每个连通域的像素点集。 */
fun connectedComponents(mask: ByteArray, w: Int, h: Int): List<List<Pt>> {
    val visited = BooleanArray(w * h)
    val comps = ArrayList<List<Pt>>()
    val nb = intArrayOf(1, 0, -1, 0, 0, 1, 0, -1, 1, 1, 1, -1, -1, 1, -1, -1)
    val stack = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val idx = y * w + x
            if (mask[idx].toInt() == 0 || visited[idx]) continue
            val comp = ArrayList<Pt>()
            var sp = 0
            stack[sp++] = idx; visited[idx] = true
            while (sp > 0) {
                val p = stack[--sp]
                val px = p % w; val py = p / w
                comp.add(pt(px, py))
                for (k in 0 until 8) {
                    val nx = px + nb[2 * k]; val ny = py + nb[2 * k + 1]
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val ni = ny * w + nx
                    if (mask[ni].toInt() != 0 && !visited[ni]) { visited[ni] = true; stack[sp++] = ni }
                }
            }
            comps.add(comp)
        }
    }
    return comps
}

/**
 * unclip：多边形等距外扩，等价 pyclipper JT_ROUND / clipper-lib jtRound 对凸文本四边形的结果。
 * distance = area * ratio / perimeter。按顶点两条边的外法线角平分线做 miter 外推；
 * 对凸四边形与 round join 差异 <1px（随后还会再取一次 minAreaRect，差异被吸收）。
 */
fun unclip(box: List<Pt>, ratio: Double): List<Pt> {
    val area = polyArea(box)
    val perimeter = polyPerimeter(box)
    if (area <= 0 || perimeter <= 0) return box
    val distance = area * ratio / perimeter
    // 确保逆时针（外法线 = 边方向右转 90°）
    var signed = 0.0
    for (i in box.indices) {
        val a = box[i]; val b = box[(i + 1) % box.size]
        signed += a[0] * b[1] - b[0] * a[1]
    }
    val poly = if (signed < 0) box.reversed() else box
    val n = poly.size
    val normals = Array(n) { i ->
        val a = poly[i]; val b = poly[(i + 1) % n]
        val dx = b[0] - a[0]; val dy = b[1] - a[1]
        val len = hypot(dx, dy)
        if (len == 0.0) doubleArrayOf(0.0, 0.0) else doubleArrayOf(dy / len, -dx / len)
    }
    val out = ArrayList<Pt>(n)
    for (i in 0 until n) {
        val nPrev = normals[(i - 1 + n) % n]
        val nNext = normals[i]
        var bx = nPrev[0] + nNext[0]
        var by = nPrev[1] + nNext[1]
        val bl = hypot(bx, by)
        if (bl < 1e-9) { bx = nNext[0]; by = nNext[1] } else { bx /= bl; by /= bl }
        val cosHalf = bx * nNext[0] + by * nNext[1]
        val scale = if (cosHalf < 1e-6) distance else distance / cosHalf
        out.add(pt(poly[i][0] + bx * scale, poly[i][1] + by * scale))
    }
    return out
}

/** numpy.round 语义：.5 取最近偶数。 */
fun npRound(x: Double): Double {
    val f = kotlin.math.floor(x)
    val frac = x - f
    if (frac < 0.5 - 1e-9) return f
    if (frac > 0.5 + 1e-9) return f + 1
    return if (f % 2.0 == 0.0) f else f + 1
}

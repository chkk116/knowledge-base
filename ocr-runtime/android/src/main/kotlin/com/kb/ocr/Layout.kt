package com.kb.ocr

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** 版面阅读顺序修正：x 轴覆盖图找竖向间隙聚栏，再按栏、栏内按 y 重排。 */
fun columnAwareReorder(items: List<RawLine>): List<RawLine> {
    if (items.size < 2) return items
    val yCenter: (Array<DoubleArray>) -> Double = { box -> box.sumOf { it[1] } / box.size }
    var maxX = 0.0
    for (it in items) for (p in it.box) if (p[0] > maxX) maxX = p[0]
    if (maxX <= 0) return items.sortedBy { yCenter(it.box) }

    val cov = ByteArray(floor(maxX).toInt() + 1)
    for (it in items) {
        val xs = it.box.map { p -> p[0] }
        val x0 = max(0, floor(xs.min()).toInt())
        val x1 = min(cov.size - 1, floor(xs.max()).toInt())
        for (x in x0..x1) cov[x] = 1
    }
    val segs = ArrayList<IntArray>()
    var inSeg = false; var start = 0
    for (x in cov.indices) {
        if (cov[x].toInt() != 0 && !inSeg) { inSeg = true; start = x }
        else if (cov[x].toInt() == 0 && inSeg) { inSeg = false; segs.add(intArrayOf(start, x - 1)) }
    }
    if (inSeg) segs.add(intArrayOf(start, cov.size - 1))
    if (segs.isEmpty()) return items.sortedBy { yCenter(it.box) }

    val minGap = max(30, floor(maxX * 0.03).toInt())
    val merged = ArrayList<IntArray>()
    merged.add(segs[0].copyOf())
    for (i in 1 until segs.size) {
        val a = segs[i][0]; val b = segs[i][1]
        if (a - merged.last()[1] <= minGap) merged.last()[1] = b
        else merged.add(intArrayOf(a, b))
    }
    // 单栏：保持原始检测顺序，避免打乱本就正确的阅读顺序
    if (merged.size <= 1) return items

    fun colOf(box: Array<DoubleArray>): Int {
        val xc = box.sumOf { it[0] } / box.size
        for (i in merged.indices) if (xc >= merged[i][0] && xc <= merged[i][1]) return i
        var best = 0; var bd = Double.POSITIVE_INFINITY
        for (i in merged.indices) {
            val m = (merged[i][0] + merged[i][1]) / 2.0
            val d = abs(m - xc)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }
    val grouped = Array(merged.size) { ArrayList<RawLine>() }
    for (it in items) grouped[colOf(it.box)].add(it)
    val out = ArrayList<RawLine>()
    for (g in grouped) out.addAll(g.sortedBy { yCenter(it.box) })
    return out
}

package com.kb.ocr

import java.io.File

data class ModelBundle(
    val det: OcrSession,
    val cls: OcrSession?,
    val rec: OcrSession,
    val characterList: List<String>,
)

data class OcrOptions(
    val lang: String = "ch",
    val columnAware: Boolean = true,
    val postprocess: Boolean = true,
    val useAngleCls: Boolean = true,
    val textScore: Double = 0.5,
    val minHeight: Int = 30,
    val widthHeightRatio: Double = 8.0,
    val detConfig: DetConfig = DetConfig(),
    val clsConfig: ClsConfig = ClsConfig(),
    val recConfig: RecConfig = RecConfig(),
)

/** 跨端统一 OCR 引擎（Kotlin 实现），与 Python / Web 行为对齐。 */
class OcrEngine(private val bundle: ModelBundle, val options: OcrOptions = OcrOptions()) {
    val lang: String get() = options.lang

    fun recognize(image: RawImage): OcrResult {
        val t0 = System.nanoTime()
        val src = rawToBgr(image)
        val h = image.height; val w = image.width

        var boxes: List<Array<DoubleArray>>
        var crops: List<BgrImage>
        val useLimitRatio = w.toDouble() / h > options.widthHeightRatio
        if (h > options.minHeight && !useLimitRatio) {
            val detBoxes = detect(bundle.det, image, options.detConfig).boxes
            val filtered = filterDetBoxes(detBoxes, h, w)
            if (filtered.isEmpty()) return emptyResult(t0)
            boxes = sortedBoxes(filtered)
            crops = boxes.map { getRotateCropImage(src, it) }
        } else {
            boxes = listOf(arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(w.toDouble(), 0.0),
                doubleArrayOf(w.toDouble(), h.toDouble()), doubleArrayOf(0.0, h.toDouble())))
            crops = listOf(src)
        }

        if (options.useAngleCls && bundle.cls != null) {
            crops = classify(bundle.cls, crops, options.clsConfig)
        }

        val recRes = recognize(bundle.rec, crops, bundle.characterList, options.recConfig)

        val items = ArrayList<RawLine>()
        for (i in boxes.indices) {
            val text = recRes.getOrNull(i)?.text ?: ""
            val score = recRes.getOrNull(i)?.score ?: 0.0
            if (score >= options.textScore) items.add(RawLine(boxes[i], text, score))
        }

        val ordered = if (options.columnAware) columnAwareReorder(items) else items
        val lines = ordered.mapIndexed { i, it ->
            OcrLine(
                text = if (options.postprocess) fixNumbers(it.text) else it.text,
                box = it.box,
                score = it.score,
                lineNo = i,
            )
        }
        val warnings = if (lines.isEmpty()) listOf("未检测到文字") else emptyList()
        return OcrResult(
            text = lines.joinToString("\n") { it.text },
            lines = lines,
            lang = options.lang,
            elapsedMs = (System.nanoTime() - t0) / 1e6,
            warnings = warnings,
        )
    }

    private fun emptyResult(t0: Long): OcrResult = OcrResult(
        text = "", lines = emptyList(), lang = options.lang,
        elapsedMs = (System.nanoTime() - t0) / 1e6,
        warnings = listOf("未检测到文字"),
    )

    companion object {
        /** 桌面 JVM：从目录加载三个 .onnx 与 ppocr_keys.txt。 */
        fun fromDirectory(dir: File, options: OcrOptions = OcrOptions(), threads: Int = 1): OcrEngine {
            val det = OrtSessions.fromPath(File(dir, "ch_PP-OCRv3_det_infer.onnx").absolutePath, threads)
            val rec = OrtSessions.fromPath(File(dir, "ch_PP-OCRv3_rec_infer.onnx").absolutePath, threads)
            val cls = if (options.useAngleCls)
                OrtSessions.fromPath(File(dir, "ch_ppocr_mobile_v2.0_cls_infer.onnx").absolutePath, threads)
            else null
            val keys = File(dir, "ppocr_keys.txt").readText(Charsets.UTF_8)
            return OcrEngine(ModelBundle(det, cls, rec, buildCharacterList(keys)), options)
        }

        /**
         * Android：把 assets 里的模型读成字节后构造。
         * 用法：val engine = OcrEngine.fromModelBytes(detBytes, recBytes, clsBytes, keysText)
         */
        fun fromModelBytes(
            detBytes: ByteArray, recBytes: ByteArray, clsBytes: ByteArray?,
            keysText: String, options: OcrOptions = OcrOptions(), useNnapi: Boolean = false,
        ): OcrEngine {
            val det = OrtSessions.fromBytes(detBytes, 1, useNnapi)
            val rec = OrtSessions.fromBytes(recBytes, 1, useNnapi)
            val cls = if (options.useAngleCls && clsBytes != null) OrtSessions.fromBytes(clsBytes, 1, useNnapi) else null
            return OcrEngine(ModelBundle(det, cls, rec, buildCharacterList(keysText)), options)
        }
    }
}

/**
 * Android Bitmap -> RawImage 的等价入口（用 ARGB int 数组，避免核心依赖 android.graphics 或 javax.imageio）。
 * 调用方：val px = IntArray(bmp.width*bmp.height)
 *         bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
 *         val raw = rawFromArgb(px, bmp.width, bmp.height)
 * JVM 侧的 ImageIO 解码器见 test sourceSet（仅供对齐测试，Android 运行时不含该类）。
 */
fun rawFromArgb(argb: IntArray, w: Int, h: Int): RawImage {
    val rgba = IntArray(w * h * 4)
    for (i in 0 until w * h) {
        val a = argb[i]
        rgba[i * 4] = (a shr 16) and 0xFF
        rgba[i * 4 + 1] = (a shr 8) and 0xFF
        rgba[i * 4 + 2] = a and 0xFF
        rgba[i * 4 + 3] = (a shr 24) and 0xFF
    }
    return RawImage(w, h, rgba)
}

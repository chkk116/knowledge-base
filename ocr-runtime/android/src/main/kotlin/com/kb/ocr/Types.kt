package com.kb.ocr

// 跨端统一类型（与 python/ocr_runtime/types.py、web/src/types.ts 对齐）

data class OcrLine(
    val text: String,
    val box: Array<DoubleArray>,   // 4 角点 [[x1,y1],[x2,y2],[x3,y3],[x4,y4]]，原图像素坐标
    val score: Double,
    val lineNo: Int,
)

data class OcrResult(
    val text: String,
    val lines: List<OcrLine>,
    val lang: String,
    val elapsedMs: Double,
    val engine: String = "rapidocr-onnx",
    val warnings: List<String> = emptyList(),
)

/** 解码后的 RGBA 图像；rgba 为 0..255 的整型数组，长度 width*height*4。 */
data class RawImage(val width: Int, val height: Int, val rgba: IntArray)

/** BGR 像素缓冲（cv2 约定），bgr 为 0..255 整型数组，长度 width*height*3。 */
data class BgrImage(val width: Int, val height: Int, val bgr: IntArray)

/** 检测/识别之间的中间结果。 */
data class RawLine(val box: Array<DoubleArray>, val text: String, val score: Double)

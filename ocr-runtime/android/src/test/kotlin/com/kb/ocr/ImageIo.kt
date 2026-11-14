package com.kb.ocr

import java.io.File
import javax.imageio.ImageIO

/** 仅供桌面 JVM 对齐测试使用；Android 运行时不含 javax.imageio，请用 rawFromArgb(Bitmap.getPixels)。 */
fun loadRawImage(path: String): RawImage {
    val img = ImageIO.read(File(path)) ?: throw IllegalArgumentException("无法读取图片 $path")
    val w = img.width; val h = img.height
    val argb = img.getRGB(0, 0, w, h, null, 0, w)
    return rawFromArgb(argb, w, h)
}

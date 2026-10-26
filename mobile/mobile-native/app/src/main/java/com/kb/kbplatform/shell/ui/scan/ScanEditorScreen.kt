/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.scan

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.ui.common.KbButton
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.theme.KbBlue
import com.kb.kbplatform.shell.ui.theme.KbRed
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 扫描编辑器（对标 MobileScanEditor 的多页扫描能力，原生实现）：
 * 多页添加（拍照/相册）→ 逐页旋转 90° / 滤镜（原图/灰度/黑白增强）/ 删除 →
 * 导出：多页合成 PDF（android.graphics.pdf，存 Documents 可分享）或上传图片建文档。
 * 裁剪与透视矫正未迁移（Web 端保留完整能力）。
 */
private data class ScanPage(val id: Long, val bitmap: Bitmap, val rotation: Int, val filter: Int)
// filter: 0 原图 / 1 灰度 / 2 黑白增强

@Composable
fun ScanEditorScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var pages by remember { mutableStateOf<List<ScanPage>>(emptyList()) }
    var current by remember { mutableIntStateOf(-1) }   // 当前编辑页下标
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var pdfReady by remember { mutableStateOf<File?>(null) }

    val cameraFile = remember { File(ctx.cacheDir, "web_capture/scan_${System.currentTimeMillis()}.jpg") }
    val captureUri = remember {
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", cameraFile)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(cameraFile.absolutePath) }
                if (bmp != null) {
                    pages = pages + ScanPage(System.currentTimeMillis(), bmp, 0, 0)
                    if (current < 0) current = 0
                }
            }
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
                if (bmp != null) {
                    pages = pages + ScanPage(System.currentTimeMillis(), bmp, 0, 0)
                    if (current < 0) current = 0
                }
            }
        }
    }

    fun currentPage(): ScanPage? = pages.getOrNull(current)

    fun rotateCurrent() {
        val idx = current
        val p = pages.getOrNull(idx) ?: return
        pages = pages.toMutableList().also {
            it[idx] = p.copy(rotation = (p.rotation + 90) % 360)
        }
    }

    fun cycleFilter() {
        val idx = current
        val p = pages.getOrNull(idx) ?: return
        pages = pages.toMutableList().also {
            it[idx] = p.copy(filter = (p.filter + 1) % 3)
        }
    }

    fun deleteCurrent() {
        pages = pages.filterIndexed { i, _ -> i != current }
        current = if (current >= pages.size) pages.size - 1 else current
    }

    fun renderPage(p: ScanPage): Bitmap {
        val m = android.graphics.Matrix().apply { postRotate(p.rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(p.bitmap, 0, 0, p.bitmap.width, p.bitmap.height, m, true)
        if (p.filter == 0) return rotated
        val out = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(
                when (p.filter) {
                    1 -> ColorMatrix().apply { setSaturation(0f) }                       // 灰度
                    else -> ColorMatrix(
                        floatArrayOf(
                            1.3f, 0f, 0f, 0f, -40f,
                            0f, 1.3f, 0f, 0f, -40f,
                            0f, 0f, 1.3f, 0f, -40f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )                                                                   // 黑白增强（高对比）
                }
            )
        }
        canvas.drawBitmap(rotated, 0f, 0f, paint)
        return out
    }

    fun exportPdf() {
        if (pages.isEmpty() || busy) return
        busy = true
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val doc = PdfDocument()
                    pages.forEachIndexed { i, p ->
                        val bmp = renderPage(p)
                        val scale = 1240f / bmp.width.coerceAtLeast(1)
                        val w = 1240.toInt()
                        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                        val pageInfo = PdfDocument.PageInfo.Builder(w, h, i + 1).create()
                        val page = doc.startPage(pageInfo)
                        page.canvas.drawBitmap(
                            Bitmap.createScaledBitmap(bmp, w, h, true), 0f, 0f, null
                        )
                        doc.finishPage(page)
                    }
                    val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "scan").apply { mkdirs() }
                    val out = File(dir, "扫描件_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.pdf")
                    FileOutputStream(out).use { doc.writeTo(it) }
                    doc.close()
                    out
                }
                pdfReady = file
                msg = ""
            } catch (e: Exception) {
                msg = e.message ?: "PDF 导出失败"
            } finally {
                busy = false
            }
        }
    }

    fun saveAsDoc() {
        if (pages.isEmpty() || busy) return
        busy = true
        scope.launch {
            try {
                val first = renderPage(pages.first())
                val f = withContext(Dispatchers.IO) {
                    val dir = File(ctx.cacheDir, "web_capture").apply { mkdirs() }
                    val out = File(dir, "scan_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(out).use {
                        first.compress(Bitmap.CompressFormat.JPEG, 88, it)
                    }
                    out
                }
                val up = KbApi.uploadFile(f, "image/jpeg")
                val imgUrl = up.optJSONObject("data")?.optString("url") ?: up.optString("url")
                if (imgUrl.isBlank()) throw IllegalStateException("上传失败")
                val sid = KbApi.personalShare().optLong("id", 0)
                KbApi.createShareDoc(
                    sid,
                    "扫描件 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date()),
                    "<p><img src=\"$imgUrl\" style=\"max-width:100%\"/></p><p>共 ${pages.size} 页扫描，PDF 已在本地 Documents/scan 目录。</p>"
                )
                msg = "已保存到我的文档"
            } catch (e: Exception) {
                msg = e.message ?: "保存失败"
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "扫描", onBack = onDone)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // —— 当前页预览 ——
            val cur = currentPage()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .background(Color(0xFFECEFF3), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (cur == null) {
                    Text("添加页面开始扫描", fontSize = 13.sp, color = KbBlue)
                } else {
                    Image(
                        renderPage(cur).asImageBitmap(), null,
                        Modifier.fillMaxSize().padding(8.dp),
                        contentScale = ContentScale.Fit
                    )
                    Text(
                        "${current + 1} / ${pages.size}",
                        fontSize = 11.sp, color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .background(Color(0x99000000), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            // —— 页面缩略条 ——
            if (pages.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pages.size) { i ->
                        val p = pages[i]
                        Box(
                            Modifier
                                .size(64.dp)
                                .background(
                                    if (i == current) KbBlue else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { current = i }
                                .padding(2.dp)
                        ) {
                            Image(
                                renderPage(p).asImageBitmap(), null,
                                Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }

            // —— 页面操作 ——
            if (cur != null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { rotateCurrent() }, modifier = Modifier.weight(1f)) { Text("旋转", fontSize = 12.sp) }
                    OutlinedButton(onClick = { cycleFilter() }, modifier = Modifier.weight(1f)) {
                        Text(listOf("滤镜:原图", "滤镜:灰度", "滤镜:增强")[currentPage()!!.filter], fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { deleteCurrent() }, modifier = Modifier.weight(1f)) {
                        Text("删除页", fontSize = 12.sp, color = KbRed)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        cameraLauncher.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                            putExtra(MediaStore.EXTRA_OUTPUT, captureUri)
                            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("拍照添加") }
                OutlinedButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier.weight(1f)
                ) { Text("相册添加") }
            }

            Spacer(Modifier.height(14.dp))
            KbButton(
                text = if (busy) "处理中…" else "导出 PDF（${pages.size} 页）",
                modifier = Modifier.fillMaxWidth(),
                enabled = pages.isNotEmpty() && !busy,
                onClick = { exportPdf() }
            )
            Spacer(Modifier.height(10.dp))
            KbButton(
                text = if (busy) "处理中…" else "上传首页建文档",
                modifier = Modifier.fillMaxWidth(),
                ghost = true,
                enabled = pages.isNotEmpty() && !busy,
                onClick = { saveAsDoc() }
            )
            if (msg.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            pdfReady?.let { f ->
                Spacer(Modifier.height(8.dp))
                KbCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("PDF 已生成", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(f.absolutePath, fontSize = 10.sp, color = KbTextDim, maxLines = 2)
                        TextButton(onClick = {
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "application/pdf")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }.onFailure {
                                Toast.makeText(ctx, "没有可用的 PDF 查看器", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("打开查看", fontSize = 12.sp) }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun KbCard(modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.material3.Card(
        shape = RoundedCornerShape(14.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
    ) { Box { content() } }
}

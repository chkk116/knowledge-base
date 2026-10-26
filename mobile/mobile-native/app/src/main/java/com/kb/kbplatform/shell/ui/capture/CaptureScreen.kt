/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.capture

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.LocalOcr
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.CenterHint
import com.kb.kbplatform.shell.ui.common.KbButton
import com.kb.kbplatform.shell.ui.common.TopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 拍照建文档（TabBar 中央 ＋ · 对标 MobileCapture.vue 的主流程）：
 * 拍照/相册 → 本地 OCR（Tesseract 离线识别，不传图）→ 可编辑文本 → 选空间 → 保存为文档。
 * 扫描增强（裁剪/透视/多页 PDF）暂未迁移，主流程「拍照识字建文档」已可用。
 */
@Composable
fun CaptureScreen(onDone: () -> Unit, openRoute: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf("pick") }          // pick | preview | review
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var ocrText by remember { mutableStateOf("") }
    var ocrLoading by remember { mutableStateOf(false) }
    var ocrError by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var doneMsg by remember { mutableStateOf("") }
    var spaces by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var chosen by remember { mutableIntStateOf(-1) }
    var showPicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { spaces = KbApi.myShares().objects() }
    }

    val cameraFile = remember { File(ctx.cacheDir, "web_capture/cap_${System.currentTimeMillis()}.jpg") }
    val captureUri = remember {
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", cameraFile)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val bmp = BitmapFactory.decodeFile(cameraFile.absolutePath)
            if (bmp != null) {
                photo = bmp
                stage = "preview"
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
                    photo = bmp
                    stage = "preview"
                }
            }
        }
    }

    fun startOcr() {
        val bmp = photo ?: return
        ocrLoading = true
        ocrError = ""
        scope.launch {
            try {
                ocrText = LocalOcr.recognize(ctx, bmp)
                stage = "review"
            } catch (e: Exception) {
                ocrError = e.message ?: "识别失败"
            } finally {
                ocrLoading = false
            }
        }
    }

    fun save() {
        if (saving) return
        val sid = if (chosen >= 0) spaces.getOrNull(chosen)?.optLong("id", 0) else -1L
        scope.launch {
            saving = true
            try {
                val target: Long = if (sid != null && sid > 0) sid else KbApi.personalShare().optLong("id", 0)
                val title = ocrText.lineSequence().firstOrNull { it.isNotBlank() }
                    ?.take(24)?.ifBlank { null }
                    ?: "拍照文档 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
                val paragraphs = ocrText.split(Regex("\n{2,}"))
                    .filter { it.isNotBlank() }
                    .joinToString("") { "<p>${it.replace("\n", "<br>")}</p>" }
                KbApi.createShareDoc(target, title, paragraphs.ifBlank { "<p></p>" })
                doneMsg = "已保存到「${spaces.getOrNull(chosen)?.optString("name").orEmpty().ifBlank { "我的文档" }}」"
            } catch (e: Exception) {
                error = e.message ?: "保存失败（已存为离线草稿）"
                com.kb.kbplatform.shell.data.DraftStore.enqueue(
                    ctx, if (chosen >= 0) spaces.getOrNull(chosen)?.optLong("id", 0) else null,
                    ocrText.lineSequence().firstOrNull { it.isNotBlank() }?.take(24) ?: "拍照文档", ocrText
                )
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "拍照建文档", onBack = if (stage == "pick") onDone else {
            { stage = if (stage == "review") "preview" else "pick" }
        })

        when {
            doneMsg.isNotEmpty() -> {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(70.dp))
                    Text("✅", fontSize = 40.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(doneMsg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.padding(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            doneMsg = ""; photo = null; ocrText = ""; stage = "pick"
                        }, modifier = Modifier.weight(1f)) { Text("再来一张") }
                        Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text("完成") }
                    }
                }
            }
            stage == "pick" -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("📷", fontSize = 46.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("把纸质资料拍下来，离线识别成文档", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(26.dp))
                    com.kb.kbplatform.shell.ui.common.KbButton(text = "拍 照", modifier = Modifier.fillMaxWidth(), onClick = {
                        cameraLauncher.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                            putExtra(MediaStore.EXTRA_OUTPUT, captureUri)
                            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    })
                    Spacer(Modifier.height(12.dp))
                    KbButton(text = "从相册选择", modifier = Modifier.fillMaxWidth(), ghost = true, onClick = {
                        galleryLauncher.launch("image/*")
                    })
                    Spacer(Modifier.height(12.dp))
                    com.kb.kbplatform.shell.ui.common.KbButton(
                        text = "多页扫描（旋转/滤镜/PDF）",
                        modifier = Modifier.fillMaxWidth(),
                        ghost = true,
                        onClick = { openRoute("scan") }
                    )
                }
            }
            stage == "preview" -> {
                val bmp = photo
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(), null,
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color(0xFFECEFF3), RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                    if (ocrError.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(ocrError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(12.dp))
                    KbButton(
                        text = if (ocrLoading) "识别中…" else "开始识别（离线）",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !ocrLoading,
                        onClick = { startOcr() }
                    )
                }
            }
            else -> {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("识别结果（可修正）", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ocrText,
                        onValueChange = { ocrText = it },
                        minLines = 8,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showPicker = true }
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        Text("存入空间：", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            spaces.getOrNull(chosen)?.optString("name").orEmpty().ifBlank { "我的文档（默认）" },
                            fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    }
                    if (error.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(12.dp))
                    KbButton(text = if (saving) "保存中…" else "保存为文档", modifier = Modifier.fillMaxWidth(), enabled = !saving, onClick = {
                        save()
                    })
                }
            }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("选择目标空间") },
            text = {
                Column {
                    spaces.forEachIndexed { i, s ->
                        Text(
                            s.optString("name").ifBlank { "未命名空间" },
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { chosen = i; showPicker = false }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("取消") } }
        )
    }
}

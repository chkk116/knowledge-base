/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 系统分享收件箱（替代 Capacitor 版 ShareReceiverPlugin 的原生职责）：
 * 从微信 / 文件管理器「分享到知识库」→ MainActivity intent-filter 接住 →
 * 落盘到 cacheDir/share_inbox → 待导入状态暴露给 UI（首页横幅入口）。
 * 导入完成（或用户取消）后 clear()。
 */
object ShareInbox {
    data class SharedFile(val file: File, val mime: String)

    private val _pending = MutableStateFlow<List<SharedFile>>(emptyList())
    val pending: StateFlow<List<SharedFile>> = _pending

    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text

    private const val MAX_FILES = 20

    /** 处理 ACTION_SEND / ACTION_SEND_MULTIPLE；无分享数据时安静返回 false */
    fun accept(ctx: Context, intent: Intent): Boolean {
        if (Intent.ACTION_SEND != intent.action && Intent.ACTION_SEND_MULTIPLE != intent.action) return false
        val dir = File(ctx.cacheDir, "share_inbox").apply { mkdirs() }
        val out = mutableListOf<SharedFile>()

        val streams: List<Uri> = if (Intent.ACTION_SEND_MULTIPLE == intent.action) {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        } else {
            listOfNotNull(
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            )
        }
        for (uri in streams.take(MAX_FILES)) {
            // ⚠️ DISPLAY_NAME 由分享方（第三方 App）提供，可能带 "../" 或路径分隔符：
            // 直接拼进 File(dir, name) 会写到 cacheDir 之外（覆盖 shared_prefs 等）。
            // 这里只保留字母/数字/中文/._-，并截断长度。
            val name = safeName(resolveName(ctx, uri), out.size)
            val f = File(dir, "${System.currentTimeMillis()}_$name")
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    f.outputStream().use { output -> input.copyTo(output) }
                } ?: continue
                out.add(SharedFile(f, ctx.contentResolver.getType(uri) ?: "application/octet-stream"))
            } catch (e: Exception) {
                f.delete()
            }
        }

        val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (extraText != null && extraText.isNotBlank()) _text.value = extraText

        if (out.isEmpty() && _text.value == null) return false
        _pending.value = _pending.value + out
        return true
    }

    private fun resolveName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        uri.lastPathSegment
    }

    /** 去掉目录部分与不安全字符，得到可安全落盘的文件名（防路径穿越） */
    private fun safeName(raw: String?, index: Int): String {
        val base = (raw ?: "").substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(80).trim('.', '_', '-')
        return cleaned.ifBlank { "share_${index}" }
    }

    /** 导入完成/取消：清空待导入与缓存文件 */
    fun clear() {
        _pending.value.forEach { it.file.delete() }
        _pending.value = emptyList()
        _text.value = null
    }
}

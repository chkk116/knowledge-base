/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.admin

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BrandingWatermark
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.ui.common.KbButton
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.KbEmpty
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.common.kbPress
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import com.kb.kbplatform.shell.ui.theme.KbToneKind
import com.kb.kbplatform.shell.ui.theme.kbIconGradient
import com.kb.kbplatform.shell.ui.theme.kbTone
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 站点设置（原生，2026-10-26）。
 *
 * 原来「站点维护」是把桌面后台页 `/admin/maintain` 塞进 WebView —— 手机上是一张
 * 1024px 的桌面表单，得左右拖动才看得全。这里改成原生：直接读
 * `GET /api/admin/settings/{key}` 的 JSON，按字段类型渲染成开关 / 输入框，
 * 保存走 `PUT`，**与网页端读写同一份数据**，不会出现两边不一致。
 *
 * 字段类型规则（后端这些配置都是扁平 JSON）：
 * - `true/false` → 开关
 * - 数字 → 数字键盘输入框
 * - 字符串 → 文本框（长文本多行）
 * - 数组/对象 → 只读展示（如页脚的 items 列表，改它还是得去网页端）
 */
private data class SettingEntry(
    val key: String,
    val title: String,
    val desc: String,
    val icon: ImageVector,
    val tone: KbToneKind
)

private val ENTRIES = listOf(
    SettingEntry("site_info", "站点信息", "备案号 / 页脚 / 自定义链接", Icons.Filled.Web, KbToneKind.Blue),
    SettingEntry("watermark", "屏幕水印", "全局水印文字（内部资料提示）", Icons.Filled.BrandingWatermark, KbToneKind.Purple),
    SettingEntry("appearance", "站点外观", "主色 / 主题等站点级外观", Icons.Filled.Palette, KbToneKind.Orange),
    SettingEntry("login_qr", "登录页二维码", "登录页展示的二维码与说明", Icons.Filled.QrCode2, KbToneKind.Green),
    SettingEntry("ai_cost_guard", "AI 费用守护", "图片 / 文本阈值，超限走本地", Icons.Filled.Savings, KbToneKind.Cyan)
)

@Composable
fun SiteSettingsScreen(onBack: () -> Unit, openWeb: (String, String) -> Unit = { _, _ -> }) {
    var editing by remember { mutableStateOf<SettingEntry?>(null) }

    val current = editing
    if (current != null) {
        SettingEditScreen(current, onBack = { editing = null })
        return
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "站点设置", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "以下配置与网页端后台共用同一份数据，改完立即对全站生效。",
                fontSize = 12.5.sp, color = KbTextDim
            )
            KbCard {
                Column {
                    ENTRIES.forEachIndexed { i, e ->
                        if (i > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 62.dp)
                                    .height(0.5.dp)
                                    .background(MaterialTheme.colorScheme.outline)
                            )
                        }
                        SettingRow(e) { editing = e }
                    }
                }
            }
            // 页脚 items 是数组（每项含 name/url），用通用表单编辑容易改坏，这里留个入口去网页端
            KbCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .kbPress(scaleDown = 1f) { openWeb("/admin/site-footer", "页脚设置") }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                        tint = KbTextDim, modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("页脚自定义链接", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "items 列表结构复杂，在网页端后台编辑",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(e: SettingEntry, onClick: () -> Unit) {
    val tone = kbTone(e.tone)
    Row(
        Modifier
            .fillMaxWidth()
            .kbPress(scaleDown = 1f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(kbIconGradient(e.tone)),
                    RoundedCornerShape(11.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(e.icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(e.desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = KbTextDim, modifier = Modifier.size(18.dp)
        )
    }
}

/** 一条可编辑字段的本地状态：原始类型 + 当前值（统一用字符串存，保存时按原类型还原） */
private class FieldState(val name: String, val kind: Kind, initial: String) {
    enum class Kind { BOOL, NUMBER, TEXT, LONG_TEXT, READONLY }

    var value by mutableStateOf(initial)
}

@Composable
private fun SettingEditScreen(entry: SettingEntry, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf("") }
    val fields = remember { mutableStateListOf<FieldState>() }
    // 只读字段（数组/对象）原文，保存时原样带回，避免把页脚 items 之类覆盖掉
    val readonly = remember { mutableMapOf<String, Any>() }

    LaunchedEffect(entry.key) {
        loading = true
        error = ""
        try {
            val raw = KbApi.adminGetSetting(entry.key)
            val obj = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            fields.clear()
            readonly.clear()
            obj.keys().forEach { name ->
                when (val v = obj.opt(name)) {
                    is Boolean -> fields.add(FieldState(name, FieldState.Kind.BOOL, if (v) "1" else "0"))
                    is Number -> fields.add(FieldState(name, FieldState.Kind.NUMBER, v.toString()))
                    is String -> {
                        val long = v.length > 40 || name in setOf("text", "content", "copyright", "desc", "notice")
                        fields.add(
                            FieldState(
                                name,
                                if (long) FieldState.Kind.LONG_TEXT else FieldState.Kind.TEXT,
                                v
                            )
                        )
                    }
                    is JSONObject, is JSONArray -> {
                        readonly[name] = v
                        fields.add(FieldState(name, FieldState.Kind.READONLY, v.toString()))
                    }
                }
            }
            if (fields.isEmpty()) error = "这个配置还没有内容，去网页端后台初始化一次即可"
        } catch (e: Exception) {
            error = e.message ?: "读取失败"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = entry.title, onBack = onBack)
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
            }
            error.isNotEmpty() -> KbEmpty("⚠️", error)
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                KbCard {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                        fields.forEach { f ->
                            FieldRow(f)
                        }
                    }
                }
                if (savedHint.isNotEmpty()) {
                    Text(savedHint, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.primary)
                }
                KbButton(
                    text = if (saving) "保存中…" else "保存",
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                    scope.launch {
                        saving = true
                        savedHint = ""
                        try {
                            val out = JSONObject()
                            fields.forEach { f ->
                                when (f.kind) {
                                    FieldState.Kind.BOOL -> out.put(f.name, f.value == "1")
                                    FieldState.Kind.NUMBER ->
                                        out.put(f.name, f.value.toDoubleOrNull() ?: 0)
                                    FieldState.Kind.TEXT, FieldState.Kind.LONG_TEXT ->
                                        out.put(f.name, f.value)
                                    FieldState.Kind.READONLY ->
                                        readonly[f.name]?.let { out.put(f.name, it) }
                                }
                            }
                            KbApi.adminPutSetting(entry.key, out.toString())
                            savedHint = "已保存，全站生效"
                        } catch (e: Exception) {
                            savedHint = "保存失败：${e.message}"
                        } finally {
                            saving = false
                        }
                    }
                    }
                )
                Text(
                    "字段名来自后端配置，含义与网页端后台一致。",
                    fontSize = 11.5.sp, color = KbTextDim
                )
            }
        }
    }
}

@Composable
private fun FieldRow(f: FieldState) {
    when (f.kind) {
        FieldState.Kind.BOOL -> Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(f.name, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = f.value == "1", onCheckedChange = { f.value = if (it) "1" else "0" })
        }
        FieldState.Kind.READONLY -> Column(Modifier.padding(vertical = 10.dp)) {
            Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                f.value,
                fontSize = 11.5.sp, color = KbTextDim, maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text("（结构较复杂，请在网页端编辑）", fontSize = 11.sp, color = KbTextDim)
        }
        else -> OutlinedTextField(
            value = f.value,
            onValueChange = { f.value = it },
            label = { Text(f.name, fontSize = 13.sp) },
            singleLine = f.kind != FieldState.Kind.LONG_TEXT,
            minLines = if (f.kind == FieldState.Kind.LONG_TEXT) 3 else 1,
            keyboardOptions = if (f.kind == FieldState.Kind.NUMBER) {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            } else KeyboardOptions.Default,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        )
    }
}

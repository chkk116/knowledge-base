/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.profile

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.AppConfig
import com.kb.kbplatform.shell.TokenStore
import com.kb.kbplatform.shell.data.AppUpdater
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.theme.KbBlue
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 我的 Tab：资料卡 + 设置（后端地址）+ 检查更新 + 退出登录。 */
@Composable
fun ProfileScreen(
    openWeb: (path: String, title: String) -> Unit,
    openRoute: (String) -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<JSONObject?>(null) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showLockDialog by remember { mutableStateOf(false) }
    var lockEnabled by remember { mutableStateOf(com.kb.kbplatform.shell.data.AppLock.enabled()) }

    val updateState by AppUpdater.state.collectAsState()

    LaunchedEffect(Unit) {
        runCatching {
            val m = KbApi.me()
            me = m
            TokenStore.setInfo(m)   // 刷新身份缓存（permissions 等后台入口显隐依据）
        }
        AppUpdater.check(ctx)   // 静默检查，仅当有新版本时入口条目提示
    }

    val showAdmin = TokenStore.hasPerm("USER_MANAGE") ||
        TokenStore.hasPerm("AI_MANAGE") ||
        TokenStore.hasPerm("DOC_REVIEW") ||
        TokenStore.hasPerm("ANNOUNCEMENT_MANAGE")

    val updateSubtitle = when (val s = updateState) {
        is AppUpdater.State.Available -> "新版本 v${s.versionName} 可用"
        is AppUpdater.State.Downloading -> "下载中 ${s.progress}%"
        is AppUpdater.State.Ready -> "已下载，点击安装"
        is AppUpdater.State.Error -> s.message
        else -> "当前已是最新版"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TopBar(title = "我的")

        // 资料卡
        KbCard(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(52.dp)
                        .background(KbBlue.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        (me?.optString("displayName") ?: "").ifBlank { "?" }.take(1),
                        color = KbBlue, fontSize = 22.sp, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        me?.optString("displayName").orEmpty().ifBlank { "…"},
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOfNotNull(
                            me?.optString("username")?.takeIf { it.isNotEmpty() },
                            roleLabel(me?.optString("role"))
                        ).joinToString(" · "),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        me?.optLong("points", 0)?.toString() ?: "0",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, color = KbBlue
                    )
                    Text("积分", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (showAdmin) {
            MenuCard(
                icon = Icons.Filled.List,
                title = "后台管理",
                subtitle = "用户 / AI 模型 / 审核 / 公告",
                onClick = { openRoute("admin") }
            )
        }
        if (TokenStore.hasPerm("STATS_VIEW")) {
            MenuCard(
                icon = Icons.Filled.List,
                title = "数据看板",
                subtitle = "管理端 · 只读统计",
                onClick = { openRoute("embed/adminstats") }
            )
        }
        if (TokenStore.hasPerm("CORE_MAINTAIN")) {
            MenuCard(
                icon = Icons.Filled.List,
                title = "站点维护",
                subtitle = "功能开关 / 水印 / 页脚 / 登录码 / 外观",
                onClick = { openRoute("embed/adminmaintain") }
            )
        }
        MenuCard(
            icon = Icons.Filled.List,
            title = "我的文件",
            subtitle = "文件中心 · 上传的图片与附件",
            onClick = { openRoute("files") }
        )
        MenuCard(
            icon = Icons.Filled.List,
            title = "发现文章",
            subtitle = "浏览站内文章",
            onClick = { openRoute("discover") }
        )
        MenuCard(
            icon = Icons.Filled.Info,
            title = "徽章成就",
            subtitle = "",
            onClick = { openRoute("badges") }
        )
        MenuCard(
            icon = Icons.Filled.Settings,
            title = "外观",
            subtitle = "跟随系统 / 浅色 / 深色",
            onClick = { openRoute("appearance") }
        )
        MenuCard(
            icon = Icons.Filled.Info,
            title = "关于",
            subtitle = "版本 · 更新日志 · 开源协议",
            onClick = { openRoute("about") }
        )
        MenuCard(
            icon = Icons.Filled.Info,
            title = "隐私政策与免责声明",
            subtitle = "",
            onClick = { openRoute("privacy") }
        )
        MenuCard(
            icon = Icons.Filled.List,
            title = "打开网页版全部功能",
            subtitle = "尚未原生化页面在 WebView 中继续可用",
            onClick = { openWeb("/m/", "网页版") }
        )
        MenuCard(
            icon = Icons.Filled.Info,
            title = "服务器地址",
            subtitle = AppConfig.effectiveBaseUrl + if (AppConfig.isInjected()) "（构建期注入）" else "（本地调试默认）",
            onClick = {}
        )
        MenuCard(
            icon = Icons.Filled.Info,
            title = "应用锁",
            subtitle = if (com.kb.kbplatform.shell.data.AppLock.enabled()) "已开启（PIN 保护）" else "未开启",
            onClick = { showLockDialog = true }
        )
        MenuCard(
            icon = Icons.Filled.Info,
            title = "检查更新",
            subtitle = updateSubtitle,
            onClick = { showUpdateDialog = true }
        )
        MenuCard(
            icon = Icons.Filled.ExitToApp,
            title = "退出登录",
            subtitle = "",
            tint = MaterialTheme.colorScheme.error,
            onClick = { TokenStore.set(null) }
        )
    }

    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("应用更新") },
            text = {
                Column {
                    when (val s = updateState) {
                        is AppUpdater.State.Available -> {
                            Text("发现新版本 v${s.versionName}", fontWeight = FontWeight.SemiBold)
                            if (s.notes.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(s.notes, fontSize = 13.sp)
                            }
                        }
                        is AppUpdater.State.Downloading -> {
                            Text("下载中… ${s.progress}%")
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { s.progress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        is AppUpdater.State.Ready -> Text("更新包已就绪，点击「安装」拉起系统安装器")
                        is AppUpdater.State.Error -> Text("下载失败：${s.message}", color = MaterialTheme.colorScheme.error)
                        else -> Text("当前已是最新版本")
                    }
                }
            },
            confirmButton = {
                when (val s = updateState) {
                    is AppUpdater.State.Available -> Button(onClick = { scope.launch { AppUpdater.download(ctx) } }) {
                        Text("下载")
                    }
                    is AppUpdater.State.Ready -> Button(onClick = { AppUpdater.install(ctx) }) { Text("安装") }
                    is AppUpdater.State.Error -> Button(onClick = { scope.launch { AppUpdater.check(ctx) } }) {
                        Text("重试")
                    }
                    else -> TextButton(onClick = { showUpdateDialog = false }) { Text("知道了") }
                }
            },
            dismissButton = {
                val s = updateState
                if (s !is AppUpdater.State.Downloading &&
                    (s is AppUpdater.State.Available && !s.force || s is AppUpdater.State.Ready)
                ) {
                    TextButton(onClick = { showUpdateDialog = false }) { Text("稍后") }
                }
            }
        )
    }

    if (showLockDialog) {
        var pin by remember { mutableStateOf("") }
        var pin2 by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf("") }
        val appLock = com.kb.kbplatform.shell.data.AppLock
        AlertDialog(
            onDismissRequest = { showLockDialog = false },
            title = { Text(if (lockEnabled) "关闭应用锁" else "开启应用锁") },
            text = {
                Column {
                    if (!lockEnabled) {
                        Text("设置 4-6 位数字 PIN，打开 App 与回切前台时需要解锁", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6) },
                            label = { Text("输入 PIN") },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = pin2,
                            onValueChange = { pin2 = it.filter { c -> c.isDigit() }.take(6) },
                            label = { Text("再输入一次") },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("输入当前 PIN 关闭应用锁", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6) },
                            label = { Text("当前 PIN") },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (msg.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (lockEnabled) {
                        if (appLock.disable(pin)) {
                            lockEnabled = false
                            showLockDialog = false
                        } else msg = "PIN 不正确"
                    } else if (!appLock.validPin(pin)) {
                        msg = "PIN 需为 4-6 位数字"
                    } else if (pin != pin2) {
                        msg = "两次输入不一致"
                    } else {
                        appLock.setup(pin)
                        lockEnabled = true
                        showLockDialog = false
                    }
                }) { Text(if (lockEnabled) "关闭" else "开启") }
            },
            dismissButton = {
                TextButton(onClick = { showLockDialog = false }) { Text("取消") }
            }
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("知识库 Native") },
            text = {
                Text(
                    "Kotlin + Jetpack Compose 原生壳竖切版本。\n" +
                        "Tiptap 协同编辑等 Web 页面经令牌桥接在 WebView 中运行。\n\n" +
                        "源代码基于 AGPL-3.0 许可证开源。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("知道了") }
            }
        )
    }
}

private fun roleLabel(role: String?): String = when (role) {
    "ADMIN" -> "管理员"
    "USER" -> "用户"
    null, "" -> ""
    else -> role
}

@Composable
private fun MenuCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    KbCard(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp)
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

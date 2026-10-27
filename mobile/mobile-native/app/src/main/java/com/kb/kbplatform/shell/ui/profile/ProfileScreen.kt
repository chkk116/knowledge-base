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
import com.kb.kbplatform.shell.ui.common.kbPress
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.foundation.layout.heightIn
import com.kb.kbplatform.shell.data.objects
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Description
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.border
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.TokenStore
import com.kb.kbplatform.shell.data.AppUpdater
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.ui.theme.KbTextDim
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

    // 统计数据（对齐套壳：积分 / 文档 / 空间）
    var docCount by remember { mutableStateOf(-1L) }
    var spaceCount by remember { mutableStateOf(-1L) }
    LaunchedEffect(Unit) {
        runCatching { KbApi.overview().optLong("totalDocs", -1) }.onSuccess { docCount = it }
        runCatching { KbApi.myShares().objects().size.toLong() }.onSuccess { spaceCount = it }
    }

    val name = me?.optString("displayName").orEmpty().ifBlank {
        me?.optString("username").orEmpty().ifBlank { "…" }
    }
    val username = me?.optString("username").orEmpty()
    val roleText = roleLabel(me?.optString("role"))

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── 资料区（对齐套壳 .prof）──
        // 原来这里是 52dp 的白色 TopBar：状态栏那条是页面底色（灰），下面才是白，看着像「没到顶」。
        // 套壳的做法是主色直接铺到屏幕最顶、圆角只在下边，这里照抄。
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                )
                .statusBarsPadding()
                .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 有头像就加载图片（GitHub/微信登录会带回来），没有才退回名字首字母。
            // 用 Coil 的 AsyncImage：自带内存+磁盘缓存，圆形裁剪靠 clip(CircleShape)。
            val avatarUrl = me?.optString("avatar").orEmpty()
            Box(
                Modifier
                    .size(58.dp)
                    .background(Color.White.copy(alpha = 0.22f), CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (avatarUrl.isNotBlank()) {
                    coil.compose.AsyncImage(
                        model = avatarUrl,
                        contentDescription = "头像",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        name.take(1),
                        color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name, color = Color.White, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp
                )
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        roleText, fontSize = 11.sp, color = Color.White,
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(7.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    if (username.isNotEmpty()) {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "@$username", fontSize = 12.5.sp,
                            color = Color.White.copy(alpha = 0.78f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // ── 数据条（对齐套壳 .stats）──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatCell(me?.optLong("points", 0) ?: 0, "积分", Modifier.weight(1f))
            StatCell(docCount, "文档", Modifier.weight(1f))
            StatCell(spaceCount, "空间", Modifier.weight(1f))
        }

        // ── 分组列表（对齐套壳：我的内容 / 成长 / 管理 / 设置）──
        // 之前 14 条塞在同一张卡里，一路排到「退出登录」，就是「一堆东西全推到一起」。
        val contentItems = listOfNotNull(
            MenuItem(Icons.Filled.FolderOpen, "我的文件", "上传的图片与附件") { openRoute("files") },
            MenuItem(Icons.Filled.Star, "我的收藏", "收藏过的文档") { openRoute("favorites") },
            MenuItem(Icons.Filled.History, "浏览历史", "最近看过的文档") { openRoute("history") },
            MenuItem(Icons.Filled.Description, "全部文档", "跨空间聚合") { openRoute("all-docs") }
        )
        val growthItems = listOfNotNull(
            MenuItem(Icons.Filled.WorkspacePremium, "徽章成就", "成长记录") { openRoute("badges") },
            MenuItem(Icons.Filled.Insights, "我的数据", "阅读与创作统计") { openRoute("stats") },
            MenuItem(Icons.Filled.Explore, "发现文章", "浏览站内文章") { openRoute("discover") }
        )
        val adminItems = listOfNotNull(
            if (showAdmin) MenuItem(Icons.Filled.AdminPanelSettings, "后台管理", "用户 / AI 模型 / 审核 / 公告") { openRoute("admin") } else null,
            if (TokenStore.hasPerm("CORE_MAINTAIN")) MenuItem(Icons.Filled.Tune, "站点设置", "站点信息 / 水印 / 外观 / 二维码") { openRoute("site-settings") } else null
        )
        val settingItems = listOfNotNull(
            MenuItem(Icons.Filled.Palette, "外观", "跟随系统 / 浅色 / 深色") { openRoute("appearance") },
            MenuItem(
                Icons.Filled.Security, "应用锁",
                if (com.kb.kbplatform.shell.data.AppLock.enabled()) "已开启（PIN 保护）" else "未开启"
            ) { showLockDialog = true },
            MenuItem(Icons.Filled.SystemUpdate, "检查更新", updateSubtitle) { showUpdateDialog = true },
            MenuItem(Icons.Filled.Info, "关于", "版本 · 更新日志 · 开源协议") { openRoute("about") },
            MenuItem(Icons.Filled.Policy, "隐私政策与免责声明", "") { openRoute("privacy") },
            MenuItem(Icons.Filled.Public, "网页版全部功能", "尚未原生化的页面在这里打开") { openWeb("/m/", "网页版") }
        )

        GroupSection("我的内容", contentItems)
        GroupSection("成长与数据", growthItems)
        if (adminItems.isNotEmpty()) GroupSection("管理", adminItems, collapsible = true)
        GroupSection("设置", settingItems)

        // ── 退出登录（独立一张卡，红色，远离其它条目，避免误触）──
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .kbPress(scaleDown = 0.99f) { TokenStore.set(null) }
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.Logout, null,
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "退出登录", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // 版本号（底部一行，方便报障时对版本）
        Text(
            "知识库 ${com.kb.kbplatform.shell.BuildConfig.VERSION_NAME}",
            fontSize = 12.sp, color = KbTextDim,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 20.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
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

private data class MenuItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val tint: Color = Color.Unspecified,
    val onClick: () -> Unit
)

@Composable
private fun MenuRow(m: MenuItem) {
    val fg = if (m.tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else m.tint
    Row(
        Modifier
            .fillMaxWidth()
            // 套壳 .m-row：内边距 13/14、最小高度 52、按下底色变浅
            .kbPress(scaleDown = 1f, onClick = m.onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(m.icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = fg)
            if (m.subtitle.isNotEmpty()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    m.subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 数据条单格（套壳 .stat：数字 17 粗、说明 11 灰） */
@Composable
private fun StatCell(num: Long, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (num >= 0) num.toString() else "—",
            fontSize = 17.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = KbTextDim)
    }
}

/**
 * 分组（对齐套壳「我的」：12.5sp 淡灰小标题 + 一张卡内的行列表）。
 *
 * [collapsible] 用于「管理」这类低频分组：默认收起，只占一行标题，
 * 展开后才是条目——避免管理员看到一长串。
 */
@Composable
private fun GroupSection(
    title: String,
    items: List<MenuItem>,
    collapsible: Boolean = false
) {
    var expanded by rememberSaveable(title) { mutableStateOf(!collapsible) }
    val arrow by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "groupArrow"
    )
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (collapsible) {
                        Modifier.kbPress(scaleDown = 1f) { expanded = !expanded }
                    } else Modifier
                )
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                color = KbTextDim, letterSpacing = 0.3.sp, modifier = Modifier.weight(1f)
            )
            if (collapsible) {
                Text("${items.size}", fontSize = 12.sp, color = KbTextDim)
                Spacer(Modifier.size(4.dp))
                Icon(
                    Icons.Filled.ExpandMore, null,
                    tint = KbTextDim,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = arrow }
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            KbCard(Modifier.padding(horizontal = 16.dp)) {
                Column {
                    items.forEachIndexed { i, m ->
                        if (i > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 48.dp)
                                    .height(0.5.dp)
                                    .background(MaterialTheme.colorScheme.outline)
                            )
                        }
                        MenuRow(m)
                    }
                }
            }
        }
    }
}

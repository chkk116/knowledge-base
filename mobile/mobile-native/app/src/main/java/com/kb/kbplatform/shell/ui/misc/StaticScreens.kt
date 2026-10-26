/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.misc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.theme.KbTextDim

/**
 * 静态页与外观（对标 MobilePrivacy/MobileDisclaimer/MobileChangelog/MobileAbout/MobileAppearance）：
 * 文本从 assets/static/ 目录读取（构建时从前端对应 Vue 页面提取，保持内容同源）。
 */
@Composable
fun StaticTextScreen(name: String, title: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember(name) {
        try {
            ctx.assets.open("static/$name.txt").use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: Exception) {
            "内容加载失败：$name"
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = title, onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            text.split("\n").forEach { line ->
                if (line.isBlank()) {
                    Spacer(Modifier.height(6.dp))
                } else {
                    Text(
                        line,
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                    )
                    Spacer(Modifier.height(2.dp))
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

/** 关于（对标 MobileAbout）：版本信息 + 开源协议 + 更新日志入口 */
@Composable
fun AboutScreen(versionName: String, onOpenChangelog: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "关于", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            KbCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("知识库", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text("一站式企业知识枢纽", fontSize = 12.sp, color = KbTextDim)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "v$versionName · Kotlin + Compose 原生壳",
                        fontSize = 12.sp, color = KbTextDim
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            KbCard(Modifier.fillMaxWidth().clickable(onClick = onOpenChangelog)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("更新日志", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("›", fontSize = 16.sp, color = KbTextDim)
                }
            }
            Spacer(Modifier.height(12.dp))
            KbCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("开源许可", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "本应用源代码基于 GNU AGPL-3.0 许可证开源；" +
                            "个人/学习/研究使用可自由复制部署修改。",
                        fontSize = 12.sp, color = KbTextDim
                    )
                }
            }
        }
    }
}

/** 外观（对标 MobileAppearance）：跟随系统 / 浅色 / 深色 */
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val mode by AppThemeState.mode.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "外观", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            KbCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    listOf(
                        "system" to "跟随系统",
                        "light" to "浅色模式",
                        "dark" to "深色模式"
                    ).forEach { (v, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { AppThemeState.set(v) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = mode == v, onClick = { AppThemeState.set(v) })
                            Text(label, fontSize = 14.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "提示：部分页面的深色适配跟随站点外观设置（Web 端「外观设置」），此处为 App 全局模式。",
                fontSize = 11.sp, color = KbTextDim
            )
        }
    }
}

package com.kb.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URI

enum class MenuKind(val label: String) {
    HOME("首页"), DOCS("文章"), CHAT("AI 问答"), FILES("我的文件"), SETTINGS("设置")
}

@Composable
fun MainScreen(session: Session, onLogout: () -> Unit) {
    var selected by remember { mutableStateOf(MenuKind.HOME) }
    val host = remember(session) { runCatching { URI(session.server).host }.getOrNull() ?: session.server }

    Row(Modifier.fillMaxSize().background(KbBg)) {
        // ------- 侧栏 -------
        Column(
            Modifier
                .width(216.dp)
                .fillMaxHeight()
                .background(Color.White)
        ) {
            Spacer(Modifier.height(14.dp))
            // 品牌行
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(KbIconGradient(KbTileViolet.first, KbTileViolet.second)),
                    contentAlignment = Alignment.Center,
                ) { BrandGlyphMini(Color.White, Modifier.size(19.dp)) }
                Spacer(Modifier.width(10.dp))
                Text("知识库", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = KbTextMain)
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (kind in MenuKind.entries) {
                    val sel = kind == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (sel) KbSidebarSelected else Color.Transparent)
                            .clickable { selected = kind }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tile = when (kind) {
                            MenuKind.HOME -> KbTileBlue
                            MenuKind.DOCS -> KbTileViolet
                            MenuKind.CHAT -> KbTileGreen
                            MenuKind.FILES -> KbTileAmber
                            MenuKind.SETTINGS -> KbTileBlue
                        }
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(KbIconGradient(tile.first, tile.second)),
                            contentAlignment = Alignment.Center,
                        ) { MenuGlyph(kind, Color.White, Modifier.size(14.dp)) }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            kind.label,
                            fontSize = 13.sp,
                            color = if (sel) KbTextMain else KbTextSub,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            HorizontalDivider()
            // 用户区
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(KbIconGradient(KbTileBlue.first, KbTileBlue.second)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        session.username.take(1).uppercase(),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(session.username, fontSize = 12.5.sp, color = KbTextMain, maxLines = 1)
                    Text(host, fontSize = 10.sp, color = KbTextSub, maxLines = 1)
                }
                TextButton(onClick = onLogout, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("退出", fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // ------- 右侧 1px 分隔 -------
        Box(Modifier.width(1.dp).fillMaxHeight().background(KbBorder))

        // ------- 内容 -------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // 头部
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(Color.White)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selected.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = KbTextMain)
                Spacer(Modifier.weight(1f))
                Text("已连接 $host", fontSize = 11.sp, color = KbTextSub)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(KbBorder))

            // 卡片内容区
            Box(Modifier.fillMaxSize().padding(20.dp)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, KbBorder, RoundedCornerShape(12.dp)),
                ) {
                    when (selected) {
                        MenuKind.HOME -> HomePanel(session)
                        else -> PlaceholderPanel(selected)
                    }
                }
            }
        }
    }
}

@Composable
private fun HorizontalDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(KbBorder))
}

@Composable
private fun HomePanel(session: Session) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            "原生桌面端骨架已就绪",
            style = androidx.compose.ui.text.TextStyle(brush = Brush.linearGradient(listOf(KbPrimaryLight, KbPrimaryDeep))),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(14.dp))
        val lines = listOf(
            "原生 UI：Compose Multiplatform（Kotlin），非 WebView 套壳",
            "登录：滑块验证码 + 账号密码，与 Web 端同一套接口",
            "下一步：接入 WebView2 内嵌区承载 Tiptap/Yjs 文档编辑器",
        )
        lines.forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(KbPrimaryLight)
                )
                Spacer(Modifier.width(10.dp))
                Text(line, fontSize = 13.sp, color = KbTextSub)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PlaceholderPanel(kind: MenuKind) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val tile = when (kind) {
                MenuKind.DOCS -> KbTileViolet
                MenuKind.CHAT -> KbTileGreen
                MenuKind.FILES -> KbTileAmber
                else -> KbTileBlue
            }
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(KbIconGradient(tile.first, tile.second)),
                contentAlignment = Alignment.Center,
            ) { MenuGlyph(kind, Color.White, Modifier.size(26.dp)) }
            Spacer(Modifier.height(12.dp))
            Text(
                when (kind) {
                    MenuKind.DOCS -> "文档列表（下一步：左侧列表原生 + 编辑区 WebView2）"
                    MenuKind.CHAT -> "AI 问答（随后接入）"
                    MenuKind.FILES -> "我的文件（随后接入）"
                    else -> "设置（随后接入）"
                },
                fontSize = 13.sp,
                color = KbTextSub,
            )
        }
    }
}

/** 菜单线框图标（与 Web 1.3.51 细线风格一致：1.5px 线 + 圆角端点） */
@Composable
fun MenuGlyph(kind: MenuKind, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.width / 24f
        val stroke = Stroke(width = 1.6f * u * 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), stroke.width, StrokeCap.Round)

        when (kind) {
            MenuKind.HOME -> {
                line(3f, 11f, 12f, 4f); line(12f, 4f, 21f, 11f)
                p.moveTo(6f, 9f); p.lineTo(6f, 20f); p.lineTo(18f, 20f); p.lineTo(18f, 9f)
                line(10.5f, 20f, 10.5f, 14.5f); line(10.5f, 14.5f, 13.5f, 14.5f); line(13.5f, 14.5f, 13.5f, 20f)
            }
            MenuKind.DOCS -> {
                p.moveTo(7f, 4f); p.lineTo(17f, 4f); p.lineTo(17f, 20f); p.lineTo(7f, 20f); p.close()
                line(10f, 9.5f, 14f, 9.5f); line(10f, 12.5f, 14f, 12.5f); line(10f, 15.5f, 13f, 15.5f)
            }
            MenuKind.CHAT -> {
                p.moveTo(4f, 5.5f); p.lineTo(20f, 5.5f); p.lineTo(20f, 15.5f); p.lineTo(11f, 15.5f)
                p.lineTo(7.5f, 19f); p.lineTo(7.5f, 15.5f); p.lineTo(4f, 15.5f); p.close()
                line(8.5f, 10.5f, 8.5f, 10.6f); line(12f, 10.5f, 12f, 10.6f); line(15.5f, 10.5f, 15.5f, 10.6f)
            }
            MenuKind.FILES -> {
                p.moveTo(3.5f, 6.5f); p.lineTo(9.5f, 6.5f); p.lineTo(11.5f, 8.5f); p.lineTo(20.5f, 8.5f)
                p.lineTo(20.5f, 18.5f); p.lineTo(3.5f, 18.5f); p.close()
            }
            MenuKind.SETTINGS -> {
                line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f)
                drawCircle(tint, 2.1f * u, Offset(14.5f * u, 7f * u), style = Stroke(stroke.width))
                drawCircle(tint, 2.1f * u, Offset(8.5f * u, 12f * u), style = Stroke(stroke.width))
                drawCircle(tint, 2.1f * u, Offset(16f * u, 17f * u), style = Stroke(stroke.width))
            }
        }
        if (!p.isEmpty) drawPath(p, tint, style = stroke)
    }
}

/** 侧栏品牌小图标（两笔书 + 架线） */
@Composable
private fun BrandGlyphMini(tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.width
        val path = Path()
        path.moveTo(s * 0.14f, s * 0.66f); path.lineTo(s * 0.40f, s * 0.30f); path.lineTo(s * 0.48f, s * 0.38f)
        path.moveTo(s * 0.32f, s * 0.84f); path.lineTo(s * 0.60f, s * 0.48f); path.lineTo(s * 0.70f, s * 0.58f)
        path.moveTo(s * 0.52f, s * 0.96f); path.lineTo(s * 0.78f, s * 0.66f); path.lineTo(s * 0.90f, s * 0.78f)
        drawPath(path, tint, style = Stroke(width = s * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(tint, Offset(s * 0.08f, s * 0.98f), Offset(s * 0.94f, s * 0.98f), s * 0.08f, StrokeCap.Round)
    }
}

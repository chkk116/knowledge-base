package com.kb.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 桌面端轻量 UI 套件（对标 Web 端的 Element Plus 观感）。
 *
 * 目的：把「一堆 11sp 灰字 TextButton + 无 hover 的平铺列表」换成有层级、有状态、有留白的组件，
 * 避免各面板各写一套。只做视觉，不涉及业务；旧面板可以逐步替换。
 */

// ---------- 令牌 ----------
val KbCardBg = Color(0xFFFFFFFF)
val KbCardBorder = Color(0xFFEAECF0)
val KbCardBorderHover = Color(0xFFC7D2FE)
val KbHoverBg = Color(0xFFF7F8FA)
val KbSelectedBg = Color(0xFFF0F4FF)
val KbAccent = Color(0xFF4F46E5)
val KbAccentSoft = Color(0xFFEEF2FF)
val KbRadius = 12.dp

/** 三档色调：主色 / 成功 / 警告 / 危险 / 中性 */
enum class KbTone { Primary, Success, Warning, Danger, Neutral }

private fun toneColors(tone: KbTone): Pair<Color, Color> = when (tone) {
    KbTone.Primary -> Color(0xFFEEF2FF) to Color(0xFF4338CA)
    KbTone.Success -> Color(0xFFECFDF5) to Color(0xFF047857)
    KbTone.Warning -> Color(0xFFFFFBEB) to Color(0xFFB45309)
    KbTone.Danger -> Color(0xFFFEF2F2) to Color(0xFFB91C1C)
    KbTone.Neutral -> Color(0xFFF3F4F6) to Color(0xFF4B5563)
}

/** 白卡：圆角 + 细边 + 极轻阴影（列表容器/阅读区用） */
@Composable
fun KbSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(KbRadius),
    borderColor: Color = KbCardBorder,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(KbCardBg)
            .border(1.dp, borderColor, shape)
            .shadow(1.dp, shape, clip = false)
    ) { content() }
}

/**
 * 可点卡片：支持 hover / 选中态（选中 = 浅主色底 + 左侧 3dp 主色竖条）。
 * 用于左栏空间项、中栏文档项。
 */
@Composable
fun KbSelectCard(
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 9.dp),
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .clip(shape)
            .background(
                when {
                    selected -> KbSelectedBg
                    hovered -> KbHoverBg
                    else -> Color.Transparent
                }
            )
            .then(if (hovered && !selected) Modifier.border(1.dp, KbCardBorderHover, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 选中竖条：占位常驻，避免选中时文字整体右移
        Box(
            Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) KbAccent else Color.Transparent)
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** 方形渐变图标块（空间/菜单缩略图） */
@Composable
fun KbGlyphTile(
    letter: String,
    gradient: Pair<Color, Color>,
    size: Int = 26,
    corner: Int = 8,
) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(corner.dp))
            .background(Brush.linearGradient(listOf(gradient.first, gradient.second))),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter.take(1), fontSize = (size * 0.45f).sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/** 小标签（标签/权限/类型）：软底 + 深字 + 圆角 */
@Composable
fun KbTag(text: String, tone: KbTone = KbTone.Neutral, maxWidth: Int = 160) {
    val (bg, fg) = toneColors(tone)
    Text(
        text,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Medium,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = maxWidth.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** 主操作按钮（实心主色，用于对话框确认） */
@Composable
fun KbPrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (enabled) KbAccent else Color(0xFFC7CBD4)
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

/** 次级按钮（白底细边） */
@Composable
fun KbGhostAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .hoverable(interaction)
            .clip(shape)
            .background(if (hovered) KbHoverBg else Color.Transparent)
            .border(1.dp, KbCardBorder, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = if (enabled) KbTextMain else KbTextSub,
        )
    }
}

/** 图标式小按钮（圆形，hover 有底），用于栏头「＋ / 刷新 / 更多」 */
@Composable
fun KbMiniIconButton(label: String, hint: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .size(24.dp)
            .hoverable(interaction)
            .clip(CircleShape)
            .background(if (hovered) KbAccentSoft else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = if (hovered) KbAccent else KbTextSub,
        )
    }
}

/** 空态：图标圆底 + 标题 + 副文案 + 可选操作 */
@Composable
fun KbEmptyState(
    glyph: String,
    title: String,
    desc: String = "",
    actionText: String = "",
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(KbAccentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, fontSize = 20.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = KbTextMain)
        if (desc.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(desc, fontSize = 11.5.sp, color = KbTextSub, lineHeight = 17.sp)
        }
        if (actionText.isNotBlank() && onAction != null) {
            Spacer(Modifier.height(10.dp))
            KbGhostAction(actionText, onClick = onAction)
        }
    }
}

/** 栏目标题（左栏/中栏、对话框内小节） */
@Composable
fun KbColumnHeader(title: String, subtitle: String = "", actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 10.5.sp, color = KbTextSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}
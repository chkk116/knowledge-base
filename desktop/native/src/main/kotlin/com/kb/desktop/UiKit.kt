package com.kb.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

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
            // 阴影先画、再裁切卡片内容：反过来会把阴影盖在背景和边框上面，
            // 且 clip=false 会让阴影渗进密集列表的相邻卡片
            .shadow(1.dp, shape, clip = true)
            .clip(shape)
            .background(KbCardBg)
            .border(1.dp, borderColor, shape)
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
            .clickable(onClick = onClick)
            // 调用处传进来的「刷新 / 新建团队空间」提示不能丢：图标钮要有无障碍标签
            .semantics { contentDescription = hint },
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

// ---------- 设置页分组（对标 Web 端设置卡片：白卡 + 小节标题 + 键值行） ----------

/** 设置分组：白卡 + 小节标题 + 内容；设置页、关于页统一用它，避免每个页面自己画边框 */
@Composable
fun KbSettingGroup(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    KbSurface(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** 设置项：左侧标签固定宽、右侧值右对齐（长值自动换行到右侧列，不会顶到标签上） */
@Composable
fun KbSettingRow(label: String, value: String, labelWidth: Int = 92) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.5.sp, color = KbTextSub, modifier = Modifier.width(labelWidth.dp))
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontSize = 12.5.sp,
            color = KbTextMain,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

// ---------- 骨架屏（列表加载态，替代「加载中…」文字） ----------

/**
 * 列表骨架屏：灰条 + 呼吸动画。只表达「形状与位置」，不假装有数据。
 * 用在加载态，切到真实列表时视觉位置一致，不会整页跳一下。
 */
@Composable
fun KbSkeletonList(rows: Int = 6, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "kb-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "kb-skeleton-alpha",
    )
    val bar = Color(0xFFE9ECF2).copy(alpha = alpha)
    val barSoft = Color(0xFFF1F3F7).copy(alpha = alpha)
    Column(modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 6.dp)) {
        repeat(rows) { i ->
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(bar))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier.fillMaxWidth(0.45f + 0.12f * (i % 3))
                            .height(10.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(bar)
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth(0.28f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(barSoft))
                }
                Spacer(Modifier.width(16.dp))
                Box(Modifier.width(52.dp).height(22.dp).clip(RoundedCornerShape(6.dp)).background(barSoft))
            }
        }
    }
}

// ---------- 对话框（对标 Web 端 el-dialog：白底圆角 + 标题栏 + 右上关闭） ----------

/**
 * 应用内统一的对话框外壳。
 *
 * 与 Material3 `AlertDialog` **参数兼容**（onDismissRequest / title / text / confirmButton / dismissButton），
 * 所以存量调用只需把 `AlertDialog(` 换成 `KbDialog(` —— 视觉换成项目色板，逻辑一行不改。
 *
 * 差异点：标题栏右侧固定一个「×」（Material 只在点遮罩/ESC 关闭，桌面用户常找不到出口）；
 * 内容区限高 560dp 可滚动，避免长表单把对话框撑出屏幕。
 */
@Composable
fun KbDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit = EMPTY_CONTENT,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    width: Dp = 520.dp,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        KbSurface(shape = RoundedCornerShape(14.dp)) {
            // 宽度跟随内容（存量对话框多是 460dp 的固定宽 Column），最少 320 以免窄成一列
            Column(Modifier.widthIn(min = 320.dp, max = width).padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { title?.invoke() }
                    Spacer(Modifier.width(8.dp))
                    KbMiniIconButton("✕", "关闭") { onDismissRequest() }
                }
                Spacer(Modifier.height(10.dp))
                HorizontalRule()
                Spacer(Modifier.height(12.dp))
                // 限高之外还要能滚：超长表单直接 overflow 会压住下面的按钮，末尾字段永远点不到
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                ) { text?.invoke() }
                if (dismissButton != null || confirmButton !== EMPTY_CONTENT) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalRule()
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        dismissButton?.invoke()
                        Spacer(Modifier.width(8.dp))
                        confirmButton()
                    }
                }
            }
        }
    }
}

private val EMPTY_CONTENT: @Composable () -> Unit = {}

/** 表头：cells = 列名 to 相对权重 */
@Composable
fun KbTableHead(cells: List<Pair<String, Float>>, horizontal: Int = 18) {
    Row(
        Modifier.fillMaxWidth().background(KbTableHeadBg).padding(horizontal = horizontal.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((label, weight) in cells) {
            Text(
                label,
                modifier = Modifier.weight(weight),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = KbTextSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalRule()
}

/** 表格行：hover 有底，可点行整行响应 */
@Composable
fun KbTableRow(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    horizontal: Int = 18,
    vertical: Int = 8,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .background(if (selected) KbSelectedBg else if (hovered) KbHoverBg else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = horizontal.dp, vertical = vertical.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
    HorizontalRule()
}

@Composable
private fun HorizontalRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF1F5F9)))
}

val KbTableHeadBg = Color(0xFFF7F8FA)

// ---------- 分页（对标 Web 端 el-pagination：右对齐、当前页实心主色） ----------

/**
 * 服务端分页条。page 为 0 基（与后端一致），显示为 1 基。
 * size 可空：传 null 则不显示「每页 N 条」。
 */
@Composable
fun KbPager(
    total: Long,
    page: Int,
    size: Int,
    modifier: Modifier = Modifier,
    onPage: (Int) -> Unit,
    onSize: ((Int) -> Unit)? = null,
    sizeOptions: List<Int> = listOf(20, 50, 100),
) {
    val pages = if (size <= 0) 1 else (((total + size - 1) / size).toInt()).coerceAtLeast(1)
    // 总条数变少（删了最后一条 / 刷新后结果变短）时调用方未必把 page 夹回来：
    // 这时页码窗口里没有高亮项，「‹」还会一路点着往外减。渲染与翻页都以夹紧后的值为准。
    val pageNow = page.coerceIn(0, pages - 1)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("共 $total 条", fontSize = 11.sp, color = KbTextSub)
        Spacer(Modifier.weight(1f))
        if (onSize != null) {
            KbPagerCell("每页 $size 条") {
                val i = sizeOptions.indexOf(size)
                onSize(sizeOptions[(if (i < 0) 0 else i + 1) % sizeOptions.size])
            }
            Spacer(Modifier.width(6.dp))
        }
        KbPagerCell("‹", enabled = pageNow > 0) { onPage(pageNow - 1) }
        Spacer(Modifier.width(4.dp))
        // 页码窗口：当前页居中，最多 5 个，两端用省略号 + 首末页补齐
        val start = minOf((pageNow - 2).coerceAtLeast(0), (pages - 5).coerceAtLeast(0))
        val end = minOf(start + 4, pages - 1)
        if (start > 0) {
            KbPagerCell("1") { onPage(0) }
            if (start > 1) Text("…", fontSize = 11.sp, color = KbTextSub, modifier = Modifier.padding(horizontal = 4.dp))
        }
        for (p in start..end) {
            KbPagerCell("${p + 1}", active = p == pageNow) { onPage(p) }
        }
        if (end < pages - 1) {
            if (end < pages - 2) Text("…", fontSize = 11.sp, color = KbTextSub, modifier = Modifier.padding(horizontal = 4.dp))
            KbPagerCell("$pages") { onPage(pages - 1) }
        }
        Spacer(Modifier.width(4.dp))
        KbPagerCell("›", enabled = pageNow < pages - 1) { onPage(pageNow + 1) }
    }
}

@Composable
private fun KbPagerCell(label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .size(width = if (label.length > 2) 62.dp else 26.dp, height = 26.dp)
            .hoverable(interaction)
            .clip(shape)
            .background(if (active) KbAccent else if (hovered && enabled) KbHoverBg else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            color = when {
                active -> Color.White
                !enabled -> Color(0xFFC7CBD4)
                hovered -> KbAccent
                else -> KbTextMain
            },
            maxLines = 1,
        )
    }
}

/** JS 字符串字面量转义（token 注入 initScript / 内嵌页脚本用）：
 *  单引号包裹，转义反斜杠、单引号与换行，避免令牌里的字符拼坏注入的脚本。 */
internal fun jsStr(s: String): String =
    "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "\\r").replace("\n", "\\n") + "'"

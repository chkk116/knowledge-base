package com.kb.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


// 管理模块（2026-11-16）：看板 / 审核 / 用户 / 部门 / 公告 / 审计 / 登录日志
//   权限全部由后端判定（ADMIN 或细粒度码），403 文案原样透出；
//   只做日常运维能闭环的轻操作，重配置（外观/水印/SSO/AI 模型…）仍回 Web 端。
//   列表均取第一页 50 条（不做翻页），避免客户端变成半个后台。

internal val AdminRailBg = Color(0xFFFAFBFC)
internal val AdminErrRed = Color(0xFFDC2626)

private val ADMIN_TABS = listOf(
    "review" to "审核队列",
    "grading" to "阅卷",
    "reports" to "举报",
    "users" to "用户",
    "depts" to "部门",
    "announce" to "公告",
    "banned" to "违禁词",
    "taxonomy" to "分类标签",
    "docs" to "帖子",
    "spaces" to "空间申请",
    "recycle" to "回收站",
    "stats" to "看板",
    "audit" to "操作审计",
    "logins" to "登录日志",
    "files" to "孤儿附件",
)

@Composable
fun AdminPanel(session: Session, onOpenDoc: (Long) -> Unit = {}) {
    var tab by remember { mutableStateOf("review") }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(150.dp).fillMaxHeight().background(AdminRailBg)) {
            Text(
                "管理",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = KbTextMain,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
            HorizontalDivider(color = KbBorder)
            Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for ((key, label) in ADMIN_TABS) {
                    val sel = tab == key
                    KbSelectCard(
                        selected = sel,
                        onClick = { tab = key },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp),
                    ) {
                        Text(
                            label,
                            fontSize = 12.sp,
                            color = if (sel) KbAccent else KbTextMain,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(KbBorder))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            // tab 切换淡入淡出：避免列表整块瞬移，切换时更容易看出「换页了」
            Crossfade(targetState = tab, animationSpec = tween(160), label = "admin-tab") { t ->
                when (t) {
                    "review" -> ReviewTab(session)
                    "grading" -> GradingTab(session)
                    "reports" -> ReportsTab(session)
                    "users" -> UsersTab(session)
                    "depts" -> DepartmentsTab(session)
                    "announce" -> AnnouncementsTab(session)
                    "banned" -> BannedWordsTab(session)
                    "taxonomy" -> TaxonomyTab(session)
                    "docs" -> DocManageTab(session)
                    "spaces" -> SpacesTab(session)
                    "recycle" -> RecycleTab(session)
                    "files" -> OrphanFilesTab(session)
                    "stats" -> StatsTab(session, onOpenDoc)
                    "audit" -> AuditTab(session)
                    "logins" -> LoginLogsTab(session)
                    else -> AdminHint("未知的管理面板：$t")
                }
            }
        }
    }
}


// ---------------- 小件 ----------------

@Composable
internal fun AdminHeader(title: String, subtitle: String, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 11.sp, color = KbTextSub, maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
        KbMiniIconButton("↻", "刷新") { onRefresh() }
    }
    HorizontalDivider(color = KbBorder)
}

@Composable
internal fun AdminChip(text: String, bg: Color, fg: Color) {
    Text(
        text,
        fontSize = 9.5.sp,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
internal fun AdminButton(label: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val fg = if (danger) Color(0xFFB91C1C) else KbPrimary
    Text(
        label,
        fontSize = 11.5.sp,
        color = if (enabled) fg else Color(0xFF9CA3AF),
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (!enabled) Color(0xFFF3F4F6) else if (danger) Color(0xFFFEF2F2) else Color(0xFFEEF2FF))
            .border(1.dp, if (enabled) fg.copy(alpha = 0.3f) else KbBorder, RoundedCornerShape(7.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
internal fun AdminHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.sp, color = KbTextSub, textAlign = TextAlign.Center, lineHeight = 19.sp)
    }
}

@Composable
internal fun AdminError(message: String, onRetry: () -> Unit) {
    // 后端 403 的原文是管理员排查权限的关键线索：默认折叠 4 行，点一下展开全文，不能被悄悄截断
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message, fontSize = 12.sp, color = AdminErrRed, textAlign = TextAlign.Center,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { expanded = true },
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRetry) { Text("重试", fontSize = 12.sp) }
    }
}

@Composable
internal fun AdminToast(text: String?) {
    if (text == null) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .padding(top = 14.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF323232))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) { Text(text, fontSize = 12.sp, color = Color.White, maxLines = 2) }
    }
}

internal fun fmtAdminTime(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    // 只识别标准 ISO 时间戳；时区 / 毫秒只用于判定格式，不参与展示。
    // 其它形态（日期、已本地化文本、短串）原样返回，不再误伤任意位置的 'T'。
    val m = Regex("^(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2})(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?$")
        .matchEntire(raw)
    return if (m != null) "${m.groupValues[1]} ${m.groupValues[2]}" else raw
}

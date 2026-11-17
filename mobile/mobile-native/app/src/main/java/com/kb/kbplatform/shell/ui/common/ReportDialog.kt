/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 举报原因固定六项，抽到顶层避免每次重组都重新分配 */
private val REPORT_REASONS = listOf("垃圾广告", "辱骂攻击", "违法违规", "侵权内容", "虚假信息", "其它")
private const val DETAIL_MAX = 2000

/**
 * 通用举报弹窗（文章 / 评论 / 用户 / 私信都用它）。
 *
 * 口径与 Web 端 `ReportDialog.vue` 一致：reason 必填（后端 ≤120 字），detail 选填（≤2000 字），
 * 同一人对同一对象 10 分钟内只收一次。targetType 取值见后端 `ReportController.TYPES`：
 * POST（公开文章）/ COMMENT / DOC（共享文档）/ DM / USER / FEEDBACK。
 */
@Composable
fun KbReportDialog(
    targetType: String,
    targetId: Long,
    preview: String,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("垃圾广告") }
    var detail by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val reasons = REPORT_REASONS

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("举报", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                if (preview.isNotBlank()) {
                    Text("「${preview.take(40)}」", fontSize = 12.sp, color = KbTextDim, maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                }
                reasons.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { r ->
                            KbChip(r, selected = reason == r, onClick = { reason = r })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                OutlinedTextField(
                    value = detail,
                    onValueChange = { if (it.length <= DETAIL_MAX) detail = it },
                    label = { Text("补充说明（选填，最多 $DETAIL_MAX 字）", fontSize = 11.5.sp) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    submitting = true
                    scope.launch {
                        // 失败时保留弹窗和已填说明，让用户能改后重试；取消要向上抛，别吞成「举报失败」
                        try {
                            KbApi.reportContent(targetType, targetId, reason, detail)
                            onResult("举报已提交，管理员会尽快处理")
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            onResult(e.message ?: "举报失败")
                        } finally {
                            submitting = false
                        }
                    }
                },
            ) { Text(if (submitting) "提交中…" else "提交举报", fontSize = 13.sp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消", fontSize = 13.sp) }
        },
    )
}
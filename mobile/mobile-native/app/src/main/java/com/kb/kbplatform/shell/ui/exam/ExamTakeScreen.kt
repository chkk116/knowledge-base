/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.exam

import androidx.compose.runtime.Composable
import com.kb.kbplatform.shell.ui.web.WebScreen

/**
 * 考试作答（原生壳）→ 内嵌 /m/exam/:id。
 *
 * 决策（2026-10-28）：答题涉及倒计时、答题卡、选项富文本（HtmlSanitizer 输出的 HTML）、
 * 交卷校验与练习模式逐题判分 —— 与网页端强耦合，原生复刻极易出现判分口径不一致。
 * 内嵌后与网页共用同一套答题与判分逻辑。
 */
@Composable
fun ExamTakeScreen(paperId: Long, titleHint: String, onExit: () -> Unit) {
    WebScreen(
        path = "/m/exam/$paperId",
        title = titleHint.ifBlank { "考试" },
        onBack = onExit
    )
}

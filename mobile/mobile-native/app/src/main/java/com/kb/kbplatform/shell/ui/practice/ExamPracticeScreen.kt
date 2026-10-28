/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.practice

import androidx.compose.runtime.Composable
import com.kb.kbplatform.shell.ui.web.WebScreen

/**
 * 练习（原生壳）→ 内嵌 /m/exam/practice。
 *
 * 练习与考试共用会话式判分（sessionId + 服务端判分），原生复刻容易出现
 * 「答案提前下发 / 判分口径不一致」的问题，统一走网页实现。
 */
@Composable
fun ExamPracticeScreen(onBack: () -> Unit = {}) {
    WebScreen(path = "/m/exam/practice", title = "练习", onBack = onBack)
}

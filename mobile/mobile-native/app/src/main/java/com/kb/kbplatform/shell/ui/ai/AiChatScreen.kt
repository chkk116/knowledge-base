/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.ai

import androidx.compose.runtime.Composable
import com.kb.kbplatform.shell.ui.web.WebScreen

/**
 * AI 问答（原生壳）→ 内嵌 /m/ai。
 *
 * 决策（2026-10-28）：AI 对话含流式 Markdown 渲染、工具调用轨迹（ReAct）、
 * 会话批量管理等，原生复刻成本高且永远落后网页端；改为内嵌，功能与网页一致。
 */
@Composable
fun AiChatScreen(onBack: () -> Unit) {
    WebScreen(path = "/m/ai", title = "AI 问答", onBack = onBack)
}

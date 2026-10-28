/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.edit

import androidx.compose.runtime.Composable
import com.kb.kbplatform.shell.ui.web.WebScreen

/**
 * 文档编辑（原生壳）→ 内嵌移动端网页编辑器。
 *
 * 决策（2026-10-28）：编辑器这类富交互页面不再用 Compose 复刻。
 * 原因：流程图/思维导图/表格是 TipTap 自定义节点（靠 Vue Flow 绘制），
 * 且编辑依赖 Yjs 实时协同 —— 原生 REST 保存只能"后写覆盖"，两人同改必冲突。
 * 内嵌 /m/ 页面后，编辑能力、协同、撤销重做与网页端完全一致，
 * 登录态由 WebScreen 的令牌桥接注入。
 */
@Composable
fun DocEditScreen(shareId: Long, docId: Long, titleHint: String, onBack: () -> Unit) {
    WebScreen(
        path = "/m/doc/$shareId/$docId/edit",
        title = titleHint.ifBlank { "编辑文档" },
        onBack = onBack
    )
}

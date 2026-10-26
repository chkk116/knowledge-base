/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 套壳配色还原（2026-10-26）。
 *
 * 数值**逐项抄自 `frontend/src/mobile.css`**，原生端不再自己发明颜色：
 * - `KbTone`  ← `.q-ico.blue/orange/green/cyan/gray/purple/red`（浅底 + 同色深字）
 *               以及 `.offline-card`（#fff7ed/#9a3412）、`.plan-card`（#ecfdf5/#065f46）
 * - `kbIconGradient` ← `.m-ico.*`（135° 渐变瓦片 + 白色图标）
 *
 * 深色值同样取自 `html.dark` 段落，不要在页面里写死十六进制。
 */
enum class KbToneKind { Primary, Blue, Orange, Green, Cyan, Gray, Purple, Red }

data class KbTone(val bg: Color, val fg: Color)

@Composable
fun kbTone(kind: KbToneKind): KbTone {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) {
        when (kind) {
            KbToneKind.Primary -> KbTone(Color(0xFF16233A), Color(0xFF4C8DFF))
            KbToneKind.Blue -> KbTone(Color(0xFF12253D), Color(0xFF60A5FA))
            KbToneKind.Orange -> KbTone(Color(0xFF3A2411), Color(0xFFFB923C))
            KbToneKind.Green -> KbTone(Color(0xFF0E2E26), Color(0xFF34D399))
            KbToneKind.Cyan -> KbTone(Color(0xFF0E2E33), Color(0xFF67E8F9))
            KbToneKind.Gray -> KbTone(Color(0xFF24262B), Color(0xFF98A2B3))
            KbToneKind.Purple -> KbTone(Color(0xFF241F3D), Color(0xFFA78BFA))
            KbToneKind.Red -> KbTone(Color(0xFF3A1C1C), Color(0xFFF87171))
        }
    } else {
        when (kind) {
            KbToneKind.Primary -> KbTone(Color(0xFFE8F3FF), Color(0xFF1677FF))
            KbToneKind.Blue -> KbTone(Color(0xFFEFF8FF), Color(0xFF1570EF))
            KbToneKind.Orange -> KbTone(Color(0xFFFFF7ED), Color(0xFFEA580C))
            KbToneKind.Green -> KbTone(Color(0xFFECFDF5), Color(0xFF059669))
            KbToneKind.Cyan -> KbTone(Color(0xFFECFEFF), Color(0xFF0891B2))
            KbToneKind.Gray -> KbTone(Color(0xFFF2F4F7), Color(0xFF667085))
            KbToneKind.Purple -> KbTone(Color(0xFFF4F1FF), Color(0xFF7C5CFF))
            KbToneKind.Red -> KbTone(Color(0xFFFEF2F2), Color(0xFFDC2626))
        }
    }
}

/**
 * `.m-ico` 的 135° 渐变对（起 → 止）。
 * Compose 的 `Brush.linearGradient` 默认左上到右下，与 CSS 的 135deg 一致。
 */
fun kbIconGradient(kind: KbToneKind): List<Color> = when (kind) {
    KbToneKind.Primary -> listOf(Color(0xFF1677FF), Color(0xFF0958D9))
    KbToneKind.Blue -> listOf(Color(0xFF4F86FF), Color(0xFF1677FF))
    KbToneKind.Orange -> listOf(Color(0xFFFFB340), Color(0xFFFF7D00))
    KbToneKind.Green -> listOf(Color(0xFF3DDC84), Color(0xFF00B42A))
    KbToneKind.Cyan -> listOf(Color(0xFF33C8B0), Color(0xFF0AB6B6))
    KbToneKind.Gray -> listOf(Color(0xFFA3AAB8), Color(0xFF8A8F99))
    KbToneKind.Purple -> listOf(Color(0xFFA78BFA), Color(0xFF7C5CFF))
    KbToneKind.Red -> listOf(Color(0xFFFF6B8A), Color(0xFFFF375F))
}

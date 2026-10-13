<script setup>
/**
 * 菜单图标（双色调细线风格）
 *
 * 设计语言（对齐参考图那种「整」的观感）：
 *   · 全部同一个品牌靛蓝（--mi-color，默认 #4f46e5），不再一 icon 一颜色
 *   · 细线描边（1.9，圆头圆角）+ 同色低透明度填充（fill-opacity .26）做层次
 *   · 24×24 网格、统一视觉重量、无渐变无投影
 * 关键：颜色只由 CSS `color` 驱动，填充用 currentColor + fill-opacity，
 *       所以换色 / 暗色适配都只改一处。
 * 用法：<MenuIcon name="House" />，name 沿用 Element Plus 图标名，菜单数据结构无需改动。
 * 未收录的名字回退为一枚中性圆点，保证不出现空白。
 */
import { computed } from 'vue'

const props = defineProps({
  name: { type: String, default: '' },
  size: { type: [Number, String], default: 20 },
})

// 描边与圆角统一在模板的 <g> 上给，这里只写形状；
// 需要「填充做层次」的路径加 fill="currentColor" fill-opacity=".26"；
// 需要「实心强调」的加 fill="currentColor" stroke="none"。
const ICONS = {
  House: `<path d="M4 10.6 12 4.2l8 6.4V19a1.6 1.6 0 0 1-1.6 1.6H5.6A1.6 1.6 0 0 1 4 19v-8.4Z" fill="currentColor" fill-opacity=".26"/><path d="M9.6 20.6v-5.4h4.8v5.4"/>`,
  EditPen: `<path d="M4.2 20.4l3.9-1L18.4 9.1a1.7 1.7 0 0 0 0-2.4l-1.1-1.1a1.7 1.7 0 0 0-2.4 0L4.6 15.9l-1 3.9c-.1.4.3.8.6.6Z" fill="currentColor" fill-opacity=".26"/><path d="M14.2 6.6l3.2 3.2"/>`,
  Files: `<path d="M8.6 4.6h8.2a1.6 1.6 0 0 1 1.6 1.6v11.6"/><rect x="4.6" y="7" width="11" height="13.4" rx="1.8" fill="currentColor" fill-opacity=".26"/><path d="M8 11.6h4.2M8 15.2h4.2"/>`,
  Delete: `<path d="M5.6 7.6h12.8l-.8 11.4a1.8 1.8 0 0 1-1.8 1.7H8.2a1.8 1.8 0 0 1-1.8-1.7L5.6 7.6Z" fill="currentColor" fill-opacity=".26"/><path d="M9.4 4.6h5.2a1 1 0 0 1 1 1v2h-7.2v-2a1 1 0 0 1 1-1Z"/><path d="M3.8 7.6h16.4M10 11v6.4M14 11v6.4"/>`,
  Notification: `<path d="M12 3.6a5.2 5.2 0 0 0-5.2 5.2c0 3.6-1.1 4.7-1.7 5.3-.3.3-.1.9.4.9h13c.5 0 .7-.6.4-.9-.6-.6-1.7-1.7-1.7-5.3A5.2 5.2 0 0 0 12 3.6Z" fill="currentColor" fill-opacity=".26"/><path d="M9.9 18.6a2.1 2.1 0 0 0 4.2 0"/>`,
  Star: `<path d="M12 3.8l2.6 5.2 5.7.8-4.1 4 1 5.7-5.2-2.7-5.2 2.7 1-5.7-4.1-4 5.7-.8L12 3.8Z" fill="currentColor" fill-opacity=".26"/>`,
  Clock: `<circle cx="12" cy="12" r="8.4" fill="currentColor" fill-opacity=".26"/><path d="M12 7.4V12l3.1 1.9"/>`,
  Medal: `<circle cx="12" cy="15" r="5.6" fill="currentColor" fill-opacity=".26"/><path d="M7.6 3.6h2.8l1.9 4-2.8 1.4-1.9-5.4ZM16.4 3.6h-2.8l-1.9 4 2.8 1.4 1.9-5.4Z" fill="currentColor" fill-opacity=".26"/><path d="M12 12.6l1 2 2.2.3-1.6 1.5.4 2.2-2-1.1-2 1.1.4-2.2-1.6-1.5 2.2-.3 1-2Z"/>`,
  Upload: `<path d="M7.4 18.6a4.2 4.2 0 0 1-.6-8.4A5.4 5.4 0 0 1 17 9.4a3.8 3.8 0 0 1 .4 7.6"/><path d="M12 20.6v-7.6"/><path d="M12 12.6 14.9 15.5H9.1Z" fill="currentColor" stroke="none"/>`,
  ChatDotRound: `<path d="M7.6 4.6h8.8a3 3 0 0 1 3 3v5.4a3 3 0 0 1-3 3h-5.5l-4.3 3.2c-.5.4-1.2 0-1.2-.6V7.6a3 3 0 0 1 3-3Z" fill="currentColor" fill-opacity=".26"/><circle cx="8.8" cy="10.3" r="1.05" fill="currentColor" stroke="none"/><circle cx="12" cy="10.3" r="1.05" fill="currentColor" stroke="none"/><circle cx="15.2" cy="10.3" r="1.05" fill="currentColor" stroke="none"/>`,
  Reading: `<path d="M12 6.8C10.1 5.2 7.7 4.8 5 5.2c-.5.1-.9.5-.9 1v10.5c0 .6.6 1.1 1.2 1 2.3-.3 4.6.1 6.3 1.4.2.2.6.2.8 0 1.7-1.3 4-1.7 6.3-1.4.6.1 1.2-.4 1.2-1V6.2c0-.5-.4-.9-.9-1-2.7-.4-5.1 0-7 1.6Z" fill="currentColor" fill-opacity=".26"/><path d="M12 7v13"/>`,
  Trophy: `<path d="M7 4.6h10v4.6a5 5 0 0 1-10 0V4.6Z" fill="currentColor" fill-opacity=".26"/><path d="M7 6.2H4.9a1.3 1.3 0 0 0-1.3 1.3c0 1.8 1.5 3.3 3.4 3.5M17 6.2h2.1a1.3 1.3 0 0 1 1.3 1.3c0 1.8-1.5 3.3-3.4 3.5"/><path d="M12 13.8v3"/><path d="M8.9 20.4h6.2l-.7-3.4H9.6l-.7 3.4Z" fill="currentColor" fill-opacity=".26"/>`,
  DocumentCopy: `<path d="M9 4.6h7.6a1.6 1.6 0 0 1 1.6 1.6v11.4"/><rect x="5.2" y="7.2" width="10.4" height="13.2" rx="1.8" fill="currentColor" fill-opacity=".26"/><path d="M8.2 11.6h4.4M8.2 15.2h4.4"/>`,
  User: `<circle cx="12" cy="8.4" r="3.7" fill="currentColor" fill-opacity=".26"/><path d="M4.9 20a7.1 7.1 0 0 1 14.2 0Z" fill="currentColor" fill-opacity=".26"/>`,
  Suitcase: `<rect x="3.6" y="7.6" width="16.8" height="12" rx="2.3" fill="currentColor" fill-opacity=".26"/><path d="M9.2 7.6V6.2a1.8 1.8 0 0 1 1.8-1.8h2a1.8 1.8 0 0 1 1.8 1.8v1.4"/><path d="M3.6 12.6h16.8"/>`,
  OfficeBuilding: `<rect x="5.2" y="3.6" width="13.6" height="16.8" rx="1.8" fill="currentColor" fill-opacity=".26"/><path d="M8.4 7.2h1.8M13.8 7.2h1.8M8.4 10.8h1.8M13.8 10.8h1.8M8.4 14.4h1.8M13.8 14.4h1.8"/><path d="M10.2 20.4v-3h3.6v3"/>`,
  CircleClose: `<circle cx="12" cy="12" r="8.4" fill="currentColor" fill-opacity=".26"/><path d="M9.4 9.4l5.2 5.2M14.6 9.4l-5.2 5.2"/>`,
  Key: `<circle cx="8.4" cy="8.4" r="4.2" fill="currentColor" fill-opacity=".26"/><path d="M11.4 11.4l8.1 8.1M16.5 16.5l1.8-1.8M18.3 18.3l1.5-1.5"/>`,
  Finished: `<rect x="5.2" y="4.6" width="13.6" height="15.8" rx="2.2" fill="currentColor" fill-opacity=".26"/><rect x="9.4" y="2.9" width="5.2" height="3.4" rx="1.2"/><path d="M9 13.2l2.1 2.1 4-4"/>`,
  Document: `<path d="M6.6 3.6h6.2L18.4 9.2V20a1.6 1.6 0 0 1-1.6 1.6H6.6A1.6 1.6 0 0 1 5 20V5.2a1.6 1.6 0 0 1 1.6-1.6Z" fill="currentColor" fill-opacity=".26"/><path d="M12.8 3.6v5.6h5.6"/><path d="M8.4 13h7.2M8.4 16.4h4.6"/>`,
  Collection: `<path d="M4.2 11.4 11.5 4.1a1.6 1.6 0 0 1 1.1-.5h5.9a1.6 1.6 0 0 1 1.6 1.6v5.9c0 .4-.2.8-.5 1.1l-7.3 7.3a1.6 1.6 0 0 1-2.2 0l-5.9-5.9a1.6 1.6 0 0 1 0-2.2Z" fill="currentColor" fill-opacity=".26"/><circle cx="15.8" cy="8.2" r="1.4"/>`,
  Warning: `<path d="M10.4 4.7 3 17.5A1.9 1.9 0 0 0 4.6 20.4h14.8a1.9 1.9 0 0 0 1.6-2.9L13.6 4.7a1.9 1.9 0 0 0-3.2 0Z" fill="currentColor" fill-opacity=".26"/><path d="M12 9.6v4"/><circle cx="12" cy="16.6" r=".95" fill="currentColor" stroke="none"/>`,
  Folder: `<path d="M4.2 6.8A1.9 1.9 0 0 1 6.1 4.9h3.4l1.8 2.3h6.6a1.9 1.9 0 0 1 1.9 1.9v7.9a1.9 1.9 0 0 1-1.9 1.9H6.1a1.9 1.9 0 0 1-1.9-1.9V6.8Z" fill="currentColor" fill-opacity=".26"/><path d="M4.2 9.8h15.6"/>`,
  DataLine: `<rect x="3.8" y="12.6" width="3.6" height="7.8" rx="1.1" fill="currentColor" fill-opacity=".26"/><rect x="10.2" y="7.2" width="3.6" height="13.2" rx="1.1" fill="currentColor" fill-opacity=".26"/><rect x="16.6" y="10.4" width="3.6" height="10" rx="1.1" fill="currentColor" fill-opacity=".26"/>`,
  Notebook: `<rect x="5.6" y="3.6" width="12.8" height="16.8" rx="2.1" fill="currentColor" fill-opacity=".26"/><path d="M9.4 3.6v16.8"/><path d="M12.4 8.4h3.4M12.4 12h3.4M12.4 15.6h2"/>`,
  Box: `<path d="M12 3.2 20.4 7.7v8.6L12 20.8l-8.4-4.5V7.7L12 3.2Z" fill="currentColor" fill-opacity=".26"/><path d="M3.6 7.7 12 12.2l8.4-4.5M12 12.2v8.6"/>`,
  Search: `<circle cx="10.8" cy="10.8" r="6.2" fill="currentColor" fill-opacity=".26"/><path d="M15.6 15.6 20.4 20.4" stroke-width="2"/>`,
  Cpu: `<rect x="6.6" y="6.6" width="10.8" height="10.8" rx="2.2" fill="currentColor" fill-opacity=".26"/><rect x="10.2" y="10.2" width="3.6" height="3.6" rx="1"/><path d="M9.6 3.6v3M14.4 3.6v3M9.6 17.4v3M14.4 17.4v3M3.6 9.6h3M3.6 14.4h3M17.4 9.6h3M17.4 14.4h3"/>`,
  Setting: `<path d="M19.5 13.6a7.7 7.7 0 0 0 0-3.2l1.9-1.5-2-3.4-2.2.9a7.7 7.7 0 0 0-2.7-1.6L14.2 2.5h-4l-.3 2.3a7.7 7.7 0 0 0-2.7 1.6l-2.2-.9-2 3.4 1.9 1.5a7.7 7.7 0 0 0 0 3.2l-1.9 1.5 2 3.4 2.2-.9a7.7 7.7 0 0 0 2.7 1.6l.3 2.3h4l.3-2.3a7.7 7.7 0 0 0 2.7-1.6l2.2.9 2-3.4-1.9-1.5Z" fill="currentColor" fill-opacity=".26"/><circle cx="12" cy="12" r="2.9"/>`,
  Tools: `<circle cx="7.4" cy="16.6" r="3.6" fill="currentColor" fill-opacity=".26"/><path d="M10.1 14 20.4 3.7"/><path d="M16.6 2.6 21 7l-2.2 2.2-4.4-4.4 2.2-2.2Z" fill="currentColor" fill-opacity=".26"/>`,
  Grid: `<rect x="3.6" y="3.6" width="6.8" height="6.8" rx="1.6" fill="currentColor" fill-opacity=".26"/><rect x="13.6" y="3.6" width="6.8" height="6.8" rx="1.6" fill="currentColor" fill-opacity=".26"/><rect x="3.6" y="13.6" width="6.8" height="6.8" rx="1.6" fill="currentColor" fill-opacity=".26"/><rect x="14.8" y="14.8" width="2.4" height="2.4" rx=".7" fill="currentColor" stroke="none"/><rect x="18" y="14.8" width="2.4" height="2.4" rx=".7" fill="currentColor" stroke="none"/><rect x="14.8" y="18" width="2.4" height="2.4" rx=".7" fill="currentColor" stroke="none"/><rect x="18" y="18" width="2.4" height="2.4" rx=".7" fill="currentColor" stroke="none"/>`,
  Brush: `<path d="M16.4 3.8 20.2 7.6 11.6 16.2 7.8 12.4 16.4 3.8Z" fill="currentColor" fill-opacity=".26"/><path d="M7.8 12.4 11.6 16.2l-1.3 1.3a3 3 0 0 1-4.2 0l-.4-.4a3 3 0 0 1 0-4.2l2.1-2.1Z" fill="currentColor" fill-opacity=".26"/><path d="M5.6 18.4c1.3 0 2.2.9 2.2 1.9 0 .9-1 1.5-2.4 1.5.4-.5.4-1.1 0-1.5-.5-.8-.5-1.9.2-1.9Z" fill="currentColor" stroke="none"/>`,
  UserFilled: `<circle cx="12" cy="8.3" r="3.9" fill="currentColor" fill-opacity=".26"/><path d="M4.9 20.2a7.1 7.1 0 0 1 14.2 0Z" fill="currentColor" fill-opacity=".26"/>`,
}

const FALLBACK = `<circle cx="12" cy="12" r="6.5" fill="currentColor" fill-opacity=".26"/><circle cx="12" cy="12" r="2" fill="currentColor" stroke="none"/>`

const inner = computed(() => ICONS[props.name] || FALLBACK)
</script>

<template>
  <svg class="mi" :width="size" :height="size" viewBox="0 0 24 24" fill="none"
       aria-hidden="true" focusable="false">
    <!-- 描边/圆角统一在这里给，形状里只写「哪里要填充」 -->
    <g v-html="inner" stroke="currentColor" stroke-width="1.9"
       stroke-linecap="round" stroke-linejoin="round" fill="none"></g>
  </svg>
</template>

<style scoped>
.mi {
  flex: none;
  display: inline-block;
  vertical-align: middle;
  /* 单一色源：换色 / 主题适配只改这一处；图标内部用 currentColor 继承 */
  color: var(--mi-color, #4f46e5);
}
html.dark .mi { color: var(--mi-color, #8b93f8); }
</style>

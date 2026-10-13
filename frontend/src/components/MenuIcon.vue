<script setup>
/**
 * 菜单彩色图标（拟物化风格）
 *
 * 设计目标：把侧边栏从「扁平单色线性图标」升级为「彩色拟物图标」——
 *   统一语言：24×24 网格、圆角造型、竖向渐变填充 + 1 处撞色点缀 + 轻投影（CSS）。
 * 用法：<MenuIcon name="House" />，name 沿用原 Element Plus 图标名作为 key，
 *       因此 App.vue 的菜单数据结构（icon 字段）无需改动。
 * 未收录的名字回退为一枚中性色圆点，保证不出现空白。
 */
import { computed } from 'vue'

const props = defineProps({
  name: { type: String, default: '' },
  size: { type: [Number, String], default: 20 },
})

// 渐变调色板（浅→深，形成拟物质感）。id 前缀 mi-，同名渐变在多实例间重复定义但内容一致，取首个即可。
const DEFS = `
  <linearGradient id="mi-blue" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#6FA8FF"/><stop offset="1" stop-color="#2B6CF6"/></linearGradient>
  <linearGradient id="mi-indigo" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#9AA8FF"/><stop offset="1" stop-color="#4F46E5"/></linearGradient>
  <linearGradient id="mi-violet" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#C4B5FD"/><stop offset="1" stop-color="#7C3AED"/></linearGradient>
  <linearGradient id="mi-emerald" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#5EEAD4"/><stop offset="1" stop-color="#059669"/></linearGradient>
  <linearGradient id="mi-amber" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FCD34D"/><stop offset="1" stop-color="#F59E0B"/></linearGradient>
  <linearGradient id="mi-orange" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FDBA74"/><stop offset="1" stop-color="#F97316"/></linearGradient>
  <linearGradient id="mi-rose" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FDA4AF"/><stop offset="1" stop-color="#E11D48"/></linearGradient>
  <linearGradient id="mi-cyan" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#67E8F9"/><stop offset="1" stop-color="#0891B2"/></linearGradient>
  <linearGradient id="mi-teal" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2DD4BF"/><stop offset="1" stop-color="#0D9488"/></linearGradient>
  <linearGradient id="mi-slate" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#A9B6C9"/><stop offset="1" stop-color="#5B6B84"/></linearGradient>
  <linearGradient id="mi-pink" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#F9A8D4"/><stop offset="1" stop-color="#DB2777"/></linearGradient>
`

const ICONS = {
  // 首页：屋顶 + 墙体 + 琥珀色门
  House: `<path d="M12 3.4 3.6 10.3a1 1 0 0 0 1.3 1.5l.6-.5v6.9c0 .8.7 1.5 1.5 1.5h9c.8 0 1.5-.7 1.5-1.5v-6.9l.6.5a1 1 0 0 0 1.3-1.5L12 3.4z" fill="url(#mi-blue)"/><path d="M10 14.4h4v5.3h-4z" fill="#FBBF24"/>`,
  // 写文章：铅笔
  EditPen: `<path d="M3.5 20.5l4.2-1.1L19.4 7.7a2 2 0 0 0 0-2.8l-1.3-1.3a2 2 0 0 0-2.8 0L3.6 15.3 2.5 19.5c-.1.5.4 1 1 .9z" fill="url(#mi-violet)"/><path d="M14.6 5.1l4.3 4.3" stroke="#fff" stroke-width="1.4" stroke-linecap="round" opacity=".55"/><path d="M3.5 20.5l1.1-4.2 3.1 3.1-4.2 1.1z" fill="#FBBF24"/>`,
  // 我的帖子：两页纸
  Files: `<rect x="8.4" y="2.8" width="11.4" height="14.6" rx="2.2" fill="#C7D7FF"/><rect x="4.2" y="6.2" width="11.4" height="15" rx="2.2" fill="url(#mi-blue)"/><path d="M7 10.6h6M7 13.6h6M7 16.6h3.4" stroke="#fff" stroke-width="1.4" stroke-linecap="round"/>`,
  // 回收站：垃圾桶
  Delete: `<path d="M5.2 7h13.6l-.9 12.1A2 2 0 0 1 15.9 21H8.1a2 2 0 0 1-2-1.9L5.2 7z" fill="url(#mi-rose)"/><path d="M9.2 3.8h5.6a1 1 0 0 1 1 1V6H8.2V4.8a1 1 0 0 1 1-1z" fill="#FDA4AF"/><path d="M3.8 6h16.4" stroke="#E11D48" stroke-width="1.6" stroke-linecap="round"/><path d="M10 10.6v6.8M14 10.6v6.8" stroke="#fff" stroke-width="1.4" stroke-linecap="round" opacity=".75"/>`,
  // 订阅：铃铛
  Notification: `<path d="M12 3a5.5 5.5 0 0 0-5.5 5.5c0 4-1.2 5.2-1.8 5.8-.3.3-.1.9.4.9h13.8c.5 0 .7-.6.4-.9-.6-.6-1.8-1.8-1.8-5.8A5.5 5.5 0 0 0 12 3z" fill="url(#mi-amber)"/><path d="M9.8 18.4a2.2 2.2 0 0 0 4.4 0h-4.4z" fill="#F59E0B"/>`,
  // 收藏：星
  Star: `<path d="M12 3.2l2.7 5.6 6.1.9-4.4 4.3 1 6.1-5.4-2.9-5.4 2.9 1-6.1L3.2 9.7l6.1-.9L12 3.2z" fill="url(#mi-amber)"/><path d="M12 6.4l1.5 3.2 3.4.5-2.5 2.4.6 3.4L12 14.3z" fill="#FDE68A" opacity=".85"/>`,
  // 历史：时钟
  Clock: `<circle cx="12" cy="12" r="8.6" fill="url(#mi-cyan)"/><circle cx="12" cy="12" r="6.3" fill="#fff" opacity=".94"/><path d="M12 7.6V12l3 1.9" stroke="#0891B2" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>`,
  // 勋章 / 排行
  Medal: `<path d="M6.6 3h3l2.4 5-3.1 1.5L6.6 3z" fill="#60A5FA"/><path d="M17.4 3h-3l-2.4 5 3.1 1.5L17.4 3z" fill="#F87171"/><circle cx="12" cy="15.2" r="6" fill="url(#mi-amber)"/><path d="M12 12.4l1 2.1 2.3.3-1.7 1.6.4 2.3-2-1.1-2 1.1.4-2.3-1.7-1.6 2.3-.3 1-2.1z" fill="#fff"/>`,
  // 我的文件：云上传
  Upload: `<path d="M7 18.4a4.2 4.2 0 0 1-.5-8.4A5.4 5.4 0 0 1 17 9.4a3.7 3.7 0 0 1 .5 7.4" fill="none" stroke="url(#mi-teal)" stroke-width="1.9" stroke-linecap="round"/><path d="M12 21V12.2M12 12.2l-3.1 3.1M12 12.2l3.1 3.1" stroke="#0D9488" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"/>`,
  // AI 问答：气泡
  ChatDotRound: `<path d="M7 4h10a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3h-6.2l-4.4 3.3c-.5.4-1.2 0-1.2-.6V7a3 3 0 0 1 3-3z" fill="url(#mi-blue)"/><circle cx="8.8" cy="10.2" r="1.25" fill="#fff"/><circle cx="12.4" cy="10.2" r="1.25" fill="#fff"/><circle cx="16" cy="10.2" r="1.25" fill="#fff"/>`,
  // 培训：翻开的书
  Reading: `<path d="M12 6.4C10 4.8 7.4 4.4 4.6 4.8c-.5.1-.9.5-.9 1v10.8c0 .6.6 1.1 1.2 1 2.4-.3 4.8.1 6.6 1.5.3.2.7.2 1 0 1.8-1.4 4.2-1.8 6.6-1.5.6.1 1.2-.4 1.2-1V5.8c0-.5-.4-.9-.9-1-2.8-.4-5.4 0-7.4 1.6z" fill="url(#mi-emerald)"/><path d="M12 6.6v13.2" stroke="#fff" stroke-width="1.3" opacity=".7"/>`,
  // 答题 / 题库：奖杯
  Trophy: `<path d="M6.6 4h10.8v4.6a5.4 5.4 0 0 1-10.8 0V4z" fill="url(#mi-amber)"/><path d="M6.6 5.6H4.3A1.3 1.3 0 0 0 3 6.9c0 2 1.6 3.5 3.5 3.7M17.4 5.6h2.3A1.3 1.3 0 0 1 21 6.9c0 2-1.6 3.5-3.5 3.7" fill="none" stroke="#FBBF24" stroke-width="1.5" stroke-linecap="round"/><path d="M12 13.6v3.2" stroke="#F59E0B" stroke-width="2.2" stroke-linecap="round"/><path d="M8.6 20.6h6.8l-.8-3.6H9.4l-.8 3.6z" fill="#F59E0B"/>`,
  // 共享文档：两页
  DocumentCopy: `<rect x="8.6" y="2.8" width="11.2" height="14.2" rx="2" fill="#C7D7FF"/><rect x="4.4" y="6.4" width="11.2" height="14.8" rx="2" fill="url(#mi-indigo)"/><path d="M7.4 11h5.2M7.4 14h5.2M7.4 17h3.2" stroke="#fff" stroke-width="1.3" stroke-linecap="round"/>`,
  // 用户
  User: `<circle cx="12" cy="8.2" r="3.9" fill="url(#mi-blue)"/><path d="M4.6 20c0-3.6 3.3-6.2 7.4-6.2s7.4 2.6 7.4 6.2c0 .6-.5 1-1 1H5.6c-.6 0-1-.4-1-1z" fill="url(#mi-blue)"/>`,
  // 角色：公文包
  Suitcase: `<rect x="3" y="7.4" width="18" height="12.2" rx="2.6" fill="url(#mi-orange)"/><path d="M9 7.4V6a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v1.4" fill="none" stroke="#F97316" stroke-width="1.7"/><rect x="3" y="11.4" width="18" height="1.7" fill="#FDBA74"/><rect x="10.4" y="11.2" width="3.2" height="2.4" rx=".7" fill="#fff"/>`,
  // 部门：楼宇
  OfficeBuilding: `<rect x="5" y="3.4" width="14" height="17.2" rx="1.9" fill="url(#mi-slate)"/><rect x="8" y="6.4" width="2.4" height="2.4" rx=".5" fill="#E8EEF6"/><rect x="13.6" y="6.4" width="2.4" height="2.4" rx=".5" fill="#E8EEF6"/><rect x="8" y="10.9" width="2.4" height="2.4" rx=".5" fill="#E8EEF6"/><rect x="13.6" y="10.9" width="2.4" height="2.4" rx=".5" fill="#E8EEF6"/><rect x="10" y="15.8" width="4" height="4.8" rx=".7" fill="#FBBF24"/>`,
  // 黑名单：禁止
  CircleClose: `<circle cx="12" cy="12" r="8.6" fill="url(#mi-rose)"/><path d="M9 9l6 6M15 9l-6 6" stroke="#fff" stroke-width="1.9" stroke-linecap="round"/>`,
  // 登录日志：钥匙
  Key: `<circle cx="8.2" cy="8.2" r="4.3" fill="url(#mi-amber)"/><circle cx="8.2" cy="8.2" r="1.7" fill="#fff"/><path d="M11 11 19.4 19.4M16.2 16.2l1.9-1.9M18.2 18.2l1.7-1.7" stroke="#F59E0B" stroke-width="2" stroke-linecap="round"/>`,
  // 审核：打勾的板
  Finished: `<rect x="4.6" y="4" width="14.8" height="17" rx="2.3" fill="url(#mi-emerald)"/><rect x="9" y="2.4" width="6" height="3.4" rx="1.2" fill="#A7F3D0"/><path d="M8.6 12.9l2.4 2.4 4.6-4.6" stroke="#fff" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"/>`,
  // 文档
  Document: `<path d="M6 3.4h7.6L19 9v11.6a1.6 1.6 0 0 1-1.6 1.6H6a1.6 1.6 0 0 1-1.6-1.6V5A1.6 1.6 0 0 1 6 3.4z" fill="url(#mi-blue)"/><path d="M13.6 3.4V9H19" fill="#C7D7FF"/><path d="M8 12.6h8M8 15.6h8M8 18.6h4.6" stroke="#fff" stroke-width="1.3" stroke-linecap="round"/>`,
  // 分类标签
  Collection: `<path d="M3.6 11.3 11 3.9a1.6 1.6 0 0 1 1.1-.4h6.3A1.6 1.6 0 0 1 20 5.1v6.3c0 .4-.2.8-.4 1.1l-7.4 7.4a1.6 1.6 0 0 1-2.2 0l-6.4-6.4a1.6 1.6 0 0 1 0-2.2z" fill="url(#mi-violet)"/><circle cx="15.9" cy="8.1" r="1.7" fill="#fff"/>`,
  // 违禁词：警示三角
  Warning: `<path d="M10.3 4.2 2.7 17.4a2 2 0 0 0 1.7 3h15.2a2 2 0 0 0 1.7-3L13.7 4.2a2 2 0 0 0-3.4 0z" fill="url(#mi-amber)"/><path d="M12 9v4.4" stroke="#fff" stroke-width="1.9" stroke-linecap="round"/><circle cx="12" cy="16.5" r="1.15" fill="#fff"/>`,
  // 附件：文件夹
  Folder: `<path d="M3.5 6.6A2 2 0 0 1 5.5 4.6h3.8l2 2.4h7.2a2 2 0 0 1 2 2v8.4a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2V6.6z" fill="url(#mi-amber)"/><path d="M3.5 9.6h17" stroke="#FDE68A" stroke-width="1.3" opacity=".85"/>`,
  // 数据看板：柱状图
  DataLine: `<rect x="3.4" y="12.4" width="3.7" height="8.2" rx="1.1" fill="#9DBBFF"/><rect x="9.2" y="7" width="3.7" height="13.6" rx="1.1" fill="url(#mi-blue)"/><rect x="15" y="10.2" width="3.7" height="10.4" rx="1.1" fill="#5B8DFF"/><circle cx="5.3" cy="9.4" r="1.7" fill="#FBBF24"/>`,
  // 培训管理：笔记本
  Notebook: `<rect x="5.4" y="3.4" width="13.2" height="17.2" rx="2.1" fill="url(#mi-orange)"/><rect x="5.4" y="3.4" width="3.6" height="17.2" rx="2.1" fill="#FDBA74"/><path d="M11 8h5.4M11 11.6h5.4M11 15.2h3.2" stroke="#fff" stroke-width="1.3" stroke-linecap="round"/><circle cx="7.2" cy="7" r=".95" fill="#fff"/><circle cx="7.2" cy="12" r=".95" fill="#fff"/><circle cx="7.2" cy="17" r=".95" fill="#fff"/>`,
  // 资产：立方体
  Box: `<path d="M12 2.6 20.6 7.3v9.4L12 21.4 3.4 16.7V7.3L12 2.6z" fill="url(#mi-orange)"/><path d="M12 2.6 20.6 7.3 12 12 3.4 7.3 12 2.6z" fill="#FDBA74"/><path d="M12 12v9.4L3.4 16.7V7.3L12 12z" fill="#000" opacity=".08"/>`,
  // 检索：放大镜
  Search: `<circle cx="10.6" cy="10.6" r="6.2" fill="none" stroke="url(#mi-blue)" stroke-width="2.3"/><path d="M15.5 15.5 20.6 20.6" stroke="#2B6CF6" stroke-width="2.7" stroke-linecap="round"/><path d="M7.7 8.6a3.7 3.7 0 0 1 3-2.1" stroke="#fff" stroke-width="1.5" stroke-linecap="round" opacity=".85"/>`,
  // AI 模型：芯片
  Cpu: `<rect x="6.4" y="6.4" width="11.2" height="11.2" rx="2.3" fill="url(#mi-violet)"/><rect x="9.5" y="9.5" width="5" height="5" rx="1" fill="#fff"/><path d="M9.6 3.4v3M14.4 3.4v3M9.6 17.6v3M14.4 17.6v3M3.4 9.6h3M3.4 14.4h3M17.6 9.6h3M17.6 14.4h3" stroke="#7C3AED" stroke-width="1.7" stroke-linecap="round"/>`,
  // 设置：齿轮
  Setting: `<g stroke="url(#mi-slate)" stroke-width="2.4" stroke-linecap="round"><path d="M12 2.7v2.3M12 19v2.3M2.7 12h2.3M19 12h2.3M5.4 5.4l1.7 1.7M16.9 16.9l1.7 1.7M18.6 5.4l-1.7 1.7M7.1 16.9l-1.7 1.7"/></g><circle cx="12" cy="12" r="5.7" fill="url(#mi-slate)"/><circle cx="12" cy="12" r="2.5" fill="#fff"/>`,
  // 水印：扳手
  Tools: `<path d="M20.7 5.3a5.2 5.2 0 0 1-6.9 6.9l-7.4 7.4a2.3 2.3 0 0 1-3.3-3.3l7.4-7.4A5.2 5.2 0 0 1 17.4 2l-2.8 2.8 2.6 2.6L20 4.6c.3.2.5.4.7.7z" fill="url(#mi-slate)"/>`,
  // 二维码
  Grid: `<g fill="#475569"><rect x="3.4" y="3.4" width="7" height="7" rx="1.7"/><rect x="13.6" y="3.4" width="7" height="7" rx="1.7"/><rect x="3.4" y="13.6" width="7" height="7" rx="1.7"/></g><g fill="#fff"><rect x="6" y="6" width="1.8" height="1.8" rx=".4"/><rect x="16.2" y="6" width="1.8" height="1.8" rx=".4"/><rect x="6" y="16.2" width="1.8" height="1.8" rx=".4"/></g><g fill="url(#mi-slate)"><rect x="13.6" y="13.6" width="3" height="3" rx=".9"/><rect x="17.6" y="13.6" width="3" height="3" rx=".9"/><rect x="13.6" y="17.6" width="3" height="3" rx=".9"/><rect x="17.6" y="17.6" width="3" height="3" rx=".9"/></g>`,
  // 外观：画笔
  Brush: `<path d="M16.2 3.4 20.6 7.8 11.4 17l-4.4-4.4L16.2 3.4z" fill="url(#mi-pink)"/><path d="M7 12.6 11.4 17l-1.6 1.6a3 3 0 0 1-4.2 0l-.4-.4a3 3 0 0 1 0-4.2L7 12.6z" fill="#F9A8D4"/><path d="M5.2 18.2c1.6 0 2.7 1 2.7 2.2 0 1.1-1.3 1.8-2.9 1.8.4-.6.4-1.2 0-1.8-.6-.9-.6-2.2.2-2.2z" fill="#DB2777"/>`,
  // 个人资料：实心人像
  UserFilled: `<circle cx="12" cy="8" r="4.1" fill="url(#mi-indigo)"/><path d="M4.3 20.2c0-3.7 3.5-6.4 7.7-6.4s7.7 2.7 7.7 6.4c0 .6-.5 1-1.1 1H5.4c-.6 0-1.1-.4-1.1-1z" fill="url(#mi-indigo)"/><circle cx="12" cy="7.6" r="1.6" fill="#fff" opacity=".45"/>`,
}

const FALLBACK = `<circle cx="12" cy="12" r="6" fill="url(#mi-slate)"/>`

const inner = computed(() => ICONS[props.name] || FALLBACK)
</script>

<template>
  <svg class="mi" :width="size" :height="size" viewBox="0 0 24 24" fill="none"
       aria-hidden="true" focusable="false">
    <defs v-html="DEFS"></defs>
    <g v-html="inner"></g>
  </svg>
</template>

<style scoped>
/* 轻投影营造「拟物」厚度；不影响布局（图标本身 flex:none） */
.mi {
  flex: none;
  display: inline-block;
  vertical-align: middle;
  filter: drop-shadow(0 1px 1px rgba(15, 23, 42, .22));
}
</style>

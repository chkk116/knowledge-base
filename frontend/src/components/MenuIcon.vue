<script setup>
/**
 * 菜单图标（双色几何风格）
 *
 * 设计语言：
 *   · 主形用「上亮 → 中本色 → 下暗」三段微渐变做出受光体积（gd-*）
 *   · 真实的**部件**用浅色档区分（如文档的背页、公文包的翻盖），形成层次
 *   · 统一琥珀点缀 #FBBF24 贯穿全部图标 → 家族识别度
 *   · 极轻投影（CSS）把图标从底色上「抬」起来
 *
 * ⚠️ 铁律：**绝不把一个完整形状对半切开**。
 *    光不会正好从中间劈开一个物体，对半切会看出「两块拼起来」的接缝，
 *    非常不自然（2026-10-13 用户指出）。层次只能来自「真实部件的叠放」。
 *
 * 用法：<MenuIcon name="House" />，name 优先沿用 Element Plus 图标名；本文件自绘的名字
 * （如 AiSpark）由 ICONS 兜底，菜单数据结构无需改动。
 * 未收录的名字回退为一枚中性圆点，保证不出现空白。
 */
import { computed } from 'vue'

const props = defineProps({
  name: { type: String, default: '' },
  size: { type: [Number, String], default: 20 },
  /** 图标风格：duo=双色几何（默认） / line=细线线框（偏好里可选） */
  variant: { type: String, default: 'duo' },
})

// 色板：D=深色主形（配渐变），L=浅色部件
const B_L = '#9DBBFF', B_XL = '#DBEAFE'
const I_L = '#A5B4FC'
const V_L = '#C4B5FD'
const A_L = '#FDE68A'
const R_L = '#FDA4AF'
const S_L = '#A9B6C9'
const O_L = '#FDBA74'
const B_D = '#2B6CF6', A_D = '#F59E0B', S_D = '#475569', T_D = '#0D9488', O_D = '#EA580C'
const X = '#FBBF24'      // 统一琥珀点缀
const X_DK = '#B45309'   // 琥珀系图标上的「深色点缀」（琥珀底上不能再用琥珀）

const DEFS = `
<linearGradient id="gd-B" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#4C86FA"/><stop offset=".55" stop-color="#2B6CF6"/><stop offset="1" stop-color="#1F5AD9"/></linearGradient>
<linearGradient id="gd-I" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#6E64EC"/><stop offset=".55" stop-color="#4F46E5"/><stop offset="1" stop-color="#3F36C9"/></linearGradient>
<linearGradient id="gd-V" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#9459F1"/><stop offset=".55" stop-color="#7C3AED"/><stop offset="1" stop-color="#6629D2"/></linearGradient>
<linearGradient id="gd-A" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#F9B22A"/><stop offset=".55" stop-color="#F59E0B"/><stop offset="1" stop-color="#DB8A05"/></linearGradient>
<linearGradient id="gd-E" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#16AC79"/><stop offset=".55" stop-color="#059669"/><stop offset="1" stop-color="#048058"/></linearGradient>
<linearGradient id="gd-C" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#12A4C6"/><stop offset=".55" stop-color="#0891B2"/><stop offset="1" stop-color="#077C99"/></linearGradient>
<linearGradient id="gd-R" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#E9436A"/><stop offset=".55" stop-color="#E11D48"/><stop offset="1" stop-color="#C4163C"/></linearGradient>
<linearGradient id="gd-S" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#5A687D"/><stop offset=".55" stop-color="#475569"/><stop offset="1" stop-color="#3A4657"/></linearGradient>
<linearGradient id="gd-O" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#F27426"/><stop offset=".55" stop-color="#EA580C"/><stop offset="1" stop-color="#CD4C08"/></linearGradient>
<linearGradient id="gd-P" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#E2438C"/><stop offset=".55" stop-color="#DB2777"/><stop offset="1" stop-color="#C01F66"/></linearGradient>
`

const ICONS = {
  // 首页：屋顶（独立部件）+ 墙体 + 门
  House: `<path d="M12 3.1 21.2 10.5H2.8L12 3.1Z" fill="url(#gd-B)"/><path d="M4.7 10.5h14.6v9.2a1.4 1.4 0 0 1-1.4 1.4H6.1a1.4 1.4 0 0 1-1.4-1.4v-9.2Z" fill="${B_L}"/><rect x="9.9" y="14.1" width="4.2" height="7" rx="1" fill="${X}"/>`,

  // 写文章：笔杆 + 削出的笔尖（真实部件）
  EditPen: `<path d="M4 20.5 5.5 15 16.3 4.2a1.8 1.8 0 0 1 2.6 0l1.1 1.1a1.8 1.8 0 0 1 0 2.6L9.2 18.8 4 20.5Z" fill="url(#gd-V)"/><path d="M4 20.5 5.5 15l3.7 3.7L4 20.5Z" fill="${V_L}"/><path d="M15.7 4.8 19.4 8.5" stroke="#fff" stroke-width="1.5" stroke-linecap="round" opacity=".6"/>`,

  // 我的帖子：背页（部件）+ 前页 + 页签
  Files: `<path d="M9.4 3.6h7a1.6 1.6 0 0 1 1.6 1.6v12.2H9.4V3.6Z" fill="${B_L}"/><path d="M5.6 6.6h9a1.6 1.6 0 0 1 1.6 1.6v11.2a1.6 1.6 0 0 1-1.6 1.6h-9a1.6 1.6 0 0 1-1.6-1.6V8.2a1.6 1.6 0 0 1 1.6-1.6Z" fill="url(#gd-B)"/><path d="M7.6 11.4h5.6M7.6 14.6h5.6" stroke="${B_XL}" stroke-width="1.5" stroke-linecap="round"/><rect x="12.6" y="3.6" width="3.2" height="3.2" rx="1" fill="${X}"/>`,

  // 回收站：桶身（整体）+ 桶盖（部件）+ 提手
  Delete: `<path d="M5.6 7.6h12.8l-.8 11.4a1.8 1.8 0 0 1-1.8 1.7H8.2a1.8 1.8 0 0 1-1.8-1.7L5.6 7.6Z" fill="url(#gd-R)"/><rect x="5.6" y="7.6" width="12.8" height="2.4" fill="${R_L}"/><path d="M9.6 4.6h4.8a1 1 0 0 1 1 1v2H8.6v-2a1 1 0 0 1 1-1Z" fill="${X}"/><path d="M10.2 12.8v5.4M13.8 12.8v5.4" stroke="#fff" stroke-width="1.4" stroke-linecap="round" opacity=".6"/>`,

  // 订阅：铃身（整体，靠渐变出体积）+ 铃锤
  Notification: `<path d="M12 3.6a5.3 5.3 0 0 0-5.3 5.3c0 3.7-1.1 4.8-1.7 5.4-.3.3-.1.9.4.9h13.2c.5 0 .7-.6.4-.9-.6-.6-1.7-1.7-1.7-5.4A5.3 5.3 0 0 0 12 3.6Z" fill="url(#gd-A)"/><path d="M9.9 18.7a2.1 2.1 0 0 0 4.2 0H9.9Z" fill="${X_DK}"/>`,

  // 收藏：整颗星（渐变出体积）+ 深琥珀星心
  Star: `<path d="M12 3.2 14.8 9l6.2 1-4.5 4.3 1.1 6.1L12 17.4 6.9 20.4l1.1-6.1L3.5 10l6.2-1L12 3.2Z" fill="url(#gd-A)"/><circle cx="12" cy="12.4" r="1.6" fill="${X_DK}"/>`,

  // 历史：整块表盘 + 指针 + 轴心
  Clock: `<circle cx="12" cy="12" r="8.6" fill="url(#gd-C)"/><path d="M12 7.2V12l3.2 1.9" stroke="#fff" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"/><circle cx="12" cy="12" r="1.3" fill="${X}"/>`,

  // 勋章：绶带（部件）+ 圆章 + 星
  Medal: `<path d="M7.2 3.4h3.2l1.8 4-3.2 1.5-1.8-5.5Z" fill="url(#gd-B)"/><path d="M16.8 3.4h-3.2l-1.8 4 3.2 1.5 1.8-5.5Z" fill="url(#gd-B)"/><circle cx="12" cy="15" r="5.6" fill="url(#gd-A)"/><path d="M12 12.4l1 2 2.2.3-1.6 1.5.4 2.2-2-1.1-2 1.1.4-2.2-1.6-1.5 2.2-.3 1-2Z" fill="#fff" opacity=".92"/>`,

  // 我的文件：云朵描边 + 箭头
  Upload: `<path d="M7.4 18.6a4.2 4.2 0 0 1-.6-8.4A5.4 5.4 0 0 1 17 9.4a3.8 3.8 0 0 1 .4 7.6" fill="none" stroke="${T_D}" stroke-width="1.9" stroke-linecap="round"/><path d="M12 20.6v-7.4" stroke="${T_D}" stroke-width="1.9" stroke-linecap="round"/><path d="M12 12.6 15 15.6H9l3-3Z" fill="${X}"/>`,
  Download: `<path d="M7.4 18.6a4.2 4.2 0 0 1-.6-8.4A5.4 5.4 0 0 1 17 9.4a3.8 3.8 0 0 1 .4 7.6" fill="none" stroke="${T_D}" stroke-width="1.9" stroke-linecap="round"/><path d="M12 11.6v7.4" stroke="${T_D}" stroke-width="1.9" stroke-linecap="round"/><path d="M12 19.6 9 16.6h6l-3 3Z" fill="${X}"/>`,

  // 消息中心：整个气泡 + 三点
  ChatDotRound: `<path d="M7.4 4.4h9.2a3 3 0 0 1 3 3v5.6a3 3 0 0 1-3 3H10l-4.2 3.1c-.5.4-1.2 0-1.2-.6V7.4a3 3 0 0 1 3-3Z" fill="url(#gd-B)"/><circle cx="9" cy="10.4" r="1.15" fill="#fff"/><circle cx="12.4" cy="10.4" r="1.15" fill="#fff"/><circle cx="15.8" cy="10.4" r="1.15" fill="${X}"/>`,

  // AI 问答：四角星芒（主形）+ 右上小星（琥珀点缀）
  AiSpark: `<path d="M10.2 4.4C11.2 9.6 13.9 12.3 19.2 13.4C13.9 14.5 11.2 17.2 10.2 22.4C9.2 17.2 6.5 14.5 1.2 13.4C6.5 12.3 9.2 9.6 10.2 4.4Z" fill="url(#gd-V)"/><path d="M18.8 1.8C19.3 4.4 20.2 5.3 22.8 5.8C20.2 6.3 19.3 7.2 18.8 9.8C18.3 7.2 17.4 6.3 14.8 5.8C17.4 5.3 18.3 4.4 18.8 1.8Z" fill="${X}"/>`,

  // 培训：整本书 + 中缝 + 书签
  Reading: `<path d="M12 6.8C10.1 5.2 7.7 4.8 5 5.2c-.5.1-.9.5-.9 1v10.5c0 .6.6 1.1 1.2 1 2.3-.3 4.6.1 6.3 1.4.2.2.6.2.8 0 1.7-1.3 4-1.7 6.3-1.4.6.1 1.2-.4 1.2-1V6.2c0-.5-.4-.9-.9-1-2.7-.4-5.1 0-7 1.6Z" fill="url(#gd-E)"/><path d="M12 7v13.2" stroke="#fff" stroke-width="1.4" opacity=".75"/><rect x="3.9" y="12.6" width="5.6" height="1.5" rx=".7" fill="${X}"/>`,

  // 答题：整只奖杯 + 双耳 + 底座
  Trophy: `<path d="M7 4.6h10v4.6a5 5 0 0 1-10 0V4.6Z" fill="url(#gd-A)"/><path d="M7 6.2H4.9a1.3 1.3 0 0 0-1.3 1.3c0 1.8 1.5 3.3 3.4 3.5M17 6.2h2.1a1.3 1.3 0 0 1 1.3 1.3c0 1.8-1.5 3.3-3.4 3.5" fill="none" stroke="${A_D}" stroke-width="1.6" stroke-linecap="round"/><path d="M12 13.8v3.2" stroke="${A_D}" stroke-width="2.2" stroke-linecap="round"/><path d="M8.9 20.5h6.2l-.7-3.5H9.6l-.7 3.5Z" fill="${X_DK}"/>`,

  // 共享文档：背页（部件）+ 前页 + 页签
  DocumentCopy: `<path d="M9.4 3.6h6.4a1.6 1.6 0 0 1 1.6 1.6v12.2H9.4V3.6Z" fill="${I_L}"/><path d="M5.6 6.6h8.6a1.6 1.6 0 0 1 1.6 1.6v11.2a1.6 1.6 0 0 1-1.6 1.6H5.6A1.6 1.6 0 0 1 4 19.4V8.2a1.6 1.6 0 0 1 1.6-1.6Z" fill="url(#gd-I)"/><path d="M7.6 11.4h5.2M7.6 14.6h5.2" stroke="#C7D2FE" stroke-width="1.5" stroke-linecap="round"/><rect x="12.4" y="3.6" width="3.2" height="3.2" rx="1" fill="${X}"/>`,

  // 用户：整头 + 整肩 + 胸章
  User: `<circle cx="12" cy="8.2" r="4" fill="url(#gd-B)"/><path d="M4.8 20.4a7.2 7.2 0 0 1 14.4 0H4.8Z" fill="url(#gd-B)"/><rect x="9.5" y="16.6" width="5" height="1.5" rx=".7" fill="${X}"/>`,

  // 角色：包身 + 翻盖（真实部件）+ 提手 + 锁扣
  Suitcase: `<path d="M3.6 9.2h16.8a2.3 2.3 0 0 1 2.3 2.3v5.9a2.3 2.3 0 0 1-2.3 2.3H3.6a2.3 2.3 0 0 1-2.3-2.3v-5.9a2.3 2.3 0 0 1 2.3-2.3Z" fill="url(#gd-O)"/><path d="M3.6 7.6h16.8a2.3 2.3 0 0 1 2.3 2.3v1.6H1.3V9.9a2.3 2.3 0 0 1 2.3-2.3Z" fill="${O_L}"/><path d="M9.2 7.6V6.2a1.8 1.8 0 0 1 1.8-1.8h2a1.8 1.8 0 0 1 1.8 1.8v1.4" fill="none" stroke="${O_D}" stroke-width="1.8" stroke-linecap="round"/><rect x="9.6" y="12.6" width="4.8" height="3.4" rx="1.2" fill="${X}"/>`,

  // 部门：整栋楼 + 窗 + 门
  OfficeBuilding: `<path d="M5.2 5.4A1.8 1.8 0 0 1 7 3.6h10a1.8 1.8 0 0 1 1.8 1.8v15H5.2v-15Z" fill="url(#gd-S)"/><g fill="${S_L}"><rect x="7.6" y="7" width="2" height="2" rx=".5"/><rect x="7.6" y="11" width="2" height="2" rx=".5"/><rect x="14.4" y="7" width="2" height="2" rx=".5"/><rect x="14.4" y="11" width="2" height="2" rx=".5"/></g><rect x="10.2" y="16" width="3.6" height="4.4" rx="1" fill="${X}"/>`,

  // 黑名单：整个圆环 + 斜杠
  CircleClose: `<circle cx="12" cy="12" r="8.6" fill="url(#gd-R)"/><path d="M9.2 9.2 14.8 14.8M14.8 9.2 9.2 14.8" stroke="#fff" stroke-width="2" stroke-linecap="round"/>`,

  // 登录日志：整把钥匙 + 匙柄孔
  Key: `<circle cx="8.4" cy="8.4" r="4.4" fill="url(#gd-A)"/><circle cx="8.4" cy="8.4" r="1.7" fill="#fff"/><path d="M11.6 11.6 19.6 19.6" stroke="${A_D}" stroke-width="2.2" stroke-linecap="round"/><path d="M16.6 16.6l1.8-1.8M18.4 18.4l1.5-1.5" stroke="${A_D}" stroke-width="2" stroke-linecap="round"/>`,

  // 审核：整块板 + 夹子 + 对勾
  Finished: `<rect x="5.2" y="4.6" width="13.6" height="15.8" rx="2.2" fill="url(#gd-E)"/><rect x="9.4" y="2.9" width="5.2" height="3.4" rx="1.2" fill="${X}"/><path d="M9 13.6l2.1 2.1 4-4" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>`,

  // 文档：整页 + 折角 + 文字线
  Document: `<path d="M6.6 3.6h6.2v5.6h5.6v10.8a1.6 1.6 0 0 1-1.6 1.6H6.6A1.6 1.6 0 0 1 5 20V5.2a1.6 1.6 0 0 1 1.6-1.6Z" fill="url(#gd-B)"/><path d="M12.8 3.6 18.4 9.2h-5.6V3.6Z" fill="${X}"/><path d="M8.4 13h7.2M8.4 16.4h4.6" stroke="${B_XL}" stroke-width="1.5" stroke-linecap="round"/>`,

  // 分类标签：整个标签 + 孔
  Collection: `<path d="M4.2 11.4 11.5 4.1a1.6 1.6 0 0 1 1.1-.5h5.9a1.6 1.6 0 0 1 1.6 1.6v5.9c0 .4-.2.8-.5 1.1l-7.3 7.3a1.6 1.6 0 0 1-2.2 0l-5.9-5.9a1.6 1.6 0 0 1 0-2.2Z" fill="url(#gd-V)"/><circle cx="15.8" cy="8.2" r="1.7" fill="${X}"/>`,

  // 违禁词：整个三角 + 感叹号
  Warning: `<path d="M10.4 4.7 3 17.5A1.9 1.9 0 0 0 4.6 20.4h14.8a1.9 1.9 0 0 0 1.6-2.9L13.6 4.7a1.9 1.9 0 0 0-3.2 0Z" fill="url(#gd-A)"/><path d="M12 9.6v4.2" stroke="#fff" stroke-width="2" stroke-linecap="round"/><circle cx="12" cy="16.6" r="1.05" fill="#fff"/>`,

  // 附件：文件夹（标签页 + 袋身，真实部件）
  Folder: `<path d="M4.2 6.8A1.9 1.9 0 0 1 6.1 4.9h3.4l1.8 2.3h6.6a1.9 1.9 0 0 1 1.9 1.9v1.7H4.2V6.8Z" fill="url(#gd-A)"/><path d="M4.2 10.8h15.6v6.2a1.9 1.9 0 0 1-1.9 1.9H6.1a1.9 1.9 0 0 1-1.9-1.9v-6.2Z" fill="${A_L}"/><rect x="9.6" y="12.6" width="4.8" height="1.8" rx=".9" fill="${X}"/>`,

  // 数据看板：三根柱（真实部件）+ 顶点
  DataLine: `<rect x="3.6" y="12.6" width="3.8" height="7.8" rx="1.2" fill="${B_L}"/><rect x="10.1" y="7.2" width="3.8" height="13.2" rx="1.2" fill="url(#gd-B)"/><rect x="16.6" y="10.4" width="3.8" height="10" rx="1.2" fill="${B_L}"/><circle cx="12" cy="4.6" r="1.9" fill="${X}"/>`,

  // 培训管理：整本本子 + 书脊 + 文字线
  Notebook: `<path d="M7.7 3.6h10.2a2.1 2.1 0 0 1 2.1 2.1v12.6a2.1 2.1 0 0 1-2.1 2.1H7.7A2.1 2.1 0 0 1 5.6 18.3V5.7a2.1 2.1 0 0 1 2.1-2.1Z" fill="url(#gd-O)"/><rect x="5.6" y="3.6" width="2.6" height="16.8" rx="1.3" fill="${X}"/><path d="M11.6 8.6h5.2M11.6 12.2h5.2M11.6 15.8h3.2" stroke="#fff" stroke-width="1.5" stroke-linecap="round" opacity=".85"/>`,

  // 资产：立方体三面（真实的立体部件）
  Box: `<path d="M12 3 20.6 7.8 12 12.6 3.4 7.8 12 3Z" fill="${O_L}"/><path d="M3.4 7.8 12 12.6v8.6L3.4 16.4V7.8Z" fill="url(#gd-O)"/><path d="M20.6 7.8 12 12.6v8.6l8.6-4.8V7.8Z" fill="#FED7AA"/><path d="M3.4 7.8 12 12.6l8.6-4.8" fill="none" stroke="#fff" stroke-width="1.1" opacity=".5"/>`,

  // 检索：整个镜片 + 镜心 + 手柄
  Search: `<circle cx="10.6" cy="10.6" r="6.4" fill="url(#gd-B)"/><circle cx="10.6" cy="10.6" r="3" fill="${X}"/><path d="M15.4 15.4 20.4 20.4" stroke="${B_D}" stroke-width="2.6" stroke-linecap="round"/>`,

  // AI 模型：整块芯片 + 内核 + 引脚
  Cpu: `<rect x="6.6" y="6.6" width="10.8" height="10.8" rx="2.2" fill="url(#gd-V)"/><rect x="10.1" y="10.1" width="3.8" height="3.8" rx="1.1" fill="${X}"/><g stroke="#7C3AED" stroke-width="1.8" stroke-linecap="round"><path d="M9.6 3.6v3M14.4 3.6v3M9.6 17.4v3M14.4 17.4v3M3.6 9.6h3M3.6 14.4h3M17.4 9.6h3M17.4 14.4h3"/></g>`,

  // 设置：整个齿轮 + 轴心
  Setting: `<g stroke="${S_D}" stroke-width="2.8" stroke-linecap="round"><path d="M12 2.8v2.4M12 18.8v2.4M2.8 12h2.4M18.8 12h2.4M5.5 5.5l1.7 1.7M16.8 16.8l1.7 1.7M18.5 5.5l-1.7 1.7M7.2 16.8l-1.7 1.7"/></g><circle cx="12" cy="12" r="6.6" fill="url(#gd-S)"/><circle cx="12" cy="12" r="2.9" fill="${X}"/>`,

  // 水印：整个印章 + 印面
  Tools: `<path d="M9.6 3.6h4.8a2 2 0 0 1 2 2v3.2l1.7 3.6H5.9l1.7-3.6V5.6a2 2 0 0 1 2-2Z" fill="url(#gd-S)"/><rect x="4" y="14.6" width="16" height="6" rx="1.6" fill="${S_L}"/><rect x="4" y="14.6" width="16" height="2.2" rx="1.1" fill="${X}"/>`,

  // 二维码：定位角 + 数据块
  Grid: `<rect x="3.6" y="3.6" width="6.8" height="6.8" rx="1.6" fill="url(#gd-S)"/><rect x="5.8" y="5.8" width="2.4" height="2.4" rx=".7" fill="#fff"/><rect x="13.6" y="3.6" width="6.8" height="6.8" rx="1.6" fill="${S_L}"/><rect x="15.8" y="5.8" width="2.4" height="2.4" rx=".7" fill="#fff"/><rect x="3.6" y="13.6" width="6.8" height="6.8" rx="1.6" fill="${S_L}"/><rect x="5.8" y="15.8" width="2.4" height="2.4" rx=".7" fill="#fff"/><rect x="13.6" y="13.6" width="3.2" height="3.2" rx="1" fill="${X}"/><rect x="17.2" y="13.6" width="3.2" height="3.2" rx="1" fill="url(#gd-S)"/><rect x="13.6" y="17.2" width="3.2" height="3.2" rx="1" fill="url(#gd-S)"/><rect x="17.2" y="17.2" width="3.2" height="3.2" rx="1" fill="${S_L}"/>`,

  // 外观：画笔（笔杆 + 金属箍，真实部件）
  Brush: `<path d="M16.6 3.6 20.4 7.4 12.2 15.6 8.4 11.8 16.6 3.6Z" fill="url(#gd-P)"/><path d="M8.4 11.8 12.2 15.6l-1.2 1.2a3 3 0 0 1-4.2 0l-.4-.4a3 3 0 0 1 0-4.2l2-2Z" fill="${S_L}"/><path d="M5.8 18.6c1.3 0 2.2.9 2.2 1.9 0 .9-1 1.5-2.4 1.5.4-.5.4-1.1 0-1.5-.5-.8-.5-1.9.2-1.9Z" fill="${X}"/>`,

  // ── 2026-10-26 新增：菜单去重后新用的名字，全部按同一套双色几何语言手绘 ──

  // 系统状态：显示器（屏幕 + 支架两个真实部件）+ 波形
  Monitor: `<rect x="2.8" y="4.2" width="18.4" height="12.2" rx="2" fill="url(#gd-B)"/><rect x="4.7" y="6.1" width="14.6" height="8.4" rx="1.2" fill="${B_XL}"/><path d="M7.4 12.2l2.5-2.9 2.1 2.1 3-3.5" fill="none" stroke="${B_D}" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/><path d="M9.2 16.4h5.6l1 3.4H8.2l1-3.4Z" fill="${B_L}"/>`,

  // 通知外发：喇叭（音箱 + 锥体 + 声波）
  Promotion: `<path d="M3.4 10h3l6.6-4.6v13.2L6.4 14h-3a1 1 0 0 1-1-1v-2a1 1 0 0 1 1-1Z" fill="url(#gd-O)"/><rect x="3.4" y="10" width="3" height="4" fill="${O_L}"/><path d="M15.8 9.2a4.6 4.6 0 0 1 0 5.6" fill="none" stroke="${O_D}" stroke-width="1.9" stroke-linecap="round"/><path d="M18.4 7a8 8 0 0 1 0 10" fill="none" stroke="${O_L}" stroke-width="1.9" stroke-linecap="round"/><circle cx="20.2" cy="5.2" r="1.5" fill="${X}"/>`,

  // 企业登录：盾牌（整体）+ 钥匙孔（点缀）
  Connection: `<path d="M12 2.9 19.7 5.7v5.7c0 4.5-3.2 8.3-7.7 9.7-4.5-1.4-7.7-5.2-7.7-9.7V5.7L12 2.9Z" fill="url(#gd-I)"/><circle cx="12" cy="10.4" r="2.1" fill="${X}"/><path d="M12 12.4v3.5" stroke="${X}" stroke-width="1.9" stroke-linecap="round"/>`,

  // 页脚设置：明信片（卡 + 邮票 + 地址线）
  Postcard: `<rect x="2.8" y="6" width="18.4" height="12" rx="1.9" fill="url(#gd-C)"/><rect x="15.2" y="7.9" width="3.9" height="3.9" rx=".9" fill="${X}"/><path d="M5.6 9.4h7.2M5.6 12.2h5M5.6 15h7.2" stroke="#CFFAFE" stroke-width="1.5" stroke-linecap="round"/>`,

  // 共享文档管理：打开的文件夹（后板 + 前板，真实部件）
  FolderOpened: `<path d="M3.4 8V6.4a1.9 1.9 0 0 1 1.9-1.9h3.3l1.8 2.3h6.3a1.9 1.9 0 0 1 1.9 1.9V8H3.4Z" fill="${A_L}"/><path d="M3.9 8h15.4l2 8.5a1.7 1.7 0 0 1-1.7 2.1H5.3a1.7 1.7 0 0 1-1.7-2.1L3.9 8Z" fill="url(#gd-A)"/><rect x="8.4" y="11.2" width="6.4" height="1.7" rx=".85" fill="${X}"/>`,

  // 全部文档：两张叠放的票券 + 撕线
  Tickets: `<rect x="4.2" y="3.8" width="12.6" height="9" rx="1.7" fill="${V_L}"/><rect x="6.4" y="8.4" width="13.4" height="11" rx="1.9" fill="url(#gd-V)"/><path d="M6.4 14h13.4" stroke="#fff" stroke-width="1.2" stroke-dasharray="1.7 1.7" opacity=".75"/><circle cx="17.2" cy="11.2" r="1.4" fill="${X}"/>`,

  // 操作审计：清单（两条勾 + 两条文字线）
  List: `<rect x="4.4" y="3.4" width="15.2" height="17.2" rx="2.3" fill="url(#gd-E)"/><path d="M7.6 8.2l1.4 1.4 2.3-2.3" fill="none" stroke="#fff" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/><path d="M13.4 8.6h3.2" stroke="#fff" stroke-width="1.5" stroke-linecap="round" opacity=".85"/><path d="M7.6 13.4l1.4 1.4 2.3-2.3" fill="none" stroke="#fff" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/><path d="M13.4 13.8h3.2" stroke="#fff" stroke-width="1.5" stroke-linecap="round" opacity=".85"/><circle cx="16.8" cy="17.4" r="1.4" fill="${X}"/>`,

  // 积分排行榜：上升折线 + 箭头（与「数据看板」的三根柱区分开）
  TrendCharts: `<path d="M3.6 18.4l4.8-5 3.1 3.1 6-7.1" fill="none" stroke="${B_D}" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/><path d="M13.2 9.4h4.6v4.6" fill="none" stroke="${B_D}" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/><circle cx="8.4" cy="13.4" r="1.7" fill="${X}"/><path d="M3.6 21h16.8" stroke="${B_L}" stroke-width="2" stroke-linecap="round"/>`,

  // 偏好设置：两条滑杆 + 两个滑块
  Operation: `<rect x="3.2" y="7.1" width="17.6" height="1.9" rx=".95" fill="${S_L}"/><rect x="3.2" y="15" width="17.6" height="1.9" rx=".95" fill="${S_L}"/><circle cx="9.2" cy="8.05" r="2.7" fill="url(#gd-S)"/><circle cx="15.4" cy="15.95" r="2.7" fill="${X}"/>`,

  // 帖子管理：便签（琥珀纸 + 折角 + 文字线），与蓝色「文档」区分
  Memo: `<path d="M5.2 4.2h11.2l3.4 3.4v12a1.7 1.7 0 0 1-1.7 1.7H5.2a1.7 1.7 0 0 1-1.7-1.7V5.9a1.7 1.7 0 0 1 1.7-1.7Z" fill="url(#gd-A)"/><path d="M16.4 4.2v3.4h3.4" fill="#FEF3C7"/><path d="M7.2 10.6h8.6M7.2 13.6h8.6M7.2 16.6h5" stroke="#fff" stroke-width="1.5" stroke-linecap="round" opacity=".9"/>`,

  // 分类标签：价签（整块 + 圆孔 + 文字线），与紫色「标签」区分
  PriceTag: `<rect x="4.2" y="6.2" width="15.6" height="12.6" rx="2.4" fill="url(#gd-R)"/><circle cx="8.2" cy="9.9" r="1.6" fill="#fff"/><path d="M11.4 10h5.4M11.4 13.2h5.4M11.4 16.4h3.2" stroke="${R_L}" stroke-width="1.5" stroke-linecap="round"/><circle cx="17.4" cy="16.2" r="1.4" fill="${X}"/>`,

  // 水印设置：印章落纸（手柄 + 柱 + 印面 + 纸上的琥珀印痕），与「工具」区分
  Stamp: `<rect x="8.4" y="2.6" width="7.2" height="2.3" rx="1.15" fill="${S_L}"/><rect x="10.4" y="4.9" width="3.2" height="5.6" fill="url(#gd-S)"/><rect x="6.2" y="10.5" width="11.6" height="3.3" rx="1.3" fill="url(#gd-S)"/><rect x="4.2" y="16.4" width="15.6" height="3.6" rx="1.5" fill="${X}"/>`,

  // 我的订阅：整只铃 + 铃舌
  Bell: `<path d="M12 3.4a6.1 6.1 0 0 1 6.1 6.1v3.9l1.7 2.7H4.2l1.7-2.7V9.5A6.1 6.1 0 0 1 12 3.4Z" fill="url(#gd-A)"/><path d="M9.7 18.2a2.3 2.3 0 0 0 4.6 0h-4.6Z" fill="${A_L}"/><circle cx="12" cy="2.9" r="1.25" fill="${X}"/>`,

  // 答题：试卷 + 笔尖（真实部件）
  Edit: `<rect x="3.8" y="3.4" width="14" height="17.2" rx="2.2" fill="url(#gd-V)"/><path d="M7 8.4h6.6M7 12h5" stroke="#EDE9FE" stroke-width="1.6" stroke-linecap="round"/><path d="M13.6 16.9l4.2-4.2 1.7 1.7-4.2 4.2-2.2.5.5-2.2Z" fill="${X}"/>`,

  // 用户与权限：两个真实的人（前 + 后）+ 点缀
  Management: `<circle cx="9" cy="8.6" r="3.3" fill="url(#gd-I)"/><path d="M3.4 19.8a5.6 5.6 0 0 1 11.2 0H3.4Z" fill="url(#gd-I)"/><circle cx="16.8" cy="9.8" r="2.5" fill="${I_L}"/><path d="M13.6 19.8a4.4 4.4 0 0 1 8.8 0h-8.8Z" fill="${I_L}"/><circle cx="20.2" cy="4.6" r="1.35" fill="${X}"/>`,

  // 个人资料：整头 + 整肩 + 肩章
  UserFilled: `<circle cx="12" cy="8.2" r="4.1" fill="url(#gd-I)"/><path d="M4.6 20.4a7.4 7.4 0 0 1 14.8 0H4.6Z" fill="url(#gd-I)"/><rect x="9.3" y="16.8" width="5.4" height="1.5" rx=".7" fill="${X}"/>`,
}

// 每实例唯一渐变 id 前缀（第四轮审查）：所有实例渲染同一份 <defs> 会产出重复的
// dom id，url(#gd-*) 永远解析到文档里第一处——首个实例卸载/重排后其余图标渐变全丢。
// 前缀化后每个实例的引用与自己的 defs 严格配对。
let miSeq = 0
const uid = 'mi' + (++miSeq)
const defs = DEFS.replaceAll('gd-', `gd-${uid}-`)
const FALLBACK = `<circle cx="12" cy="12" r="7" fill="url(#gd-S)"/><circle cx="12" cy="12" r="2.4" fill="${X}"/>`

/**
 * 细线线框风格（2026-11-10，偏好里可选）：1.5px 线 + 圆角端点，
 * 每枚只在恰好一处用主色 #1677FF，其余深灰 —— 覆盖高频菜单项，
 * 未覆盖的名字回退到双色 ICONS（线框/双色混排时以双色兜底，避免空白）。
 */
const ICONS_LINE = {
  House: `<path d="M4 11 12 4l8 7" fill="none" stroke="#1D2129" stroke-width="1.5" stroke-linejoin="round"/><path d="M6 10.5V19a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1v-8.5" fill="none" stroke="#1D2129" stroke-width="1.5"/><path d="M10.5 20v-5h3v5" fill="none" stroke="#1677FF" stroke-width="1.5"/>`,
  Files: `<path d="M8 3.5h7l4.5 4.5V19a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 19V5A1.5 1.5 0 0 1 8 3.5Z" fill="none" stroke="#1D2129" stroke-width="1.5" stroke-linejoin="round"/><path d="M15 3.5V8h4.5" fill="none" stroke="#1D2129" stroke-width="1.5"/><path d="M9.5 11.5h5M9.5 15h3.5" stroke="#1677FF" stroke-width="1.5" stroke-linecap="round" fill="none"/>`,
  EditPen: `<path d="M4.5 19.5 6 14.8 16.2 4.6a1.6 1.6 0 0 1 2.3 0l1 1a1.6 1.6 0 0 1 0 2.3L9.3 18.2 4.5 19.5Z" fill="none" stroke="#1D2129" stroke-width="1.5" stroke-linejoin="round"/><path d="M14.8 6 18 9.2" stroke="#1677FF" stroke-width="1.5" stroke-linecap="round" fill="none"/>`,
  AiSpark: `<path d="M12 4a8 8 0 0 1 8 8 8 8 0 0 1-8 8H5.6l1.6-2.5A8 8 0 0 1 12 4Z" fill="none" stroke="#1D2129" stroke-width="1.5" stroke-linejoin="round"/><path d="M12 8.6c.4 2.1 1.3 3 3.4 3.4-2.1.4-3 1.3-3.4 3.4-.4-2.1-1.3-3-3.4-3.4 2.1-.4 3-1.3 3.4-3.4Z" fill="#1677FF"/>`,
  Search: `<circle cx="11" cy="10.5" r="5.5" fill="none" stroke="#1D2129" stroke-width="1.5"/><path d="M15.2 14.7 19.5 19" stroke="#1677FF" stroke-width="1.8" stroke-linecap="round" fill="none"/>`,
  Setting: `<path d="M4 8.5h16M4 15.5h16" stroke="#1D2129" stroke-width="1.5" stroke-linecap="round" fill="none"/><circle cx="9.5" cy="8.5" r="2.6" fill="#1677FF"/><circle cx="14.5" cy="15.5" r="2.6" fill="none" stroke="#1D2129" stroke-width="1.5"/>`,
  ChatDotRound: `<rect x="3.5" y="5" width="17" height="14" rx="2.5" fill="none" stroke="#1D2129" stroke-width="1.5" stroke-linejoin="round"/><path d="M3.5 7.5 12 13l8.5-5.5" stroke="#1D2129" stroke-width="1.5" stroke-linejoin="round" fill="none"/><circle cx="12" cy="16.4" r="1.1" fill="#1677FF"/>`,
  Star: `<path d="M12 3.8l2.4 5 5.4.8-3.9 3.8.9 5.4-4.8-2.6-4.8 2.6.9-5.4L4.2 9.6l5.4-.8L12 3.8Z" fill="none" stroke="#1677FF" stroke-width="1.5" stroke-linejoin="round"/>`,
  DocumentCopy: `<path d="M8 3.5h7l4.5 4.5V19a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 19V5A1.5 1.5 0 0 1 8 3.5Z" fill="none" stroke="#1D2129" stroke-width="1.5" stroke-linejoin="round"/><path d="M15 3.5V8h4.5" fill="none" stroke="#1D2129" stroke-width="1.5"/><path d="M9.5 14h5" stroke="#1677FF" stroke-width="1.5" stroke-linecap="round" fill="none"/>`,
}

// hasOwnProperty 守卫（第四轮审查）：原型链上的 constructor/toString 等名字不该被当成图标
const inner = computed(() => {
  let raw
  if (props.variant === 'line' && Object.prototype.hasOwnProperty.call(ICONS_LINE, props.name)) {
    raw = ICONS_LINE[props.name]
  } else if (Object.prototype.hasOwnProperty.call(ICONS, props.name)) {
    raw = ICONS[props.name]
  } else {
    raw = FALLBACK
  }
  return raw.replaceAll('gd-', `gd-${uid}-`)
})
</script>

<template>
  <svg class="mi" :width="size" :height="size" viewBox="0 0 24 24" fill="none"
       aria-hidden="true" focusable="false">
    <defs v-html="defs"></defs>
    <g v-html="inner"></g>
  </svg>
</template>

<style scoped>
.mi {
  flex: none;
  display: inline-block;
  vertical-align: middle;
  /* 极轻投影：把图标从底色上「抬」起来（比内阴影更干净，20px 下不糊） */
  filter: drop-shadow(0 1px 1px rgba(15, 23, 42, .20));
}
</style>

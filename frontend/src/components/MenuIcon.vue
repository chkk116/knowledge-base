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
 * 用法：<MenuIcon name="House" />，name 沿用 Element Plus 图标名，菜单数据结构无需改动。
 * 未收录的名字回退为一枚中性圆点，保证不出现空白。
 */
import { computed } from 'vue'

const props = defineProps({
  name: { type: String, default: '' },
  size: { type: [Number, String], default: 20 },
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

  // AI 问答：整个气泡 + 三点
  ChatDotRound: `<path d="M7.4 4.4h9.2a3 3 0 0 1 3 3v5.6a3 3 0 0 1-3 3H10l-4.2 3.1c-.5.4-1.2 0-1.2-.6V7.4a3 3 0 0 1 3-3Z" fill="url(#gd-B)"/><circle cx="9" cy="10.4" r="1.15" fill="#fff"/><circle cx="12.4" cy="10.4" r="1.15" fill="#fff"/><circle cx="15.8" cy="10.4" r="1.15" fill="${X}"/>`,

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

// hasOwnProperty 守卫（第四轮审查）：原型链上的 constructor/toString 等名字不该被当成图标
const inner = computed(() => {
  const raw = Object.prototype.hasOwnProperty.call(ICONS, props.name) ? ICONS[props.name] : FALLBACK
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

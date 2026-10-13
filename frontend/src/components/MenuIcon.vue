<script setup>
/**
 * 菜单图标（双色几何风格）
 *
 * 设计语言（保证 33 个是「一套」而不是拼凑）：
 *   · 深色主形 + 同色系浅色切面 → 用块面分色做出几何层次，而非描边
 *   · 统一琥珀点缀 #FBBF24 贯穿全部图标 → 形成家族识别度
 *   · 直边 + 圆角的块面造型，24×24 网格，无渐变、无投影（20px 下更清晰）
 * 用法：<MenuIcon name="House" />，name 沿用 Element Plus 图标名，菜单数据结构无需改动。
 * 未收录的名字回退为一枚中性圆点，保证不出现空白。
 */
import { computed } from 'vue'

const props = defineProps({
  name: { type: String, default: '' },
  size: { type: [Number, String], default: 20 },
})

// 色板：每套 = 深色主形 / 浅色切面（同色系）；X = 全局统一琥珀点缀
const B_D = '#2B6CF6', B_L = '#9DBBFF', B_XL = '#DBEAFE'
const I_D = '#4F46E5', I_L = '#A5B4FC'
const V_D = '#7C3AED', V_L = '#C4B5FD'
const A_D = '#F59E0B', A_L = '#FDE68A'   // 琥珀的浅色档要比别的更亮，否则左右切面看不出对比
const X_DK = '#B45309'                   // 琥珀系图标的「深色点缀」（琥珀底上不能再用琥珀点缀）
const E_D = '#059669', E_L = '#6EE7B7'
const C_D = '#0891B2', C_L = '#67E8F9'
const R_D = '#E11D48', R_L = '#FDA4AF'
const S_D = '#475569', S_L = '#A9B6C9'
const O_D = '#EA580C', O_L = '#FDBA74'
const T_D = '#0D9488'
const P_D = '#DB2777', P_L = '#F9A8D4'
const X = '#FBBF24'

/**
 * 立体感来源（克制版，避免回到厚重拟物）：
 *   深色主形用「上亮 → 中本色 → 下暗」三段微渐变，模拟受光体积；
 *   浅色切面保持平涂做对比；再加一层极轻投影（CSS）把它从底色上「抬」起来。
 */
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
  // 首页：三角屋顶 + 浅色墙体 + 琥珀门
  House: `<path d="M12 3.1 21.2 10.5H2.8L12 3.1Z" fill="url(#gd-B)"/><path d="M4.7 10.5h14.6v9.2a1.4 1.4 0 0 1-1.4 1.4H6.1a1.4 1.4 0 0 1-1.4-1.4v-9.2Z" fill="${B_L}"/><rect x="9.9" y="14.1" width="4.2" height="7" rx="1" fill="${X}"/>`,

  // 写文章：铅笔 + 浅色笔尖切面
  EditPen: `<path d="M4 20.5 5.5 15 16.3 4.2a1.8 1.8 0 0 1 2.6 0l1.1 1.1a1.8 1.8 0 0 1 0 2.6L9.2 18.8 4 20.5Z" fill="url(#gd-V)"/><path d="M4 20.5 5.5 15l3.7 3.7L4 20.5Z" fill="${V_L}"/><path d="M15.7 4.8 19.4 8.5" stroke="#fff" stroke-width="1.5" stroke-linecap="round" opacity=".6"/>`,

  // 我的帖子：浅色背页 + 深色前页 + 琥珀页签
  Files: `<path d="M9.4 3.6h7a1.6 1.6 0 0 1 1.6 1.6v12.2H9.4V3.6Z" fill="${B_L}"/><path d="M5.6 6.6h9a1.6 1.6 0 0 1 1.6 1.6v11.2a1.6 1.6 0 0 1-1.6 1.6h-9a1.6 1.6 0 0 1-1.6-1.6V8.2a1.6 1.6 0 0 1 1.6-1.6Z" fill="url(#gd-B)"/><path d="M7.6 11.4h5.6M7.6 14.6h5.6" stroke="${B_XL}" stroke-width="1.5" stroke-linecap="round"/><rect x="12.6" y="3.6" width="3.2" height="3.2" rx="1" fill="${X}"/>`,

  // 回收站：桶身分色 + 琥珀提手
  Delete: `<path d="M5.6 7.6h12.8l-.8 11.4a1.8 1.8 0 0 1-1.8 1.7H8.2a1.8 1.8 0 0 1-1.8-1.7L5.6 7.6Z" fill="url(#gd-R)"/><path d="M5.6 7.6h12.8l-.3 4.4H5.9L5.6 7.6Z" fill="${R_L}"/><path d="M9.6 4.6h4.8a1 1 0 0 1 1 1v2H8.6v-2a1 1 0 0 1 1-1Z" fill="${X}"/><path d="M10.2 12.6v5.6M13.8 12.6v5.6" stroke="#fff" stroke-width="1.4" stroke-linecap="round" opacity=".65"/>`,

  // 订阅：铃身左右分色 + 琥珀铃锤
  Notification: `<path d="M12 3.6a5.3 5.3 0 0 0-5.3 5.3c0 3.7-1.1 4.8-1.7 5.4-.3.3-.1.9.4.9h6.6V3.6Z" fill="url(#gd-A)"/><path d="M12 3.6a5.3 5.3 0 0 1 5.3 5.3c0 3.7 1.1 4.8 1.7 5.4.3.3.1.9-.4.9H12V3.6Z" fill="${A_L}"/><path d="M9.9 18.7a2.1 2.1 0 0 0 4.2 0H9.9Z" fill="${X_DK}"/>`,

  // 收藏：五角星左右分色（琥珀底 → 点缀改用深琥珀，否则看不见）
  Star: `<path d="M12 3.2 9.2 9 3 10l4.5 4.3-1.1 6.1L12 17.4V3.2Z" fill="url(#gd-A)"/><path d="M12 3.2 14.8 9l6.2 1-4.5 4.3 1.1 6.1L12 17.4V3.2Z" fill="${A_L}"/><circle cx="12" cy="12.2" r="1.5" fill="${X_DK}"/>`,

  // 历史：表盘左右分色 + 指针
  Clock: `<circle cx="12" cy="12" r="8.6" fill="url(#gd-C)"/><path d="M12 3.4a8.6 8.6 0 0 1 0 17.2V3.4Z" fill="${C_L}"/><path d="M12 7.2V12l3.2 1.9" stroke="#fff" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"/><circle cx="12" cy="12" r="1.3" fill="${X}"/>`,

  // 勋章：双色绶带 + 琥珀圆章
  Medal: `<path d="M7.2 3.4h3.2l1.8 4-3.2 1.5-1.8-5.5Z" fill="url(#gd-B)"/><path d="M16.8 3.4h-3.2l-1.8 4 3.2 1.5 1.8-5.5Z" fill="${B_L}"/><circle cx="12" cy="15" r="5.6" fill="url(#gd-A)"/><path d="M12 9.4a5.6 5.6 0 0 1 0 11.2V9.4Z" fill="${A_L}"/><path d="M12 12.4l1 2 2.2.3-1.6 1.5.4 2.2-2-1.1-2 1.1.4-2.2-1.6-1.5 2.2-.3 1-2Z" fill="#fff" opacity=".92"/>`,

  // 我的文件：云朵 + 琥珀箭头
  Upload: `<path d="M7.4 18.6a4.2 4.2 0 0 1-.6-8.4A5.4 5.4 0 0 1 17 9.4a3.8 3.8 0 0 1 .4 7.6" fill="none" stroke="${T_D}" stroke-width="1.9" stroke-linecap="round"/><path d="M12 20.6v-7.4" stroke="${T_D}" stroke-width="1.9" stroke-linecap="round"/><path d="M12 12.6 15 15.6H9l3-3Z" fill="${X}"/>`,

  // AI 问答：气泡上下分色 + 三点
  ChatDotRound: `<path d="M7.4 4.4h9.2a3 3 0 0 1 3 3v5.6a3 3 0 0 1-3 3H10l-4.2 3.1c-.5.4-1.2 0-1.2-.6V7.4a3 3 0 0 1 3-3Z" fill="url(#gd-B)"/><path d="M7.4 4.4h9.2a3 3 0 0 1 3 3v1.8H4.6V7.4a3 3 0 0 1 3-3Z" fill="${B_L}"/><circle cx="9" cy="11.6" r="1.15" fill="#fff"/><circle cx="12.4" cy="11.6" r="1.15" fill="#fff"/><circle cx="15.8" cy="11.6" r="1.15" fill="${X}"/>`,

  // 培训：摊开的书左右分色 + 琥珀书签
  Reading: `<path d="M12 6.8C10.1 5.2 7.7 4.8 5 5.2c-.5.1-.9.5-.9 1v10.5c0 .6.6 1.1 1.2 1 2.3-.3 4.6.1 6.3 1.4h.4V6.8Z" fill="${E_L}"/><path d="M12 6.8c1.9-1.6 4.3-2 7-1.6.5.1.9.5.9 1v10.5c0 .6-.6 1.1-1.2 1-2.3-.3-4.6.1-6.3 1.4H12V6.8Z" fill="url(#gd-E)"/><path d="M12 6.8v13.3" stroke="#fff" stroke-width="1.3" opacity=".7"/><rect x="3.9" y="12.6" width="5.6" height="1.5" rx=".7" fill="${X}"/>`,

  // 答题：奖杯左右分色 + 琥珀底座
  Trophy: `<path d="M7 4.6h5v9.2H7V4.6Z" fill="url(#gd-A)"/><path d="M12 4.6h5v4.6a5 5 0 0 1-5 4.6V4.6Z" fill="${A_L}"/><path d="M7 6.2H4.9a1.3 1.3 0 0 0-1.3 1.3c0 1.8 1.5 3.3 3.4 3.5M17 6.2h2.1a1.3 1.3 0 0 1 1.3 1.3c0 1.8-1.5 3.3-3.4 3.5" fill="none" stroke="${A_D}" stroke-width="1.6" stroke-linecap="round"/><path d="M12 13.8v3.2" stroke="${A_D}" stroke-width="2.2" stroke-linecap="round"/><path d="M8.9 20.5h6.2l-.7-3.5H9.6l-.7 3.5Z" fill="${X_DK}"/>`,

  // 共享文档：浅色背页 + 深色前页 + 琥珀页签
  DocumentCopy: `<path d="M9.4 3.6h6.4a1.6 1.6 0 0 1 1.6 1.6v12.2H9.4V3.6Z" fill="${I_L}"/><path d="M5.6 6.6h8.6a1.6 1.6 0 0 1 1.6 1.6v11.2a1.6 1.6 0 0 1-1.6 1.6H5.6A1.6 1.6 0 0 1 4 19.4V8.2a1.6 1.6 0 0 1 1.6-1.6Z" fill="url(#gd-I)"/><path d="M7.6 11.4h5.2M7.6 14.6h5.2" stroke="#C7D2FE" stroke-width="1.5" stroke-linecap="round"/><rect x="12.4" y="3.6" width="3.2" height="3.2" rx="1" fill="${X}"/>`,

  // 用户：左右分色的半身像 + 琥珀胸章
  User: `<circle cx="12" cy="8.2" r="4" fill="url(#gd-B)"/><path d="M12 4.2a4 4 0 0 1 0 8V4.2Z" fill="${B_L}"/><path d="M4.8 20.4a7.2 7.2 0 0 1 14.4 0H4.8Z" fill="${B_L}"/><path d="M12 13.2a7.2 7.2 0 0 1 7.2 7.2H12v-7.2Z" fill="url(#gd-B)"/><rect x="9.5" y="16.6" width="5" height="1.5" rx=".7" fill="${X}"/>`,

  // 角色：公文包上下分色 + 琥珀锁扣
  Suitcase: `<path d="M3.6 7.6h16.8a2.3 2.3 0 0 1 2.3 2.3v1.6H1.3V9.9a2.3 2.3 0 0 1 2.3-2.3Z" fill="${O_L}"/><path d="M1.3 11.5h21.4v5.9a2.3 2.3 0 0 1-2.3 2.3H3.6a2.3 2.3 0 0 1-2.3-2.3v-5.9Z" fill="url(#gd-O)"/><path d="M9.2 7.6V6.2a1.8 1.8 0 0 1 1.8-1.8h2a1.8 1.8 0 0 1 1.8 1.8v1.4" fill="none" stroke="${O_D}" stroke-width="1.8" stroke-linecap="round"/><rect x="9.6" y="12.6" width="4.8" height="3.4" rx="1.2" fill="${X}"/>`,

  // 部门：楼宇左右分色 + 琥珀门
  OfficeBuilding: `<path d="M5.2 5.4A1.8 1.8 0 0 1 7 3.6h5v16.8H5.2V5.4Z" fill="url(#gd-S)"/><path d="M12 3.6h5a1.8 1.8 0 0 1 1.8 1.8v15H12V3.6Z" fill="${S_L}"/><g fill="url(#gd-S)"><rect x="7.6" y="7" width="2" height="2" rx=".5"/><rect x="7.6" y="11" width="2" height="2" rx=".5"/><rect x="14.4" y="7" width="2" height="2" rx=".5"/><rect x="14.4" y="11" width="2" height="2" rx=".5"/></g><rect x="10.2" y="16" width="3.6" height="4.4" rx="1" fill="${X}"/>`,

  // 黑名单：圆环左右分色 + 白色斜杠
  CircleClose: `<circle cx="12" cy="12" r="8.6" fill="${R_L}"/><path d="M12 3.4a8.6 8.6 0 0 1 0 17.2V3.4Z" fill="url(#gd-R)"/><path d="M9.2 9.2 14.8 14.8M14.8 9.2 9.2 14.8" stroke="#fff" stroke-width="2" stroke-linecap="round"/>`,

  // 登录日志：钥匙 + 琥珀匙柄
  Key: `<circle cx="8.4" cy="8.4" r="4.4" fill="url(#gd-A)"/><path d="M8.4 4a4.4 4.4 0 0 1 0 8.8V4Z" fill="${A_L}"/><circle cx="8.4" cy="8.4" r="1.7" fill="#fff"/><path d="M11.6 11.6 19.6 19.6" stroke="${A_D}" stroke-width="2.2" stroke-linecap="round"/><path d="M16.6 16.6l1.8-1.8M18.4 18.4l1.5-1.5" stroke="${A_D}" stroke-width="2" stroke-linecap="round"/>`,

  // 审核：板子上下分色 + 琥珀夹子 + 白色对勾
  Finished: `<rect x="5.2" y="4.6" width="13.6" height="15.8" rx="2.2" fill="url(#gd-E)"/><rect x="5.2" y="4.6" width="13.6" height="5.4" rx="2.2" fill="${E_L}"/><rect x="9.4" y="2.9" width="5.2" height="3.4" rx="1.2" fill="${X}"/><path d="M9 13.6l2.1 2.1 4-4" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>`,

  // 文档：折角页 + 琥珀折角
  Document: `<path d="M6.6 3.6h6.2v5.6h5.6v10.8a1.6 1.6 0 0 1-1.6 1.6H6.6A1.6 1.6 0 0 1 5 20V5.2a1.6 1.6 0 0 1 1.6-1.6Z" fill="url(#gd-B)"/><path d="M12.8 3.6 18.4 9.2h-5.6V3.6Z" fill="${X}"/><path d="M8.4 13h7.2M8.4 16.4h4.6" stroke="${B_XL}" stroke-width="1.5" stroke-linecap="round"/>`,

  // 分类标签：标签左右分色 + 琥珀孔
  Collection: `<path d="M4.2 11.4 11.5 4.1a1.6 1.6 0 0 1 1.1-.5h5.9a1.6 1.6 0 0 1 1.6 1.6v5.9c0 .4-.2.8-.5 1.1l-7.3 7.3a1.6 1.6 0 0 1-2.2 0l-5.9-5.9a1.6 1.6 0 0 1 0-2.2Z" fill="url(#gd-V)"/><path d="M12.6 3.6h5.9a1.6 1.6 0 0 1 1.6 1.6v5.9c0 .4-.2.8-.5 1.1l-7.3 7.3-7-7L12.6 3.6Z" fill="${V_L}"/><circle cx="15.8" cy="8.2" r="1.7" fill="${X}"/>`,

  // 违禁词：警示三角左右分色 + 白色感叹
  Warning: `<path d="M12 4.4 3.4 19h8.6V4.4Z" fill="url(#gd-A)"/><path d="M12 4.4 20.6 19H12V4.4Z" fill="${A_L}"/><path d="M12 9.6v4.2" stroke="#fff" stroke-width="2" stroke-linecap="round"/><circle cx="12" cy="16.6" r="1.05" fill="#fff"/>`,

  // 附件：文件夹上下分色 + 琥珀标签
  Folder: `<path d="M4.2 6.8A1.9 1.9 0 0 1 6.1 4.9h3.4l1.8 2.3h6.6a1.9 1.9 0 0 1 1.9 1.9v1.7H4.2V6.8Z" fill="url(#gd-A)"/><path d="M4.2 10.8h15.6v6.2a1.9 1.9 0 0 1-1.9 1.9H6.1a1.9 1.9 0 0 1-1.9-1.9v-6.2Z" fill="${A_L}"/><rect x="9.6" y="12.6" width="4.8" height="1.8" rx=".9" fill="${X}"/>`,

  // 数据看板：三柱分色 + 琥珀顶点
  DataLine: `<rect x="3.6" y="12.6" width="3.8" height="7.8" rx="1.2" fill="${B_L}"/><rect x="10.1" y="7.2" width="3.8" height="13.2" rx="1.2" fill="url(#gd-B)"/><rect x="16.6" y="10.4" width="3.8" height="10" rx="1.2" fill="${B_L}"/><circle cx="12" cy="4.6" r="1.9" fill="${X}"/>`,

  // 培训管理：笔记本左右分色 + 琥珀书脊
  Notebook: `<path d="M5.6 5.7A2.1 2.1 0 0 1 7.7 3.6h4.3v16.8H7.7a2.1 2.1 0 0 1-2.1-2.1V5.7Z" fill="${O_L}"/><path d="M12 3.6h4.3a2.1 2.1 0 0 1 2.1 2.1v12.6a2.1 2.1 0 0 1-2.1 2.1H12V3.6Z" fill="url(#gd-O)"/><rect x="5.6" y="3.6" width="2.6" height="16.8" rx="1.3" fill="${X}"/><path d="M14.4 8.6h3M14.4 12.2h3M14.4 15.8h1.8" stroke="#fff" stroke-width="1.5" stroke-linecap="round" opacity=".85"/>`,

  // 资产：立方体三面（浅/深/更浅）
  Box: `<path d="M12 3 20.6 7.8 12 12.6 3.4 7.8 12 3Z" fill="${O_L}"/><path d="M3.4 7.8 12 12.6v8.6L3.4 16.4V7.8Z" fill="url(#gd-O)"/><path d="M20.6 7.8 12 12.6v8.6l8.6-4.8V7.8Z" fill="#FED7AA"/><path d="M3.4 7.8 12 12.6l8.6-4.8" fill="none" stroke="#fff" stroke-width="1.1" opacity=".5"/>`,

  // 检索：放大镜左右分色 + 琥珀镜片
  Search: `<circle cx="10.6" cy="10.6" r="6.4" fill="url(#gd-B)"/><path d="M10.6 4.2a6.4 6.4 0 0 1 0 12.8V4.2Z" fill="${B_L}"/><circle cx="10.6" cy="10.6" r="3" fill="${X}"/><path d="M15.4 15.4 20.4 20.4" stroke="${B_D}" stroke-width="2.6" stroke-linecap="round"/>`,

  // AI 模型：芯片上下分色 + 琥珀内核
  Cpu: `<rect x="6.6" y="6.6" width="10.8" height="10.8" rx="2.2" fill="url(#gd-V)"/><rect x="6.6" y="6.6" width="10.8" height="4.4" rx="2.2" fill="${V_L}"/><rect x="10.1" y="10.6" width="3.8" height="3.8" rx="1.1" fill="${X}"/><g stroke="${V_D}" stroke-width="1.8" stroke-linecap="round"><path d="M9.6 3.6v3M14.4 3.6v3M9.6 17.4v3M14.4 17.4v3M3.6 9.6h3M3.6 14.4h3M17.4 9.6h3M17.4 14.4h3"/></g>`,

  // 设置：齿轮（齿 + 左右分色盘 + 琥珀轴心）
  Setting: `<g stroke="${S_D}" stroke-width="2.8" stroke-linecap="round"><path d="M12 2.8v2.4M12 18.8v2.4M2.8 12h2.4M18.8 12h2.4M5.5 5.5l1.7 1.7M16.8 16.8l1.7 1.7M18.5 5.5l-1.7 1.7M7.2 16.8l-1.7 1.7"/></g><circle cx="12" cy="12" r="6.6" fill="${S_L}"/><path d="M12 5.4a6.6 6.6 0 0 1 0 13.2V5.4Z" fill="url(#gd-S)"/><circle cx="12" cy="12" r="2.9" fill="${X}"/>`,

  // 水印：印章（上下分色 + 琥珀印面）
  Tools: `<path d="M9.6 3.6h4.8a2 2 0 0 1 2 2v3.2l1.7 3.6H5.9l1.7-3.6V5.6a2 2 0 0 1 2-2Z" fill="url(#gd-S)"/><path d="M12 3.6h2.4a2 2 0 0 1 2 2v3.2l1.7 3.6H12V3.6Z" fill="${S_L}"/><rect x="4" y="14.6" width="16" height="6" rx="1.6" fill="${S_L}"/><rect x="4" y="14.6" width="16" height="2.2" rx="1.1" fill="${X}"/>`,

  // 二维码：三定位角 + 琥珀数据块
  Grid: `<rect x="3.6" y="3.6" width="6.8" height="6.8" rx="1.6" fill="url(#gd-S)"/><rect x="5.8" y="5.8" width="2.4" height="2.4" rx=".7" fill="#fff"/><rect x="13.6" y="3.6" width="6.8" height="6.8" rx="1.6" fill="${S_L}"/><rect x="15.8" y="5.8" width="2.4" height="2.4" rx=".7" fill="#fff"/><rect x="3.6" y="13.6" width="6.8" height="6.8" rx="1.6" fill="${S_L}"/><rect x="5.8" y="15.8" width="2.4" height="2.4" rx=".7" fill="#fff"/><rect x="13.6" y="13.6" width="3.2" height="3.2" rx="1" fill="${X}"/><rect x="17.2" y="13.6" width="3.2" height="3.2" rx="1" fill="url(#gd-S)"/><rect x="13.6" y="17.2" width="3.2" height="3.2" rx="1" fill="url(#gd-S)"/><rect x="17.2" y="17.2" width="3.2" height="3.2" rx="1" fill="${S_L}"/>`,

  // 外观：画笔（笔杆分色 + 琥珀笔锋）
  Brush: `<path d="M16.6 3.6 20.4 7.4 12.2 15.6 8.4 11.8 16.6 3.6Z" fill="url(#gd-P)"/><path d="M16.6 3.6 20.4 7.4 16.4 11.4 12.6 7.6 16.6 3.6Z" fill="${P_L}"/><path d="M8.4 11.8 12.2 15.6l-1.2 1.2a3 3 0 0 1-4.2 0l-.4-.4a3 3 0 0 1 0-4.2l2-2Z" fill="${S_L}"/><path d="M5.8 18.6c1.3 0 2.2.9 2.2 1.9 0 .9-1 1.5-2.4 1.5.4-.5.4-1.1 0-1.5-.5-.8-.5-1.9.2-1.9Z" fill="${X}"/>`,

  // 个人资料：人像左右分色 + 琥珀肩章
  UserFilled: `<circle cx="12" cy="8.2" r="4.1" fill="url(#gd-I)"/><path d="M12 4.1a4.1 4.1 0 0 1 0 8.2V4.1Z" fill="${I_L}"/><path d="M4.6 20.4a7.4 7.4 0 0 1 14.8 0H4.6Z" fill="${I_L}"/><path d="M12 13a7.4 7.4 0 0 1 7.4 7.4H12V13Z" fill="url(#gd-I)"/><rect x="9.3" y="16.8" width="5.4" height="1.5" rx=".7" fill="${X}"/>`,
}

const FALLBACK = `<circle cx="12" cy="12" r="7" fill="${S_L}"/><path d="M12 5a7 7 0 0 1 0 14V5Z" fill="url(#gd-S)"/><circle cx="12" cy="12" r="2.4" fill="${X}"/>`

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
.mi {
  flex: none;
  display: inline-block;
  vertical-align: middle;
  /* 极轻投影：把图标从底色上「抬」起来（比内阴影更干净，20px 下不糊） */
  filter: drop-shadow(0 1px 1px rgba(15, 23, 42, .20));
}
</style>

/**
 * 版本更新说明（前端内置）
 *
 * 两处共用：
 *   1. 「我的 → 关于 → 本版本更新」区块（简洁列表）
 *   2. 应用内升级弹层——后台 SysSetting.app_version 的 notes 为空时用它兜底
 *
 * 约定：新版本在最前面追加一条。version 与 mobile/android/app/build.gradle 的
 * versionName 保持一致（弹层按 versionName 匹配）。
 */
export const CHANGELOG = [
  {
    version: '1.12',
    date: '2026-10-11',
    title: '更丰富的首页与实用工具',
    items: [
      '首页改版：金刚区快捷入口 + 数据概览 + 公告',
      '新增「我的数据」：阅读统计、连续天数、AI 使用次数',
      '新增「离线内容」：一键管理已缓存文档',
      '新增「我的收藏」：稍后读，离线可用',
      '文档支持导出 Word / Markdown、复制链接、分享二维码、历史版本',
      '新增「外观」：5 套主题色 + 正文字号调节',
      '新增「应用锁」：打开 App 需 PIN 码',
      '关于页新增本版本更新说明',
      '优化更新提示排版；修复未读红点数字不居中',
    ],
  },
  {
    version: '1.11',
    date: '2026-10-11',
    title: '修复 App 变网页版',
    items: [
      '修复热更包入口错误导致 App 显示成网页版的问题',
      '热更包瘦身（12.7MB → 7.8MB）',
    ],
  },
  {
    version: '1.10',
    date: '2026-10-09',
    title: '更新校验与审查修复',
    items: [
      '补完安装包 SHA-256 校验链路',
      '修复更新地址校验竞态',
      '章节富文本 XSS 净化、注销链路统一、登录日志 90 天清理',
    ],
  },
  {
    version: '1.9',
    date: '2026-10-09',
    title: '应用内升级 + 免责声明',
    items: [
      '应用内下载并直接安装（不再跳浏览器，带进度条）',
      '新增免责声明页',
      '修复管理页被弹到登录页（跨域 iframe 登录态桥接）',
    ],
  },
]

export const CURRENT_VERSION = CHANGELOG[0]

/** 按 versionName 取该版本的更新说明（取不到返回 null） */
export function changelogOf(versionName) {
  const v = String(versionName || '').replace(/^v/, '').trim()
  if (!v) return null
  return CHANGELOG.find((c) => c.version === v) || null
}

/**
 * 把后台下发的 notes 解析成条目数组。
 * 兼容：换行分隔、"1. xxx；2. yyy"、"- xxx"、"· xxx" 等常见写法。
 */
export function parseNotes(notes) {
  const s = String(notes || '').trim()
  if (!s) return []
  return s
    .split(/\r?\n|；|;/)
    .map((x) => x.trim())
    .filter(Boolean)
    .map((x) => x.replace(/^[-·•*]\s*/, '').replace(/^\d+\s*[.、)]\s*/, '').trim())
    .filter(Boolean)
}

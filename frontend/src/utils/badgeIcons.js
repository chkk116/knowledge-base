import {
  EditPen, Edit, Notebook, Trophy, Pointer, Star, StarFilled,
  Collection, Lightning, Coin, Medal, UserFilled, User,
  ChatDotRound, Connection, Timer, Lock
} from '@element-plus/icons-vue'

// 徽章 id -> 线性图标（取代 emoji：跨端渲染一致、风格统一、更现代）
export const BADGE_ICONS = {
  first_post: EditPen,
  writer: Edit,
  author: Notebook,
  sage: Trophy,
  liked10: Pointer,
  liked100: Star,
  liked500: StarFilled,
  fav20: Collection,
  pts100: Lightning,
  pts1000: Coin,
  pts5000: Medal,
  fan1: UserFilled,
  fan50: User,
  fan200: ChatDotRound,
  social10: Connection,
  vet365: Timer,
}

// 徽章 id -> 名称（用于 tooltip / 佩戴提示，与后端 BadgeService.CATALOG 对齐）
export const BADGE_NAMES = {
  first_post: '初执笔',
  writer: '写手',
  author: '著书立说',
  sage: '知识宗师',
  liked10: '小有口碑',
  liked100: '人气作者',
  liked500: '明星作者',
  fav20: '珍藏之选',
  pts100: '初露锋芒',
  pts1000: '积分达人',
  pts5000: '积分王者',
  fan1: '初识',
  fan50: '小有名气',
  fan200: '意见领袖',
  social10: '社交达人',
  vet365: '元老用户',
}

// 徽章 id -> {name, desc, tier} 完整目录（前端副本，与 BadgeService.CATALOG 对齐）
export const BADGE_CATALOG = {
  first_post: { name: '初执笔',    desc: '发布第 1 篇文章',         tier: '入门' },
  writer:     { name: '写手',      desc: '累计发布 5 篇文章',       tier: '进阶' },
  author:     { name: '著书立说',  desc: '累计发布 20 篇文章',      tier: '高级' },
  sage:       { name: '知识宗师',  desc: '累计发布 50 篇文章',      tier: '荣誉' },
  liked10:    { name: '小有口碑',   desc: '获得 10 个赞',           tier: '入门' },
  liked100:   { name: '人气作者',   desc: '获得 100 个赞',          tier: '高级' },
  liked500:   { name: '明星作者',   desc: '获得 500 个赞',          tier: '荣誉' },
  fav20:      { name: '珍藏之选',   desc: '被收藏 20 次',           tier: '进阶' },
  pts100:     { name: '初露锋芒',   desc: '累计 100 积分',          tier: '入门' },
  pts1000:    { name: '积分达人',   desc: '累计 1000 积分',         tier: '高级' },
  pts5000:    { name: '积分王者',   desc: '累计 5000 积分',         tier: '荣誉' },
  fan1:       { name: '初识',       desc: '收获第 1 位粉丝',        tier: '入门' },
  fan50:      { name: '小有名气',   desc: '粉丝达到 50 人',         tier: '进阶' },
  fan200:     { name: '意见领袖',   desc: '粉丝达到 200 人',        tier: '荣誉' },
  social10:   { name: '社交达人',   desc: '关注 10 位作者',         tier: '进阶' },
  vet365:     { name: '元老用户',   desc: '注册满 365 天',          tier: '荣誉' },
}

export function badgeName(id) {
  return (BADGE_CATALOG[id] && BADGE_CATALOG[id].name) || ''
}

// 从前端目录取徽章完整定义（name/desc/tier），主用于不依赖后端的浮窗
export function badgeDef(id) {
  if (!id) return null
  return BADGE_CATALOG[id] ? { id, ...BADGE_CATALOG[id] } : null
}

// 等级 -> 渐变配色（六边形外环 / 发光 / 等级标签 / 卡片顶条）
export const TIER_GRADIENT = {
  '入门': ['#22c55e', '#16a34a'],
  '进阶': ['#38bdf8', '#0ea5e9'],
  '高级': ['#fbbf24', '#f59e0b'],
  '荣誉': ['#a78bfa', '#8b5cf6'],
}

export const LOCK_ICON = Lock
export const DEFAULT_BADGE_ICON = Star

// 取某枚徽章的渐变两色；tier 缺失时回退到品牌紫
export function tierGradient(tier) {
  return TIER_GRADIENT[tier] || ['#6366f1', '#8b5cf6']
}

// 8 位 hex 透明度后缀（现代浏览器支持 #rrggbbaa）
export function withAlpha(hex, alpha) {
  return (hex && hex.startsWith('#')) ? hex + alpha : 'transparent'
}

// 徽章 id -> 渐变两色（与后端 BadgeService.CATALOG 配色对齐），用于佩戴角标/小图
export const BADGE_COLORS = {
  first_post: ['#22c55e', '#16a34a'],
  writer:     ['#38bdf8', '#0ea5e9'],
  author:     ['#fbbf24', '#f59e0b'],
  sage:       ['#a78bfa', '#8b5cf6'],
  liked10:    ['#22c55e', '#16a34a'],
  liked100:   ['#fbbf24', '#f59e0b'],
  liked500:   ['#a78bfa', '#8b5cf6'],
  fav20:      ['#38bdf8', '#0ea5e9'],
  pts100:     ['#22c55e', '#16a34a'],
  pts1000:    ['#fbbf24', '#f59e0b'],
  pts5000:    ['#a78bfa', '#8b5cf6'],
  fan1:       ['#22c55e', '#16a34a'],
  fan50:      ['#38bdf8', '#0ea5e9'],
  fan200:     ['#a78bfa', '#8b5cf6'],
  social10:   ['#38bdf8', '#0ea5e9'],
  vet365:     ['#a78bfa', '#8b5cf6'],
}

// 取佩戴徽章的渐变两色；未知 id 回退品牌紫
export function badgeColor(id) {
  return BADGE_COLORS[id] || ['#6366f1', '#8b5cf6']
}


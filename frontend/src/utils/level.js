// 等级阈值（累计积分）与等级名（帖子王主题）
const TIERS = [
  [0, '萌新'],
  [50, '见习写手'],
  [150, '写手'],
  [350, '活跃写手'],
  [700, '博主'],
  [1200, '资深博主'],
  [2000, '大V'],
  [3500, '文章宗师'],
  [6000, '帖子传奇'],
  [10000, '帖子王'],
]

/**
 * 根据积分计算等级信息
 * @returns {{ level:number, title:string, next:?number, current:number, span:number, progress:number }}
 */
export function getLevel(points = 0) {
  let level = 1
  let title = TIERS[0][1]
  let next = TIERS.length > 1 ? TIERS[1][0] : null
  for (let i = 0; i < TIERS.length; i++) {
    if (points >= TIERS[i][0]) {
      level = i + 1
      title = TIERS[i][1]
      next = TIERS[i + 1] ? TIERS[i + 1][0] : null
    }
  }
  const current = TIERS[level - 1][0]
  const span = next == null ? 0 : next - current
  const progress = next == null ? 100 : Math.min(100, Math.round(((points - current) / span) * 100))
  const nextTitle = next == null ? null : TIERS[level][1]
  return { level, title, next, nextTitle, current, span, progress }
}

/** 全部等级阈值（供排行/详情展示），每项 { min: 累计积分下限, title: 等级名 } */
export const LEVELS = TIERS.map(([min, title]) => ({ min, title }))

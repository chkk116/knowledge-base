// 技能开关持久化：localStorage 够用，不加后端表。
// 默认值全开 —— 保证老用户在没有 UI 之前行为完全不变。
import { ALL_GROUP_IDS, normalizeGroups } from './groups'

const KEY = 'kb_agent_skills_v1'

export function loadSkillGroups() {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return ALL_GROUP_IDS.slice()
    return normalizeGroups(JSON.parse(raw))
  } catch {
    return ALL_GROUP_IDS.slice()
  }
}

export function saveSkillGroups(ids) {
  const safe = normalizeGroups(ids)
  try { localStorage.setItem(KEY, JSON.stringify(safe)) } catch { /* 隐私模式下忽略 */ }
  return safe
}

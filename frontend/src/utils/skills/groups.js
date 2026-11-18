// 技能组：给「勾选技能」用。顺序即 registry 里的工具顺序分组展示顺序。
// 不叫商店：这是本机偏好，纯前端开关，不引入后端表。
export const SKILL_GROUPS = [
  { id: 'docs', label: '文档操作', desc: '检索 / 读取 / 新建 / 改写文档，列出我的文档与文件' },
  { id: 'create', label: '内容生成', desc: '生成图片、短视频、流程图' },
  { id: 'exam', label: '出题测评', desc: '按主题生成考题并加入题库' },
  { id: 'nav', label: '页面导航', desc: '跳转到应用内页面' },
  { id: 'assist', label: '协作辅助', desc: '获取当前时间、需要拍板时向用户提问' },
  { id: 'mail', label: '邮箱', desc: '搜索 / 读取已绑定邮箱的邮件，整封转为知识库文档' }
]

export const ALL_GROUP_IDS = SKILL_GROUPS.map((g) => g.id)

/** 收敛外部数据（localStorage 可能被改坏）：只保留合法组 id，非法值丢弃 */
export function normalizeGroups(value) {
  if (!Array.isArray(value)) return ALL_GROUP_IDS.slice()
  const set = new Set(value.filter((v) => typeof v === 'string' && ALL_GROUP_IDS.includes(v)))
  // 空数组视为「用户一个都没勾」不合法，回落全开，避免 Agent 直接哑掉
  return set.size ? ALL_GROUP_IDS.filter((id) => set.has(id)) : ALL_GROUP_IDS.slice()
}

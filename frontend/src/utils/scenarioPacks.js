/**
 * 场景模板（Scenario Pack）内置定义。
 *
 * 一个「场景」= 一份 JSON 配置包：术语映射 + AI 推荐问题。
 * 存在 kb_sys_setting(key='scenario_pack')，由管理员在「站点设置 → 场景模板」选择。
 * 前端通过 store/scenario.js 读取，术语缺失时回落通用文案 —— 没配置时行为与从前完全一致。
 *
 * 设计约束（刻意收窄）：
 *   - 只覆盖「高频入口叫法」（侧栏菜单 / 首页 / AI 推荐问题），不做全站文案替换；
 *   - 不做数据迁移：切场景只改叫法，文档/考题数据不动；
 *   - 加新场景 = 加一份 JSON，不改任何业务逻辑（严禁把 if 场景==医院 散落进代码）。
 */

export const MAX_TERM_LEN = 20
export const MAX_PROMPTS = 6

/** 内置场景（general 必须在首位，是回落缺省） */
export const SCENARIOS = [
  {
    key: 'general',
    name: '通用知识库',
    desc: '默认形态：文档沉淀、AI 问答、考试练习、学习计划，适合大多数团队',
    points: ['全部默认叫法', '无行业倾向'],
  },
  {
    key: 'hospital',
    name: '医院科室制度学习',
    desc: '制度文件沉淀 + 制度问答 + 日常练习 + 定期考核，贴合科室学习闭环',
    points: ['菜单改为「制度/考核」叫法', 'AI 助手按制度口径回答'],
    terms: {
      write: '撰写制度', myPosts: '我的制度', recycle: '制度回收站', subscriptions: '制度订阅',
      favorites: '常用制度', history: '查阅记录', badges: '学习勋章', files: '附件资料',
      ranking: '学习排行', aiChat: '制度助手', courses: '科室培训', exams: '制度考核',
      practice: '日常练习', docShares: '科室制度库', allDocs: '全部制度',
    },
    quickPrompts: [
      '「三查七对」的具体要求是什么？',
      '汇总本月新修订制度的要点',
      '根据最新制度出 5 道考核题',
    ],
  },
  {
    key: 'school',
    name: '学校教研组',
    desc: '教案与教研资料共享、课后练习、单元测验，服务教研组协作',
    points: ['菜单改为「教案/测验」叫法', '适合教研组协作场景'],
    terms: {
      write: '撰写教案', myPosts: '我的教案', favorites: '常用资源', history: '查阅记录',
      aiChat: '教研助手', courses: '教研活动', exams: '在线测验',
      practice: '课后练习', docShares: '教研资源库', allDocs: '全部资源',
    },
    quickPrompts: [
      '按新课标帮我出一份单元测验',
      '这学期教研活动的重点有哪些？',
      '帮我整理一份听课评课要点',
    ],
  },
  {
    key: 'enterprise',
    name: '企业合规培训',
    desc: '合规制度库 + 新员工学习路径 + 合规考试，服务内控与审计要求',
    points: ['菜单改为「合规/考试」叫法', '培训记录可留档'],
    terms: {
      write: '撰写合规文件', myPosts: '我的文件', favorites: '常用制度', history: '查阅记录',
      aiChat: '合规助手', courses: '合规培训', exams: '合规考试',
      practice: '日常练习', docShares: '合规制度库', allDocs: '全部制度',
    },
    quickPrompts: [
      '新员工入职需要学习哪些合规制度？',
      '数据安全相关的红线条款有哪些？',
      '根据合规手册出 10 道考试题',
    ],
  },
]

const BUILTIN_KEYS = new Set(SCENARIOS.map((s) => s.key))

/** 通用缺省（terms 为空 = 全部回落到代码里的默认文案） */
export const GENERAL_PACK = {
  key: 'general',
  name: '通用知识库',
  terms: {},
  quickPrompts: [],
}

/** 收敛外部配置（管理员可能手填 JSON）：只保留合法键与字符串值，超长截断 */
export function normalizePack(raw) {
  const p = raw && typeof raw === 'object' ? raw : {}
  const key = BUILTIN_KEYS.has(p.key) ? p.key : 'general'
  const builtin = SCENARIOS.find((s) => s.key === key)

  const terms = {}
  if (p.terms && typeof p.terms === 'object') {
    for (const [k, v] of Object.entries(p.terms)) {
      const val = String(v ?? '').trim()
      if (val) terms[k] = val.slice(0, MAX_TERM_LEN)
    }
  }
  const quickPrompts = Array.isArray(p.quickPrompts)
    ? p.quickPrompts.map((x) => String(x ?? '').trim()).filter(Boolean).slice(0, MAX_PROMPTS)
    : []

  return { key, name: p.name || builtin.name, terms, quickPrompts }
}

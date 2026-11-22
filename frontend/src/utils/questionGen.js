// AI 问答（agent 模式）里的「出题」能力（2026-10-20）。
//
// 为什么放在这里而不是各页面各写一份：桌面 AiChat.vue 与 MobileAiChat.vue
// 都跑同一个 runLocalAgent，出题逻辑必须一致（确认话术、预览格式、入库口径）。
//
// 与后台「考试管理 → AI 出题」的区别：
//   - 那边是表单 + 勾选预览，适合批量补题；
//   - 这边是对话驱动，用户说「帮我出 5 道关于请假的单选题」即可，
//     生成后同样**先问要不要入库**，不擅自写题库。

import { selfExamGenerate, selfExamBatchCreate } from '../api/training'
import { listMyShares, listShareDocs } from '../api/docShare'

// DOC 模式自动挑空间时最多探测几个（每个一次列表请求，太多会拖慢出题）
const MAX_SPACE_PROBES = 6

const TYPE_NAME = { SINGLE: '单选', MULTI: '多选', JUDGE: '判断', FILL: '填空', ESSAY: '简答' }
const TYPE_ALIAS = {
  单选: 'SINGLE', 单选题: 'SINGLE', single: 'SINGLE',
  多选: 'MULTI', 多选题: 'MULTI', multi: 'MULTI',
  判断: 'JUDGE', 判断题: 'JUDGE', judge: 'JUDGE',
  填空: 'FILL', 填空题: 'FILL', fill: 'FILL',
  简答: 'ESSAY', 问答题: 'ESSAY', essay: 'ESSAY',
}

function normTypes(raw) {
  const list = Array.isArray(raw) ? raw : raw ? [raw] : []
  const out = []
  for (const t of list) {
    const k = String(t || '').trim()
    const v = TYPE_ALIAS[k] || TYPE_ALIAS[k.toLowerCase()] || (TYPE_NAME[k] ? k : '')
    if (v && !out.includes(v)) out.push(v)
  }
  return out
}

function normUrls(raw) {
  const list = Array.isArray(raw) ? raw : raw ? [raw] : []
  return list.map((u) => String(u || '').trim()).filter(Boolean).slice(0, 5)
}

/**
 * 供 runLocalAgent 的 hooks.createQuestions 调用。
 * @param {object} args 模型给的参数 {topic,count,types,sourceType,shareId,urls,category,extra}
 * @param {object} opts { askUser, isDemo, session }
 *   session 由宿主在一次 Agent 任务内复用（{ webOk, aiOk }）：
 *   模型失败后常会用同样参数重试，若每次都重新弹确认，用户会被问 5、6 遍（2026-10-20 实际发生）。
 * @returns {Promise<string>} 回填进 agent 轨迹的文本
 */
// forcePersonal 保留仅为兼容既有调用方：现在无论有没有 EXAM_MANAGE，出题一律进个人题库
export async function agentCreateQuestions(args = {}, { askUser, isDemo, session } = {}) {
  if (isDemo) return '演示账号是只读的，不能出题（请用正式账号登录后再试）'
  if (typeof askUser !== 'function') return '当前环境不支持向用户确认，无法出题'

  const topic = String(args.topic || '').trim()
  if (!topic) return '出题失败：需要先确定出题主题'

  const count = Math.max(1, Math.min(20, Number(args.count) || 5))
  const sourceType = ['DOC', 'WEB', 'AI'].includes(String(args.sourceType || '').toUpperCase())
    ? String(args.sourceType).toUpperCase() : 'DOC'
  const types = normTypes(args.types)
  const urls = normUrls(args.urls)
  const s = session || {}

  // ---- 先确认：这两档都会离开知识库的边界。同一次任务只问一次，拒绝后也不再问 ----
  if (sourceType === 'WEB') {
    if (s.webOk === false) return '用户之前已拒绝联网出题，不要重试。请改用 DOC，或直接问用户怎么处理'
    if (!urls.length) return '出题失败：依据网页出题需要提供至少一个参考链接'
    if (s.webOk !== true) {
      const ans = await askUser(
        `将让服务器访问这些网页、抓取正文作为出题依据：\n${urls.join('\n')}\n\n确认继续？`,
        ['继续', '取消'],
      )
      // 只认精确点击的选项文案：对话框允许自由输入，「不要继续」里也含「继续」
      s.webOk = String(ans).trim() === '继续'
      if (!s.webOk) return '用户取消了联网出题，不要重试'
    }
  } else if (sourceType === 'AI') {
    if (s.aiOk === false) return '用户之前已拒绝凭通用知识出题，不要重试。请改用 DOC，或直接问用户怎么处理'
    if (s.aiOk !== true) {
      const ans = await askUser(
        '这组题将完全不依据知识库内容，由 AI 凭通用知识生成，可能与你们的实际制度不符。确认继续？',
        ['继续', '取消'],
      )
      s.aiOk = String(ans).trim() === '继续'
      if (!s.aiOk) return '用户取消了通用知识出题，不要重试'
    }
  }

  // ---- DOC 模式：没指定空间就挑一个「真的有文档」的空间 ----
  // 之前直接取列表第一个：普通用户的第一个空间是注册时自动建的「我的空间」，通常是空的，
  // 于是出题拿不到任何材料、只能凭主题瞎编（N1）。这里最多探 MAX_SPACE_PROBES 个空间，
  // 选第一个真有文档的。
  // 都探不到（列表全空 / 请求全失败）就退回第一个，不在这里卡住用户：后端读到空材料会明确
  // 报 400「所选空间没有可用正文的文档」，用户换个空间再试即可。
  let shareId = args.shareId ? Number(args.shareId) : null
  if (sourceType === 'DOC' && !shareId) {
    try {
      const { data } = await listMyShares()
      const spaces = (data || []).filter((sp) => sp && sp.id)
      if (!spaces.length) return '出题失败：你还没有可用的知识库空间'
      shareId = spaces[0].id
      // 注意别叫 s：外层 agentCreateQuestions 里的 s 是 session 对象，重名会静默遮蔽
      for (const sp of spaces.slice(0, MAX_SPACE_PROBES)) {
        try {
          const r = await listShareDocs(sp.id)
          const docs = r?.data || []
          if (docs.some((d) => d && !d.inbox)) { shareId = sp.id; break }
        } catch { /* 单个空间读不到就跳过 */ }
      }
    } catch (e) {
      return '出题失败：读取知识库空间出错'
    }
  }

  let data
  try {
    // 没有「考试管理」权限的用户（个人版）走个人自测接口：题库按 个人-{uid} 隔离
    // forcePersonal：从「我的测验」发起的生成，一律进个人题库（哪怕账号是管理员）
    // Agent / 「我的测验」发起的出题一律进个人题库：
    // 原先按 EXAM_MANAGE 走管理端接口，题目直接落进公共题库，个人题库刷新还是 0 题，
    // 而公共题库练习里会冒出这批个人题（外部测试实测到的「假成功」就是这个根因）。
    const genApi = selfExamGenerate
    const res = await genApi({
      topic,
      count,
      types,
      difficulty: Number(args.difficulty) || 0,
      category: String(args.category || '').trim(),
      extra: String(args.extra || '').trim(),
      sourceType,
      shareId: sourceType === 'DOC' ? shareId : null,
      urls: sourceType === 'WEB' ? urls : null,
    })
    data = res.data || res
  } catch (e) {
    const code = e?.response?.status
    const msg = e?.response?.data?.error || e?.response?.data?.message || e?.message || ''
    if (code === 403) return '出题失败：服务端拒绝了这次请求（' + (msg || '403') + '）'
    if (code === 429) return '出题失败：操作太频繁，请稍等一会儿再试'
    if (/timeout/i.test(msg) || e?.code === 'ECONNABORTED') {
      return `出题超时：生成 ${count} 道题一般要 20~60 秒，这次等了 3 分钟还没回来。建议把题量减到 5 道以内再试`
    }
    return '出题失败：' + (msg || 'AI 服务暂时不可用')
  }

  const qs = (data && data.questions) || []
  if (!qs.length) return 'AI 这次没有产出可用的题目，换个主题或补充资料后再试'

  const materials = (data.materials || []).map((m) => m.title).join('、')
  const basis = materials ? `依据：${materials}` : '依据：AI 通用知识'
  const warn = (data.warnings || []).length ? `\n（${data.warnings.length} 条提示：${data.warnings.slice(0, 2).join('；')}）` : ''

  // 预览只放题干：带上选项/解析会把 agent 上下文撑爆，也让用户读不下去
  const preview = qs
    .map((q, i) => `${i + 1}. [${TYPE_NAME[q.type] || q.type}] ${q.content}`)
    .join('\n')

  const ans = await askUser(
    `已生成 ${qs.length} 道题。\n${basis}\n\n${preview}\n\n要加入题库吗？`,
    ['加入题库', '先不入库'],
  )
  // 只认精确的「加入题库」：自由输入里的「别加入 / 先不加入」都含「加入」，不能算同意
  if (String(ans).trim() !== '加入题库') {
    return `用户选择先不入库。以下是候选题目，可让他到「我的测验」的出题入口重新生成并勾选保存（后台「考试管理 → AI 出题」写入的是公共题库）：\n${preview}`
  }

  try {
    const items = qs.map((q) => ({
      question: {
        type: q.type,
        content: q.content,
        analysis: q.analysis,
        difficulty: q.difficulty,
        category: String(args.category || '').trim(),
      },
      options: (q.options || []).map((o) => ({ label: o.label, content: o.content, isCorrect: !!o.isCorrect })),
    }))
    const saveApi = selfExamBatchCreate
    const res = await saveApi(items)
    const saved = Array.isArray(res.data) ? res.data : (Array.isArray(res) ? res : [])
    if (saved.length !== items.length) {
      return `入库未成功：提交 ${items.length} 道，实际落库 ${saved.length} 道。请到「我的测验 → 题库」确认后重试。${warn}`
    }
    return `已加入个人题库 ${saved.length} 道（${basis}）。可在「我的测验 → 题库」查看。${warn}\n\n${preview}`
  } catch (e) {
    return '题目已生成但入库失败：' + (e?.response?.data?.error || e?.message || '未知错误')
  }
}

// AI 问答（agent 模式）里的「出题」能力（2026-10-20）。
//
// 为什么放在这里而不是各页面各写一份：桌面 AiChat.vue 与 MobileAiChat.vue
// 都跑同一个 runLocalAgent，出题逻辑必须一致（确认话术、预览格式、入库口径）。
//
// 与后台「考试管理 → AI 出题」的区别：
//   - 那边是表单 + 勾选预览，适合批量补题；
//   - 这边是对话驱动，用户说「帮我出 5 道关于请假的单选题」即可，
//     生成后同样**先问要不要入库**，不擅自写题库。

import { adminAiGenerateQuestions, adminBatchCreateQuestions } from '../api/training'
import { listMyShares } from '../api/docShare'

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
 * @param {object} opts { askUser, isDemo }
 * @returns {Promise<string>} 回填进 agent 轨迹的文本
 */
export async function agentCreateQuestions(args = {}, { askUser, isDemo } = {}) {
  if (isDemo) return '演示账号是只读的，不能出题（请用正式账号登录后再试）'
  if (typeof askUser !== 'function') return '当前环境不支持向用户确认，无法出题'

  const topic = String(args.topic || '').trim()
  if (!topic) return '出题失败：需要先确定出题主题'

  const count = Math.max(1, Math.min(20, Number(args.count) || 5))
  const sourceType = ['DOC', 'WEB', 'AI'].includes(String(args.sourceType || '').toUpperCase())
    ? String(args.sourceType).toUpperCase() : 'DOC'
  const types = normTypes(args.types)
  const urls = normUrls(args.urls)

  // ---- 先确认：这两档都会离开知识库的边界 ----
  if (sourceType === 'WEB') {
    if (!urls.length) return '出题失败：依据网页出题需要提供至少一个参考链接'
    const ans = await askUser(
      `将让服务器访问这些网页、抓取正文作为出题依据：\n${urls.join('\n')}\n\n确认继续？`,
      ['继续', '取消'],
    )
    if (!String(ans).includes('继续')) return '用户取消了联网出题'
  } else if (sourceType === 'AI') {
    const ans = await askUser(
      '这组题将完全不依据知识库内容，由 AI 凭通用知识生成，可能与你们的实际制度不符。确认继续？',
      ['继续', '取消'],
    )
    if (!String(ans).includes('继续')) return '用户取消了通用知识出题'
  }

  // ---- DOC 模式：没指定空间就用最近可用的一个 ----
  let shareId = args.shareId ? Number(args.shareId) : null
  if (sourceType === 'DOC' && !shareId) {
    try {
      const { data } = await listMyShares()
      const first = (data || [])[0]
      if (!first) return '出题失败：你还没有可用的知识库空间'
      shareId = first.id
    } catch (e) {
      return '出题失败：读取知识库空间出错'
    }
  }

  let data
  try {
    const res = await adminAiGenerateQuestions({
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
    if (code === 403) return '出题失败：当前账号没有「考试管理」权限，无法写入题库'
    if (code === 429) return '出题失败：操作太频繁，请稍等一会儿再试'
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
  if (!String(ans).includes('加入')) {
    return `用户选择先不入库。以下是候选题目，可让他到「考试管理 → AI 出题」里重新生成并勾选保存：\n${preview}`
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
    const res = await adminBatchCreateQuestions(items)
    const saved = res.data || res || []
    return `已加入题库 ${saved.length} 道（${basis}）。可在「考试管理 → 题库」查看，或去 /admin/exams 直接组卷。${warn}\n\n${preview}`
  } catch (e) {
    return '题目已生成但入库失败：' + (e?.response?.data?.error || e?.message || '未知错误')
  }
}

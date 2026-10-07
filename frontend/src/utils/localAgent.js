// 手机本地 Agent 引擎（2026-10-07）。
//
// 设计：ReAct 风格的前端编排——LLM（走自家后端 /ai/ask 通用模式）负责推理，
// 工具在**手机本地**执行（检索知识库 / 读文档 / 取时间），结果回填后继续推理，
// 直到给出最终答案或到达步数上限。全程不需要外部 agent 服务，离线部分工具（时间）也可用。
//
// 提示协议：要求 LLM 在需要工具时输出一行
//   TOOL: {"tool":"工具名","args":{...}}
// 信息足够时直接输出最终答案。前端解析 TOOL 行→执行→把结果追加进轨迹→进入下一轮。

import { search, getDoc } from '../api/kb'

const MAX_STEPS = 4
const DOC_SNIPPET = 3000

const TOOL_MANUAL = `可用工具（需要时输出一行 TOOL 调用，格式严格为：
TOOL: {"tool":"工具名","args":{...}}
工具列表：
1. search_kb —— 检索知识库文档。参数 {"query":"关键词"}
2. read_doc —— 读取某篇文档全文。参数 {"id":文档id}（id 来自 search_kb 的结果）
3. now —— 获取当前日期时间。参数 {}
当已有信息足够回答时，直接输出最终答案（不要再输出 TOOL 行）。不要编造工具。`

/**
 * 跑一个本地 Agent 任务。
 * @param {object} opts
 *   question  用户任务描述
 *   ask       (text, signal) => Promise<string>  单轮 LLM 调用（通用模式，无知识库注入）
 *   onStep    (text) => void                      每步进展回调（UI 展示）
 *   signal    AbortSignal
 * @returns {Promise<{answer:string, steps:string[]}>}
 */
export async function runLocalAgent({ question, ask, onStep, signal }) {
  const steps = []
  const say = (t) => { steps.push(t); onStep && onStep(t) }
  const trace = []
  let answer = ''

  for (let step = 1; step <= MAX_STEPS; step++) {
    if (signal && signal.aborted) { answer = '（已停止）'; break }
    const parts = ['【任务】' + question]
    if (trace.length) parts.push('【已执行的步骤与结果】\n' + trace.join('\n\n'))
    parts.push('【要求】\n' + TOOL_MANUAL + '\n\n继续任务：信息足够请直接给出最终答案；需要工具则输出 TOOL 行。')

    say(step === 1 ? '正在规划任务…' : `第 ${step}/${MAX_STEPS} 步推理…`)
    const out = String(await ask(parts.join('\n\n'), signal) || '').trim()
    if (signal && signal.aborted) { answer = '（已停止）'; break }

    const m = out.match(/^\s*TOOL:\s*(\{[\s\S]*\})\s*$/m)
    if (!m) { answer = out || '（未得到回答）'; break }

    let call
    try { call = JSON.parse(m[1]) } catch { answer = out; break }
    const tool = String(call.tool || '')
    const args = call.args || {}
    let result = ''
    try {
      if (tool === 'search_kb') {
        const q = String(args.query || '').slice(0, 50)
        say('🔍 检索知识库：' + q)
        const { data } = await search(q)
        const rows = (data || []).slice(0, 5).map((r) => `- [${r.id}] ${r.title || '无标题'}`)
        result = rows.length ? `命中 ${rows.length} 篇：\n` + rows.join('\n') : '没有命中文档'
      } else if (tool === 'read_doc') {
        say('📄 读取文档 #' + args.id)
        const { data } = await getDoc(args.id)
        result = `《${data.title || '无标题'}》正文（节选）：\n` + String(data.content || '').slice(0, DOC_SNIPPET)
      } else if (tool === 'now') {
        result = '当前时间：' + new Date().toLocaleString('zh-CN')
      } else {
        result = '未知工具：' + tool
      }
    } catch (e) {
      result = '工具执行失败：' + ((e && e.message) || e)
    }
    trace.push(`[${step}] TOOL ${tool} ${JSON.stringify(args)}\n结果：${result}`)
  }

  if (!answer) {
    answer = '任务未在 ' + MAX_STEPS + ' 步内完成。已收集的信息：\n\n' + trace.join('\n\n')
  }
  return { answer, steps }
}

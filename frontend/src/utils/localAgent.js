// 手机本地 Agent 引擎（2026-10-07；2026-11-07 抽成 registry + 执行器）。
//
// 设计：ReAct 风格的前端编排——LLM（走自家后端 /ai/ask 通用模式）负责推理，
// 工具在**手机本地**执行（检索知识库 / 读文档 / 取时间），结果回填后继续推理，
// 直到给出最终答案或到达步数上限。全程不需要外部 agent 服务，离线部分工具（时间）也可用。
//
// 提示协议：要求 LLM 在需要工具时输出一行
//   TOOL: {"tool":"工具名","args":{...}}
// 信息足够时直接输出最终答案。前端解析 TOOL 行→执行→把结果追加进轨迹→进入下一轮。
//
// 技能包（Skill Pack）：工具集中在 utils/skills/registry.js，新增工具只改那一个文件。
//   - 手册按启用的技能组裁剪（文档问答不再为图片/视频/出题的手册付 token）
//   - 执行器从「本次启用的工具」里查表，未启用的工具不会被模型误调
//   - enabledGroups 不传 = 全开（默认行为与重构前完全一致）
import { TOOLS } from './skills/registry'
import { buildManual } from './skills/manual'

const MAX_STEPS = 6

// 从模型输出提取第一个 TOOL: {...}：逐个出现点做括号配对（处理字符串与转义），
// 替换旧的贪婪正则——模型在 TOOL 行后多输出内容时会把整个匹配撑爆导致 JSON.parse 失败
function extractToolJson(text) {
  const s = String(text || '')
  for (let i = s.indexOf('TOOL:'); i !== -1; i = s.indexOf('TOOL:', i + 1)) {
    const start = s.indexOf('{', i)
    if (start === -1) continue
    let depth = 0, inStr = false, esc = false
    for (let j = start; j < s.length; j++) {
      const ch = s[j]
      if (esc) { esc = false; continue }
      if (ch === '\\') { esc = true; continue }
      if (ch === '"') { inStr = !inStr; continue }
      if (inStr) continue
      if (ch === '{') depth++
      else if (ch === '}') { depth--; if (depth === 0) return s.slice(start, j + 1) }
    }
  }
  return null
}

/** 剥掉模型回复里的思考块（<think>…</think> / reasoning 字段），只留正式回答 */
function stripThink(t) {
  return String(t || '')
    .replace(/<think>[\s\S]*?<\/think>/gi, '')
    .replace(/^思考过程[\s\S]*?(?=回答|答案|$)/, '')
    .trim()
}

/**
 * 跑一个本地 Agent 任务。
 * @param {object} opts
 *   question        用户任务描述
 *   ask             (text, signal) => Promise<string>  单轮 LLM 调用（通用模式，无知识库注入）
 *   onStep          (text) => void                      每步进展回调（UI 展示）
 *   signal          AbortSignal
 *   hooks           宿主能力集合，未传的对应工具返回「不支持」
 *   enabledGroups   启用的技能组 id 数组；不传 = 全开
 *   hasPerm         可选 (code) => boolean，registry 里声明 perm 的工具会二次校验
 * @returns {Promise<{answer:string, steps:string[]}>}
 */
export async function runLocalAgent({
  question, ask, onStep, signal, hooks = {},
  enabledGroups = null, hasPerm = null
}) {
  const steps = []
  const say = (t) => { steps.push(t); onStep && onStep(t) }
  const trace = []
  let answer = ''
  let lastTool = ''
  let lastResult = ''

  // 手册与可用工具清单是同一份过滤结果：prompt 里没出现的工具，执行器也不认
  const { text: manual, tools } = buildManual(TOOLS, enabledGroups)

  for (let step = 1; step <= MAX_STEPS; step++) {
    if (signal && signal.aborted) { answer = '（已停止）'; break }
    const parts = ['【任务】' + question,
      '（内部指令，禁止在回答中复述、引用或输出下面任何一行；只输出结论或一行 TOOL 调用）']
    if (trace.length) parts.push('【已执行的步骤与结果】\n' + trace.join('\n\n'))
    parts.push('【要求】\n' + manual + '\n\n继续任务：信息足够请直接给出最终答案；需要工具则输出 TOOL 行。')

    say(step === 1 ? '正在规划任务…' : `第 ${step}/${MAX_STEPS} 步推理…`)
    // LLM 调用单独兜底（审查 r10 High）：网络/超时/后端 5xx 不能把整个 Agent 打穿——
    // 已收集的步骤轨迹要保留下来一并展示，否则用户只看到「Agent 执行失败」毫无上下文
    let out
    try {
      out = String(await ask(parts.join('\n\n'), signal) || '').trim()
    } catch (e) {
      if (signal && signal.aborted) { answer = '（已停止）'; break }
      const why = String((e && e.message) || e).slice(0, 200)
      answer = 'AI 服务调用失败：' + why +
        (trace.length ? '\n\n已完成的步骤（结果仍有效）：\n' + trace.join('\n') : '') +
        '\n\n请稍后重试；持续失败请检查网络或联系管理员。'
      break
    }
    if (signal && signal.aborted) { answer = '（已停止）'; break }

    // 模型偶尔会把整段提示词原样复述回来（尤其带上长工具手册、轨迹里又有大段 JSON 时），
    // 那不是答案——直接展示会让用户看到一屏 {"topic":...} 的原始参数。
    const leaked = /^【任务】/.test(out) || out.includes('【已执行的步骤与结果】') || /^【要求】/.test(out)
      // 从手册中间开始复述：出现手册特征串 + 整段过长 + 至少一条 TOOL 行
      || (out.length > 400
          && /(工作方式要求|工具列表|可用工具（需要时输出|禁止在回答中复述)/.test(out)
          && out.includes('TOOL:'))
    if (leaked) {
      answer = '本次没有生成有效回答，请重试一次。'
      break
    }

    const raw = extractToolJson(out)
    if (!raw) { answer = out || '（未得到回答）'; break }

    let call
    try { call = JSON.parse(raw) } catch { answer = out; break }
    const tool = String(call.tool || '')
    const args = call.args || {}
    let result = ''
    try {
      const entry = tools.find((t) => t.id === tool)
      if (!entry) {
        result = '未知工具：' + tool
      } else {
        result = await entry.run({ args, hooks, say, hasPerm })
      }
    } catch (e) {
      result = '工具执行失败：' + ((e && e.message) || e)
    }
    trace.push(`[${step}] TOOL ${tool} ${JSON.stringify(args)}\n结果：${result}`)
    lastTool = tool
    lastResult = String(result || '')
  }

  if (!answer) {
    const tail = lastResult ? `\n\n最后一步（${lastTool}）：${lastResult.slice(0, 200)}` : ''
    answer = `这次没在 ${MAX_STEPS} 步内做完。${tail}\n\n可以换个说法再试，或者把任务拆小一点（比如先出 3 道试试）。`
  }
  return { answer, steps }
}

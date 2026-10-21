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

const MAX_STEPS = 6
const DOC_SNIPPET = 3000

const TOOL_MANUAL = `可用工具（需要时输出一行 TOOL 调用，格式严格为：
TOOL: {"tool":"工具名","args":{...}}
工具列表：
1. search_kb —— 检索知识库文档。参数 {"query":"关键词"}
2. read_doc —— 读取某篇文档全文。参数 {"id":文档id}（id 来自 search_kb 或 list_my_docs）
3. list_my_docs —— 列出「我的文档」（我自己写的文档）。参数 {}
4. now —— 获取当前日期时间。参数 {}
5. create_doc —— 新建一篇文档并**保存进知识库**。参数 {"title":"标题","content":"正文，Markdown 格式","status":"draft|submit"}
   · status 可选：draft = 存草稿（仅自己可见）；submit = 提交审核（默认）。
   · 重要：普通用户创建的文档**不会直接公开**——draft 进草稿箱，submit 进「待审核」等管理员通过；
     管理员账号才会直接发布。所以回答里要如实说明文档处于什么状态，别让用户以为已经公开了。
6. update_doc —— 改写/优化一篇**已有文档**并保存。参数 {"id":文档id,"title":"可选新标题","content":"优化后的完整正文（Markdown）"}
7. navigate —— 跳转到本应用内的页面。参数 {"path":"/m/docs/123"}（文档页为 /m/docs/<id>；首页 /m/home、文档库 /m/docs、我的 /m/profile）
8. ask_user —— **需要用户拍板时提问，不要自己替用户决定**。参数 {"question":"问题","options":["选项A","选项B"]}
   · 用户会从选项里点选，也可以自己输入；返回「用户选择：xxx」。
   · 适用场景：写文档前问风格/篇幅、审查文档时问改哪些部分、有多个方案让用户取舍。
   · 一次只问一个关键问题，别把多个问题堆在一起。
9. list_my_files —— 列出「我的文件」里已上传的图片 / 音视频 / PDF（含可直接用的链接）。参数 {}
   · **要在文档里插入图片 / 视频 / 音频时，先用它拿到 url**，再写进正文。写法（**别直接写 HTML 标签，会被转义**）：
     图片：![说明](url) ｜ 视频：@[video](url) ｜ 音频：@[audio](url)
10. create_flowchart —— **生成流程图**并写进共享文档（画布）。参数：
    {"title":"流程图标题","docId":可选(不传就新建一篇),"nodes":[{"id":"1","label":"开始","shape":"ellipse"}],"edges":[{"from":"1","to":"2","label":"是"}]}
    · shape 取值：ellipse 开始/结束、rect 普通步骤、diamond 判断分支、doc 文档、db 数据库、sub 子流程。
    · **只需给节点和连线，坐标由前端自动排版**，不要自己编位置。
    · 适用：「画个流程」「生成审批流程图」「把步骤画成图」这类要求。
11. create_questions —— **用 AI 生成考试题目并加入题库**。参数：
    {"topic":"出题主题","count":5,"types":["SINGLE","MULTI","JUDGE"],"sourceType":"DOC|WEB|AI","urls":["链接"],"category":"分类","extra":"额外要求"}
    · types：SINGLE 单选 / MULTI 多选 / JUDGE 判断 / FILL 填空 / ESSAY 简答；count 最多 20。
    · **sourceType 默认用 DOC**（依据知识库文档）：宿主会自动取该空间最近 8 篇文档作依据，
      **不需要先 search_kb**——search_kb 查到的是「我的文档」id，和共享空间文档 id 不是同一套，别拿来当 docIds。
    · 只有用户明确说「不限于知识库」「凭你自己的想法」「从网上找」时才用 AI 或 WEB；这两档宿主会自动弹确认，你不用自己问。
    · **不要因为检索不到内容就擅自改成 AI**——那会绕开用户的制度文档，出的题可能跟实际规定不符。拿不准就用 DOC。
    · 生成后宿主会问用户「要不要入库」，入库成功才算完成。
    · 只有拥有「考试管理」权限的账号可用；没权限会返回明确失败原因。
    · 适用：「帮我出几道关于 xx 的题」「根据某篇文档出考题」。用户没说要几道时按 5 道、以单选为主。

工作方式要求：
- 用户说「审查/优化/改写我某篇文档」时：先用 list_my_docs（或 search_kb）定位到文档 → read_doc 读全文 → 分析 → 用 update_doc 写回优化后的**完整正文**（不是只给建议，要真正落地）。
- 用户说「写一篇…」「帮我整理成文档」「存进知识库」时：用 create_doc 真正新建，不要只在回答里复述内容。
- 想让用户看某篇文档时，用 navigate 直接跳过去。
- 正文用 Markdown 写（标题 #、列表 -、加粗 **、代码块 \`\`\`）。
- **预计消耗大的操作先问用户**：例如要连续读 3 篇以上长文档、反复多次调用工具、或写入很长的正文时，
  先用 ask_user 说明「这一步会消耗较多 token」并确认，别闷头把额度烧掉。
- 工具返回「失败 / 超时」时，**不要用同样的参数再调一次**；最多调整一次参数，仍失败就如实告诉用户失败原因并停下。
  把步数全耗在重复同一个调用上，用户只会看到一条报错。
- 信息足够时直接给出最终答案（不要再输出 TOOL 行）。不要编造工具。`

/**
 * 跑一个本地 Agent 任务。
 * @param {object} opts
 *   question  用户任务描述
 *   ask       (text, signal) => Promise<string>  单轮 LLM 调用（通用模式，无知识库注入）
 *   onStep    (text) => void                      每步进展回调（UI 展示）
 *   signal    AbortSignal
 *   hooks     { navigate?, createDoc?, updateDoc?, listMyDocs?, listMyFiles?, insertFlowchart?, createQuestions?, askUser? }
 *             宿主能力集合，未传的对应工具返回「不支持」
 * @returns {Promise<{answer:string, steps:string[]}>}
 */
export async function runLocalAgent({ question, ask, onStep, signal, hooks = {} }) {
  const steps = []
  const say = (t) => { steps.push(t); onStep && onStep(t) }
  const trace = []
  let answer = ''
  let lastTool = ''
  let lastResult = ''

  for (let step = 1; step <= MAX_STEPS; step++) {
    if (signal && signal.aborted) { answer = '（已停止）'; break }
    const parts = ['【任务】' + question]
    if (trace.length) parts.push('【已执行的步骤与结果】\n' + trace.join('\n\n'))
    parts.push('【要求】\n' + TOOL_MANUAL + '\n\n继续任务：信息足够请直接给出最终答案；需要工具则输出 TOOL 行。')

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
    if (/^【任务】/.test(out) || out.includes('【已执行的步骤与结果】') || /^【要求】/.test(out)) {
      answer = '这次模型把任务描述原样复述了一遍，没给出结论。\n\n再发一次通常就好了；也可以把任务说小一点（例如「出 3 道关于 VLAN 的单选题，加进题库」）。'
      break
    }

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
      } else if (tool === 'list_my_docs') {
        if (typeof hooks.listMyDocs !== 'function') {
          result = '当前环境不支持列出文档'
        } else {
          say('📚 列出我的文档')
          result = await hooks.listMyDocs()
        }
      } else if (tool === 'navigate') {
        if (typeof hooks.navigate !== 'function') {
          result = '当前环境不支持页面跳转'
        } else {
          // 应用内路径白名单：Agent 输出可能被检索内容 prompt injection 影响，
          // 放行 //evil.com 或 https:// 形式会跳出应用（外部复审 2026-10-08）；
          // 反斜杠也要拦（审查 r10 High）：URL 解析器会把 /\evil.com 归一成 //evil.com
          const path = String(args.path || '').slice(0, 200).trim()
          if (!path.startsWith('/') || path.startsWith('//')
              || path.includes('\\') || /[\x00-\x1f\x7f]/.test(path)) {
            result = 'path 必须是应用内绝对路径（以单个 / 开头、不含反斜杠与控制字符），如 /m/docs/123'
          } else {
            say('➡️ 跳转到 ' + path)
            result = await hooks.navigate(path)
          }
        }
      } else if (tool === 'ask_user') {
        if (typeof hooks.askUser !== 'function') {
          result = '当前环境不支持向用户提问，请自行合理假设后继续'
        } else {
          const q = String(args.question || '').slice(0, 300)
          const opts = Array.isArray(args.options)
            ? args.options.slice(0, 6).map((o) => String(o).slice(0, 80))
            : []
          if (!q) {
            result = '提问内容为空'
          } else {
            say('❓ 等待你确认：' + q)
            const ans = await hooks.askUser(q, opts)
            result = ans
              ? '用户选择：' + ans
              : '用户未做选择（可能取消了）。请自行按最合理的方案继续，不要重复问同一个问题。'
          }
        }
      } else if (tool === 'list_my_files') {
        if (typeof hooks.listMyFiles !== 'function') {
          result = '当前环境不支持列出文件'
        } else {
          say('📎 列出我的文件')
          result = await hooks.listMyFiles()
        }
      } else if (tool === 'create_flowchart') {
        if (typeof hooks.insertFlowchart !== 'function') {
          result = '当前环境不支持生成流程图'
        } else {
          const title = String(args.title || '流程图').slice(0, 80)
          const ns = Array.isArray(args.nodes) ? args.nodes : []
          const es = Array.isArray(args.edges) ? args.edges : []
          if (!ns.length) {
            result = '节点为空，未生成流程图'
          } else {
            // buildFlowchartGraph 内部会把节点截到 40 / 连线截到 60，播报数量须与之一致
            result = await hooks.insertFlowchart({ title, docId: args.docId, nodes: ns, edges: es })
          }
        }
      } else if (tool === 'create_questions') {
        if (typeof hooks.createQuestions !== 'function') {
          result = '当前环境不支持 AI 出题'
        } else {
          const topic = String(args.topic || '').slice(0, 200)
          if (!topic) {
            result = '缺少出题主题'
          } else {
            say('📝 AI 出题：' + topic.slice(0, 30))
            // 确认（外网/通用知识）、生成、入库都封在宿主里：这里只管调与回填
            result = await hooks.createQuestions({ ...args, topic })
          }
        }
      } else if (tool === 'create_doc') {
        if (typeof hooks.createDoc !== 'function') {
          result = '当前环境不支持保存文档'
        } else {
          const title = String(args.title || 'Agent 整理').slice(0, 100)
          // 正文上限：超长正文既占库存储，又会在后续步骤反复进上下文放大 token 消耗
          const content = String(args.content || '').slice(0, 20000)
          if (!content.trim()) {
            result = '内容为空，未保存'
          } else {
            const status = args.status === 'draft' ? 'draft' : 'submit'
            say('💾 新建文档：' + title + (status === 'draft' ? '（草稿）' : '（提交审核）'))
            result = await hooks.createDoc({ title, content, status })
          }
        }
      } else if (tool === 'update_doc') {
        if (typeof hooks.updateDoc !== 'function') {
          result = '当前环境不支持更新文档'
        } else {
          const id = (typeof args.id === 'string' || typeof args.id === 'number') ? args.id : ''
          const content = String(args.content || '').slice(0, 20000)
          const title = args.title ? String(args.title).slice(0, 100) : ''
          if (!id) {
            result = '缺少文档 id（必须是字符串或数字），无法更新'
          } else if (!content.trim()) {
            result = '正文为空，未更新'
          } else {
            say('✏️ 更新文档 #' + id + (title ? '（' + title + '）' : ''))
            result = await hooks.updateDoc({ id, title, content })
          }
        }
      } else {
        result = '未知工具：' + tool
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

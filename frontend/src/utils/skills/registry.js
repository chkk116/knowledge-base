// Agent 工具注册表：新增工具只改这一个文件（manual + run + 分组 + 可选 perm）。
// 执行器（utils/localAgent.js）只负责查表、调用、回填轨迹，不再写 if/else 分支。
//
// 约定：
//   run({ args, hooks, say }) => Promise<string> | string
//     args  模型输出的原始参数（必须自己校验长度/类型，别信模型）
//     hooks 宿主能力，缺失时返回「当前环境不支持 xxx」
//     say   进度播报（会同时进轨迹）
//   perm   可选，声明所需权限码；由宿主/执行器做二次校验（展示层不能只靠它，
//          普通用户几乎没有管理员权限码，一刀切过滤会把工具砍光）
import { search, getDoc } from '../../api/kb'

const DOC_SNIPPET = 3000
const unsupported = (what) => `当前环境不支持${what}`

export const TOOLS = [
  {
    id: 'search_kb',
    group: 'docs',
    manual: `search_kb —— 检索知识库文档。参数 {"query":"关键词"}`,
    async run({ args, say }) {
      const q = String(args.query || '').slice(0, 50)
      say('🔍 检索知识库：' + q)
      const { data } = await search(q)
      const rows = (data || []).slice(0, 5).map((r) => `- [${r.id}] ${r.title || '无标题'}`)
      return rows.length ? `命中 ${rows.length} 篇：\n` + rows.join('\n') : '没有命中文档'
    }
  },
  {
    id: 'read_doc',
    group: 'docs',
    manual: `read_doc —— 读取某篇文档全文。参数 {"id":文档id}（id 来自 search_kb 或 list_my_docs）`,
    async run({ args, say }) {
      say('📄 读取文档 #' + args.id)
      const { data } = await getDoc(args.id)
      return `《${data.title || '无标题'}》正文（节选）：\n` + String(data.content || '').slice(0, DOC_SNIPPET)
    }
  },
  {
    id: 'list_my_docs',
    group: 'docs',
    manual: `list_my_docs —— 列出「我的文档」（我自己写的文档）。参数 {}`,
    async run({ hooks, say }) {
      if (typeof hooks.listMyDocs !== 'function') return unsupported('列出文档')
      say('📚 列出我的文档')
      return await hooks.listMyDocs()
    }
  },
  {
    id: 'now',
    group: 'assist',
    manual: `now —— 获取当前日期时间。参数 {}`,
    run() {
      return '当前时间：' + new Date().toLocaleString('zh-CN')
    }
  },
  {
    id: 'create_doc',
    group: 'docs',
    manual: `create_doc —— 新建一篇文档并**保存进知识库**。参数 {"title":"标题","content":"正文，Markdown 格式","status":"draft|submit"}
   · status 可选：draft = 存草稿（仅自己可见）；submit = 提交审核（默认）。
   · 重要：普通用户创建的文档**不会直接公开**——draft 进草稿箱，submit 进「待审核」等管理员通过；
     管理员账号才会直接发布。所以回答里要如实说明文档处于什么状态，别让用户以为已经公开了。`,
    async run({ args, hooks, say }) {
      if (typeof hooks.createDoc !== 'function') return unsupported('保存文档')
      const title = String(args.title || 'Agent 整理').slice(0, 100)
      // 正文上限：超长正文既占库存储，又会在后续步骤反复进上下文放大 token 消耗
      const content = String(args.content || '').slice(0, 20000)
      if (!content.trim()) return '内容为空，未保存'
      const status = args.status === 'draft' ? 'draft' : 'submit'
      say('💾 新建文档：' + title + (status === 'draft' ? '（草稿）' : '（提交审核）'))
      return await hooks.createDoc({ title, content, status })
    }
  },
  {
    id: 'update_doc',
    group: 'docs',
    manual: `update_doc —— 改写/优化一篇**已有文档**并保存。参数 {"id":文档id,"title":"可选新标题","content":"优化后的完整正文（Markdown）"}`,
    async run({ args, hooks, say }) {
      if (typeof hooks.updateDoc !== 'function') return unsupported('更新文档')
      const id = (typeof args.id === 'string' || typeof args.id === 'number') ? args.id : ''
      const content = String(args.content || '').slice(0, 20000)
      const title = args.title ? String(args.title).slice(0, 100) : ''
      if (!id) return '缺少文档 id（必须是字符串或数字），无法更新'
      if (!content.trim()) return '正文为空，未更新'
      say('✏️ 更新文档 #' + id + (title ? '（' + title + '）' : ''))
      return await hooks.updateDoc({ id, title, content })
    }
  },
  {
    id: 'create_image',
    group: 'create',
    manual: `create_image —— 生成一张图片（文生图）。参数 {"prompt":"画面描述，中英文皆可，越具体越好"}
   · 返回里带「图片已生成，Markdown：![生成的图片](/uploads/…)」，**把那行 Markdown 原样放进最终回答**。
   · 用户想要插图/画图/生成图片时用这个；描述里写清主体、风格、构图。
   · **给文章配图**：先 create_image 拿到图片 URL，再在 create_doc / update_doc 的正文里
     以**独立一行**插入图片语法：![图片说明](/uploads/…)（一篇文章多张图就多次调用、每张独占一行），
     图片会随正文一起保存并在文章页正常显示。`,
    async run({ args, hooks, say }) {
      if (typeof hooks.createImage !== 'function') return unsupported('生成图片')
      const pr = String(args.prompt || '').slice(0, 800)
      if (!pr) return 'prompt 不能为空，请给出具体的画面描述'
      say('🎨 生成图片：' + pr.slice(0, 40))
      return await hooks.createImage(pr)
    }
  },
  {
    id: 'create_video',
    group: 'create',
    manual: `create_video —— 生成一段短视频（文生视频，约 5-10 秒画面）。参数 {"prompt":"视频描述，含主体、动作、镜头语言"}
   · **较慢**：一次调用会同步等待 1-4 分钟，期间不要重复调用；返回里带「视频已生成，Markdown：@[video](/uploads/…)」。
   · 给文章配视频：拿到 URL 后，在 update_doc 正文里以**独立一行**插入 @[video](/uploads/…mp4)。
   · 用户明确要视频/短视频时才用；只要插图请用 create_image（更快更便宜）。`,
    async run({ args, hooks, say }) {
      if (typeof hooks.createVideo !== 'function') return unsupported('生成视频')
      const vp = String(args.prompt || '').slice(0, 800)
      if (!vp) return 'prompt 不能为空，请给出具体的视频画面描述'
      say('🎬 生成视频（约 1-4 分钟）：' + vp.slice(0, 40))
      return await hooks.createVideo(vp)
    }
  },
  {
    id: 'navigate',
    group: 'nav',
    manual: `navigate —— 跳转到本应用内的页面。参数 {"path":"/m/doc/12/34"}（共享文档页为 /m/doc/<空间id>/<文档id>；首页 /m/home、文档库 /m/docs、我的 /m/profile）
   · ⚠️ **没有 /m/docs/<id> 这个路由**，只给文档 id 会直接 404。
   · create_flowchart 等工具的返回里已经带了「打开地址：…」，**原样用它**，不要自己拼路径。`,
    async run({ args, hooks, say }) {
      if (typeof hooks.navigate !== 'function') return unsupported('页面跳转')
      // 应用内路径白名单：Agent 输出可能被检索内容 prompt injection 影响，
      // 放行 //evil.com 或 https:// 形式会跳出应用（外部复审 2026-10-08）；
      // 反斜杠也要拦（审查 r10 High）：URL 解析器会把 /\evil.com 归一成 //evil.com
      const path = String(args.path || '').slice(0, 200).trim()
      if (!path.startsWith('/') || path.startsWith('//')
          || path.includes('\\') || /[\x00-\x1f\x7f]/.test(path)) {
        return 'path 必须是应用内绝对路径（以单个 / 开头、不含反斜杠与控制字符），如 /m/docs/123'
      }
      say('➡️ 跳转到 ' + path)
      return await hooks.navigate(path)
    }
  },
  {
    id: 'ask_user',
    group: 'assist',
    manual: `ask_user —— **需要用户拍板时提问，不要自己替用户决定**。参数 {"question":"问题","options":["选项A","选项B"]}
   · 用户会从选项里点选，也可以自己输入；返回「用户选择：xxx」。
   · 适用场景：写文档前问风格/篇幅、审查文档时问改哪些部分、有多个方案让用户取舍。
   · 一次只问一个关键问题，别把多个问题堆在一起。`,
    async run({ args, hooks, say }) {
      if (typeof hooks.askUser !== 'function') return '当前环境不支持向用户提问，请自行合理假设后继续'
      const q = String(args.question || '').slice(0, 300)
      const opts = Array.isArray(args.options)
        ? args.options.slice(0, 6).map((o) => String(o).slice(0, 80))
        : []
      if (!q) return '提问内容为空'
      say('❓ 等待你确认：' + q)
      const ans = await hooks.askUser(q, opts)
      return ans
        ? '用户选择：' + ans
        : '用户未做选择（可能取消了）。请自行按最合理的方案继续，不要重复问同一个问题。'
    }
  },
  {
    id: 'list_my_files',
    group: 'docs',
    manual: `list_my_files —— 列出「我的文件」里已上传的图片 / 音视频 / PDF（含可直接用的链接）。参数 {}
   · **要在文档里插入图片 / 视频 / 音频时，先用它拿到 url**，再写进正文。写法（**别直接写 HTML 标签，会被转义**）：
     图片：![说明](url) ｜ 视频：@[video](url) ｜ 音频：@[audio](url)`,
    async run({ hooks, say }) {
      if (typeof hooks.listMyFiles !== 'function') return unsupported('列出文件')
      say('📎 列出我的文件')
      return await hooks.listMyFiles()
    }
  },
  {
    id: 'create_flowchart',
    group: 'create',
    manual: `create_flowchart —— **生成流程图**并写进共享文档（画布）。参数：
    {"title":"流程图标题","docId":可选(不传就新建一篇),"nodes":[{"id":"1","label":"开始","shape":"ellipse"}],"edges":[{"from":"1","to":"2","label":"是"}]}
    · shape 取值：ellipse 开始/结束、rect 普通步骤、diamond 判断分支、doc 文档、db 数据库、sub 子流程。
    · **只需给节点和连线，坐标由前端自动排版**，不要自己编位置。
    · 适用：「画个流程」「生成审批流程图」「把步骤画成图」这类要求。`,
    async run({ args, hooks }) {
      if (typeof hooks.insertFlowchart !== 'function') return unsupported('生成流程图')
      const title = String(args.title || '流程图').slice(0, 80)
      const ns = Array.isArray(args.nodes) ? args.nodes : []
      const es = Array.isArray(args.edges) ? args.edges : []
      if (!ns.length) return '节点为空，未生成流程图'
      // buildFlowchartGraph 内部会把节点截到 40 / 连线截到 60，播报数量须与之一致
      return await hooks.insertFlowchart({ title, docId: args.docId, nodes: ns, edges: es })
    }
  },
  {
    id: 'create_questions',
    group: 'exam',
    perm: 'EXAM_MANAGE',
    manual: `create_questions —— **用 AI 生成考试题目并加入题库**。参数：
    {"topic":"出题主题","count":5,"types":["SINGLE","MULTI","JUDGE"],"sourceType":"DOC|WEB|AI","urls":["链接"],"category":"分类","extra":"额外要求"}
    · types：SINGLE 单选 / MULTI 多选 / JUDGE 判断 / FILL 填空 / ESSAY 简答；count 最多 20。
    · **sourceType 默认用 DOC**（依据知识库文档）：宿主会自动取该空间最近 8 篇文档作依据，
      **不需要先 search_kb**——search_kb 查到的是「我的文档」id，和共享空间文档 id 不是同一套，别拿来当 docIds。
    · 只有用户明确说「不限于知识库」「凭你自己的想法」「从网上找」时才用 AI 或 WEB；这两档宿主会自动弹确认，你不用自己问。
    · **不要因为检索不到内容就擅自改成 AI**——那会绕开用户的制度文档，出的题可能跟实际规定不符。拿不准就用 DOC。
    · 生成后宿主会问用户「要不要入库」，入库成功才算完成。
    · 只有拥有「考试管理」权限的账号可用；没权限会返回明确失败原因。
    · 适用：「帮我出几道关于 xx 的题」「根据某篇文档出考题」。用户没说要几道时按 5 道、以单选为主。`,
    async run({ args, hooks, say, hasPerm }) {
      if (typeof hooks.createQuestions !== 'function') return '当前环境不支持 AI 出题'
      // 权限二次校验：即便模型从别处猜到工具名，也不能绕过权限码
      if (hasPerm && !hasPerm('EXAM_MANAGE')) return '没有「考试管理」权限，无法使用 AI 出题'
      const topic = String(args.topic || '').slice(0, 200)
      if (!topic) return '缺少出题主题'
      say('📝 AI 出题：' + topic.slice(0, 30))
      // 确认（外网/通用知识）、生成、入库都封在宿主里：这里只管调与回填
      return await hooks.createQuestions({ ...args, topic })
    }
  }
]

export const TOOL_BY_ID = Object.fromEntries(TOOLS.map((t) => [t.id, t]))

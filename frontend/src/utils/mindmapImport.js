// 思维导图导入：把外部格式转成统一的树结构 { text, children[] }
//
// 支持的来源：
//   1. .xmind  —— 本质是 ZIP 包，内含 content.json（Xmind 8+/2020+）或 content.xml（老版）。
//      浏览器端零依赖：手写 ZIP 中央目录解析 + 原生 DecompressionStream('deflate-raw') 解压。
//   2. Markdown —— 兼容三种常见写法：# 标题层级、- 无序列表（含缩进嵌套）、纯文本缩进。
//      幕布 / 知犀 / 飞书文档的「复制为 Markdown」基本都落在这些形态里。
//   3. OPML    —— 幕布、知犀、Xmind 都能导出；结构天然是树，是最稳的中间格式。
//   4. 纯文本缩进 —— 兜底，Tab / 2空格 / 4空格 都认。
//
// 统一约定：返回 { text, children } 单根树；多根时虚拟一个根节点收拢（children 挂上去）。

// ============================ ZIP 解析 ============================

/**
 * 统一把 ArrayBuffer / Uint8Array（含 Node Buffer）/ Blob / File 转成 ArrayBuffer。
 * 注意不能只判断 ArrayBuffer + 调 .arrayBuffer()：Uint8Array 没有这个方法，
 * 而 Node 的 Buffer 正是 Uint8Array 子类（单测会直接炸）。
 */
export async function toArrayBuffer(input) {
  if (input instanceof ArrayBuffer) return input
  if (ArrayBuffer.isView(input)) {
    // 视图可能未覆盖整个 buffer，按 byteOffset/byteLength 精确切出
    return input.buffer.slice(input.byteOffset, input.byteOffset + input.byteLength)
  }
  if (input && typeof input.arrayBuffer === 'function') return input.arrayBuffer()
  throw new Error('无法识别的文件输入类型（需要 File / Blob / ArrayBuffer / Uint8Array）')
}

/**
 * 解析 ZIP，返回 { 文件名: Uint8Array }。
 * 只实现「读」所需的最小子集：扫 End of Central Directory → 遍历中央目录 → 取每个条目的
 * 本地头偏移 → 按压缩方式 inflate。Xmind 用 deflate(8) 或 store(0)，足够。
 */
export async function unzip(buf) {
  const u8 = buf instanceof Uint8Array ? buf : new Uint8Array(buf)
  const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength)

  // 1. 从尾部回扫 EOCD（0x06054b50），注释最长 65535，因此最多回扫 64KB+22
  const maxBack = Math.min(u8.length, 65557)
  let eocd = -1
  for (let i = u8.length - 22; i >= u8.length - maxBack; i--) {
    if (i < 0) break
    if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break }
  }
  if (eocd < 0) throw new Error('不是有效的 ZIP 文件（未找到 EOCD）')

  const count = dv.getUint16(eocd + 10, true)      // 中央目录条目数
  let off = dv.getUint32(eocd + 16, true)          // 中央目录起始偏移

  const files = {}
  for (let i = 0; i < count; i++) {
    if (dv.getUint32(off, true) !== 0x02014b50) break   // 中央目录头签名
    const method = dv.getUint16(off + 10, true)
    const compSize = dv.getUint32(off + 20, true)
    const nameLen = dv.getUint16(off + 28, true)
    const extraLen = dv.getUint16(off + 30, true)
    const cmtLen = dv.getUint16(off + 32, true)
    const localOff = dv.getUint32(off + 42, true)
    const name = utf8(u8.subarray(off + 46, off + 46 + nameLen))

    // 本地头：文件名长度在第 26 字节、扩展区长度在第 28 字节，真实数据起点要跳过本地头的这两段
    if (dv.getUint32(localOff, true) === 0x04034b50) {
      const lNameLen = dv.getUint16(localOff + 26, true)
      const lExtraLen = dv.getUint16(localOff + 28, true)
      const dataStart = localOff + 30 + lNameLen + lExtraLen
      const raw = u8.subarray(dataStart, dataStart + compSize)
      if (method === 0) {
        files[name] = raw
      } else if (method === 8) {
        files[name] = await inflateRaw(raw)
      }
      // 其他压缩方式（bzip2/lzma）Xmind 不用，跳过
    }
    off += 46 + nameLen + extraLen + cmtLen
  }
  return files
}

/** 原生 deflate-raw 解压（Chrome 103+ / Edge / Firefox 113+ / Safari 16.4+） */
async function inflateRaw(bytes) {
  if (typeof DecompressionStream === 'undefined') {
    throw new Error('当前浏览器不支持原生解压，请用较新版本的 Chrome / Edge')
  }
  // 注意：subarray 出来的视图可能未与 buffer 对齐，必须先复制再交给 stream
  const copy = bytes.slice()
  const ds = new DecompressionStream('deflate-raw')
  const stream = new Blob([copy]).stream().pipeThrough(ds)
  return new Uint8Array(await new Response(stream).arrayBuffer())
}

function utf8(bytes) {
  return new TextDecoder('utf-8').decode(bytes)
}

// ============================ Xmind ============================

/**
 * 解析 .xmind → 树。
 * content.json 结构（Xmind 2020+）：数组，每项是一张画布 sheet，
 *   { title, rootTopic: { title, children: { attached: [ {title, children:{attached:[...]}} ] } } }
 * content.xml 结构（Xmind 8）：<xmap-content><sheet><topic><title>x</title><children><topics type="attached"><topic>…</topic>
 */
export async function parseXmind(fileOrBuffer) {
  const buf = await toArrayBuffer(fileOrBuffer)
  const files = await unzip(buf)

  // 优先 content.json（新版），回退 content.xml（老版）
  const jsonName = Object.keys(files).find((n) => /(^|\/)content\.json$/i.test(n))
  if (jsonName) {
    const text = utf8(files[jsonName])
    return fromXmindJson(text)
  }
  const xmlName = Object.keys(files).find((n) => /(^|\/)content\.xml$/i.test(n))
  if (xmlName) {
    const text = utf8(files[xmlName])
    return fromXmindXml(text)
  }
  // 兼容：有些工具导出的是 .xmind 里放 content.xml 但改名为别的
  const anyXml = Object.keys(files).find((n) => /content.*\.xml$/i.test(n))
  if (anyXml) return fromXmindXml(utf8(files[anyXml]))
  throw new Error('这个 .xmind 里没有找到 content.json 或 content.xml，可能不是标准 Xmind 文件')
}

/** content.json → 树 */
export function fromXmindJson(text) {
  let arr
  try {
    arr = JSON.parse(text)
  } catch {
    throw new Error('Xmind 的 content.json 解析失败（内容不是合法 JSON）')
  }
  const sheets = Array.isArray(arr) ? arr : [arr]
  const roots = []
  for (const sheet of sheets) {
    const rt = sheet?.rootTopic
    if (!rt) continue
    const tree = topicJsonToTree(rt)
    // 多画布：套一层画布名做主节点，避免多个根被 mergeRoots 拍平后丢失来源
    if (sheets.length > 1) {
      roots.push({ text: cleanText(sheet.title) || '画布', children: [tree] })
    } else {
      roots.push(tree)
    }
  }
  if (!roots.length) throw new Error('Xmind 文件里没有任何主题（rootTopic 缺失）')
  return mergeRoots(roots)
}

function topicJsonToTree(topic) {
  const node = { text: cleanText(topic?.title), children: [] }
  const attached = topic?.children?.attached
  if (Array.isArray(attached)) {
    for (const c of attached) node.children.push(topicJsonToTree(c))
  }
  // 概要 / 边界里的子主题也算进来（否则内容会静默丢失）
  const summary = topic?.children?.summary
  if (Array.isArray(summary)) {
    for (const c of summary) node.children.push(topicJsonToTree(c))
  }
  const detached = topic?.children?.detached
  if (Array.isArray(detached)) {
    for (const c of detached) node.children.push(topicJsonToTree(c))
  }
  return node
}

/** content.xml → 树（无 DOMParser 依赖的极简解析：只取嵌套的 <topic><title>） */
export function fromXmindXml(text) {
  const roots = []
  // 找所有 <title>…</title> 并按出现顺序，配合 topic 的嵌套深度重建树。
  // 用一个显式栈扫描 <topic> 开合与 <title> 文本即可，避免引入 XML 解析器。
  const tagRe = /<(\/?)(topic|sheet|title)\b([^>]*)>|([^<]+)/gi
  const stack = []
  let cur = null
  let inTitle = false
  let buf = ''
  let m
  while ((m = tagRe.exec(text)) !== null) {
    if (m[4] !== undefined) {
      if (inTitle) buf += m[4]
      continue
    }
    const closing = m[1] === '/'
    const tag = (m[2] || '').toLowerCase()
    if (tag === 'title') {
      if (closing) {
        if (cur) cur.text = cleanText(buf)
        buf = ''
        inTitle = false
      } else {
        inTitle = true
        buf = ''
      }
    } else if (tag === 'topic') {
      if (closing) {
        const done = stack.pop()
        cur = stack[stack.length - 1] || null
        // 出栈的根（无父）就是一棵树的根
        if (done && !cur) roots.push(done)
      } else {
        const node = { text: '', children: [] }
        if (cur) cur.children.push(node)
        stack.push(node)
        cur = node
      }
    } else if (tag === 'sheet' && closing) {
      // 画布结束（多画布场景下 topic 已各自成根）
    }
  }
  if (!roots.length) throw new Error('Xmind 的 content.xml 里没有解析出任何主题')
  return mergeRoots(roots)
}

// ============================ Markdown ============================

/**
 * Markdown → 树。
 * 支持的层级信号（可混用，按行判定）：
 *   - ATX 标题： # ## ### …（层级 = 井号数）
 *   - 无序/有序列表： - * + 1. （层级由前导缩进决定，Tab=1 级，2 空格=1 级）
 *   - 引用/段落：视为上一层的子节点或根
 * 表格行、代码块围栏、分割线会被跳过。
 */
export function parseMarkdown(md) {
  const lines = String(md || '').replace(/\r\n?/g, '\n').split('\n')
  const flat = []   // { text, level }
  let inFence = false
  let codeFence = ''

  for (const raw of lines) {
    const line = raw.replace(/\t/g, '    ')

    // 代码块围栏：``` 或 ~~~ 之间的内容整体丢弃（导图里放代码没意义）
    const fence = /^\s*(`{3,}|~{3,})/.exec(line)
    if (fence) {
      if (!inFence) { inFence = true; codeFence = fence[1][0] }
      else if (fence[1][0] === codeFence) { inFence = false; codeFence = '' }
      continue
    }
    if (inFence) continue

    const t = line.trim()
    if (!t) continue
    if (/^(---+|\*\*\*+|___+|\|.*\|)$/.test(t)) continue   // 分割线 / 表格行

    // 1) ATX 标题
    const h = /^(#{1,6})\s+(.*)$/.exec(t)
    if (h) {
      flat.push({ text: stripInline(h[2]), level: h[1].length })
      continue
    }

    // 2) 列表项（无序 - * + / 有序 1. 1) ）
    const li = /^(\s*)(?:[-*+]|\d+[.)])\s+(.*)$/.exec(line)
    if (li) {
      const indent = li[1].length
      // 缩进换算层级：每 2 空格或 1 Tab 算一级；列表层级整体偏移，使其挂在最近的标题之下
      flat.push({ text: stripInline(li[2]), level: 100 + Math.floor(indent / 2) })
      continue
    }

    // 3) 普通段落：按缩进也算层级（幕布纯文本粘贴常是这种）
    const indent = /^(\s*)/.exec(line)[1].length
    if (indent >= 2) {
      flat.push({ text: stripInline(t), level: 100 + Math.floor(indent / 2) })
      continue
    }
    // 顶格普通段落
    flat.push({ text: stripInline(t), level: 0 })
  }

  if (!flat.length) throw new Error('没有从 Markdown 里解析出任何内容')

  return buildTreeFromLevels(flat)
}

/** 去掉行内 Markdown 标记，只留纯文本标签 */
function stripInline(s) {
  return String(s)
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')       // 图片 → alt
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')        // 链接 → 文字
    .replace(/(\*\*|__)(.*?)\1/g, '$2')             // 粗体
    .replace(/(\*|_)(.*?)\1/g, '$2')                // 斜体
    .replace(/~~(.*?)~~/g, '$1')                    // 删除线
    .replace(/`([^`]*)`/g, '$1')                    // 行内代码
    .replace(/\s+#+\s*$/, '')                       // 行尾多余的 #
    .trim()
}

/** 用「层级数组」重建树：遇到更深的层级就往下挂，更浅就回退到对应祖先 */
function buildTreeFromLevels(flat) {
  // 第 2/3 类（列表/缩进，level>=100）的基准层级由它前面最近的标题决定，
  // 这里做一次归一化：把 100+ 的层级压到「标题层级 +1」起算，避免标题与列表混用时层级跳空。
  let lastHeadingLevel = 0
  let listBase = null
  const norm = flat.map((it) => {
    if (it.level < 100) {
      lastHeadingLevel = it.level
      listBase = null
      return { text: it.text, level: it.level }
    }
    const rel = it.level - 100          // 列表自身缩进级别
    if (listBase === null) listBase = rel
    const lvl = lastHeadingLevel + 1 + (rel - listBase)
    return { text: it.text, level: lvl }
  })

  const root = { text: '', children: [] }
  const stack = [{ node: root, level: 0 }]
  for (const it of norm) {
    const node = { text: it.text, children: [] }
    while (stack.length > 1 && stack[stack.length - 1].level >= it.level) stack.pop()
    stack[stack.length - 1].node.children.push(node)
    stack.push({ node, level: it.level })
  }
  return consolidate(root)
}

// ============================ OPML ============================

/** OPML → 树（<outline text="…"> 嵌套） */
export function parseOpml(xml) {
  return parseOpmlStrict(String(xml || ''))
}

/** 用标签流正确维护嵌套层次的 OPML 解析 */
function parseOpmlStrict(text) {
  const roots = []
  const stack = []
  const re = /<(\/?)outline\b([^>]*?)(\/?)>/gi
  let m
  while ((m = re.exec(text)) !== null) {
    const closing = m[1] === '/'
    const selfClose = m[3] === '/'
    if (closing) {
      stack.pop()
      continue
    }
    const attrs = m[2]
    const label = pickAttr(attrs, 'text') ?? pickAttr(attrs, 'title') ?? pickAttr(attrs, '_note') ?? ''
    const node = { text: cleanText(label), children: [] }
    if (stack.length) stack[stack.length - 1].children.push(node)
    else roots.push(node)
    if (!selfClose) stack.push(node)
  }
  if (!roots.length) throw new Error('OPML 里没有解析出任何 outline 节点')
  return mergeRoots(roots)
}

function pickAttr(s, name) {
  const r = new RegExp('\\b' + name + '\\s*=\\s*"([^"]*)"', 'i').exec(s)
  return r ? decodeEntities(r[1]) : null
}

function decodeEntities(s) {
  return String(s)
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&apos;/g, "'")
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
}

// ============================ 纯文本缩进 ============================

/** 纯文本缩进（Tab / 空格）→ 树；没有缩进就当单行根 + 无子 */
export function parseIndentedText(txt) {
  const lines = String(txt || '').replace(/\r\n?/g, '\n').split('\n')
  const flat = []
  for (const line of lines) {
    const t = line.trim()
    if (!t) continue
    // Tab 先归一成 4 空格
    const indent = /^[\t ]*/.exec(line)[0].replace(/\t/g, '    ').length
    flat.push({ text: t, level: Math.floor(indent / 2) })
  }
  if (!flat.length) throw new Error('没有解析出任何内容')
  return buildTreeFromLevels(flat)
}

// ============================ 通用 ============================

/** 多根 → 单根：多于一个根时套一层虚拟根 */
function mergeRoots(roots) {
  const list = roots.filter(Boolean)
  if (list.length === 1) return list[0]
  return { text: '思维导图', children: list }
}

/** 收拢只有单个子节点且自己无文本的中间节点（Markdown 解析的虚拟根常见） */
function consolidate(root) {
  // 去掉解析时造的虚拟根（text 为空）——把它提升为唯一子节点
  if (!root.text && root.children.length === 1) return consolidate(root.children[0])
  if (!root.text && root.children.length > 1) root.text = '思维导图'
  root.children = root.children.map(consolidate)
  return root
}

function cleanText(s) {
  return String(s ?? '').replace(/\s+/g, ' ').trim()
}

/** 统计树规模（用于导入后的提示） */
export function countTree(node) {
  if (!node) return 0
  return 1 + (node.children || []).reduce((a, c) => a + countTree(c), 0)
}

/** 树最大深度（根为 1） */
export function treeDepth(node) {
  if (!node) return 0
  const kids = node.children || []
  return 1 + (kids.length ? Math.max(...kids.map(treeDepth)) : 0)
}

// ============================ 树形布局 ============================

/**
 * 把 { text, children } 树布局成导图坐标（左→右单侧展开）。
 *
 * 算法（经典 tidy-tree 的简化版，两遍）：
 *   ① 后序：叶子占一个「行槽」，父节点垂直位置 = 其子节点 y 的中点。
 *   ② 前序：按 depth 定 x（固定列宽），按子树宽度横向平移，避免兄弟子树重叠。
 * 相比原先 `y = order * 80` 的行式布局，这里保证：同层兄弟不重叠、父节点垂直居中于子节点群、
 * 子树整体成块。节点数很多时会按需加大列宽（长文本列宽自适应）。
 *
 * @returns { nodes, edges }  与 MindmapFlowBlock 的落盘格式一致
 */
export function layoutTree(root, opts = {}) {
  const COL_W = opts.colWidth ?? 240      // 列宽（层间距）
  const ROW_H = opts.rowHeight ?? 56      // 行槽高（同层节点间距）
  const ORIGIN_X = opts.originX ?? 60
  const ORIGIN_Y = opts.originY ?? 220

  const nodes = []
  const edges = []
  let order = 0
  let cursor = 0   // 叶子行槽游标

  // ① 后序：分配 y
  const assignY = (tree) => {
    const kids = tree.children || []
    if (!kids.length) {
      tree.__y = cursor
      cursor += 1
      return tree.__y
    }
    const ys = kids.map(assignY)
    tree.__y = (ys[0] + ys[ys.length - 1]) / 2
    return tree.__y
  }
  assignY(root)

  // ② 前序：落节点与边
  const place = (tree, depth, parentId) => {
    const id = 'n' + (++order)
    nodes.push({
      id,
      type: 'mindNode',
      position: {
        x: Math.round(ORIGIN_X + depth * COL_W),
        y: Math.round(ORIGIN_Y + tree.__y * ROW_H),
      },
      data: { label: tree.text ?? '' },
    })
    if (parentId) edges.push({ id: 'e' + id, source: parentId, target: id })
    ;(tree.children || []).forEach((c) => place(c, depth + 1, id))
  }
  place(root, 0, null)

  // 垂直居中：把整棵树的 y 归一化到 originY 附近，避免小树飘在很低的位置
  const minY = Math.min(...nodes.map((n) => n.position.y))
  if (Number.isFinite(minY)) {
    const shift = ORIGIN_Y - minY
    nodes.forEach((n) => { n.position.y = Math.round(n.position.y + shift) })
  }

  return { nodes, edges }
}

/** 树 → 导图数据（布局 + 序列化一步到位，供导图块导入时直接落盘） */
export function treeToFlow(tree, opts) {
  return layoutTree(tree, opts)
}

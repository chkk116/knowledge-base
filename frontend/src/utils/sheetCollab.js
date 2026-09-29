/**
 * 共享文档「电子表格块」的单元格级协同驱动。
 *
 * 背景：x-data-spreadsheet 的引擎数据是「整张工作簿」粒度（getData() 给全表），
 * 若直接把它当协同单位，就只能一次一人编辑（编辑锁）。本驱动把**单元格文本**抽出来
 * 放进 Y.Map，实现多人同时编辑不同单元格；样式 / 结构（合并、冻结、行列增删、格式）
 * 仍走整表快照（LWW），因为它们天然是整表语义、且很少并发操作。
 *
 * 数据模型：
 *   Y.Map<string, string>   key = "行:列"（0 基）   value = 单元格文本（含公式，如 "=A1*2"）
 *
 * 关键实测依据（见 outputs/sheet-*.png）：
 *   · engine.cellText(r, c, text) 可写单格，但**不会自动重绘**，须再调 engine.reRender()
 *   · reRender() 会**自动重算公式**（改 A1 后 B1=A1*2 随之更新）
 *   · 外部写入**不改变本地选区**，也不触发引擎的 change 事件（因此不会回环）
 */

const SEP = ':'

export function cellKey(r, c) {
  return r + SEP + c
}

export function parseCellKey(k) {
  const i = String(k).indexOf(SEP)
  if (i < 0) return null
  const r = Number(String(k).slice(0, i))
  const c = Number(String(k).slice(i + 1))
  return Number.isFinite(r) && Number.isFinite(c) ? [r, c] : null
}

/** getData() 可能返回数组（多工作表）；本块只用第一张 */
export function firstSheet(data) {
  return Array.isArray(data) ? (data[0] || {}) : (data || {})
}

/** 抽出所有**非空**单元格文本 → { "r:c": text } */
export function extractTexts(data) {
  const wb = firstSheet(data)
  const out = {}
  const rows = wb.rows || {}
  for (const rk of Object.keys(rows)) {
    const cells = (rows[rk] && rows[rk].cells) || {}
    for (const ck of Object.keys(cells)) {
      const t = cells[ck] && cells[ck].text
      if (t !== undefined && t !== null && String(t) !== '') {
        out[rk + SEP + ck] = String(t)
      }
    }
  }
  return out
}

/** 文本差异：新增/修改进 set，消失进 del */
export function diffTexts(prev, next) {
  const set = {}
  const del = []
  for (const k of Object.keys(next)) {
    if (next[k] !== prev[k]) set[k] = next[k]
  }
  for (const k of Object.keys(prev)) {
    if (!(k in next)) del.push(k)
  }
  return { set, del }
}

/** rows 里除 cells（文本）之外的属性（行高等结构信息） */
function rowsWithoutCells(rows) {
  const out = {}
  for (const rk of Object.keys(rows || {})) {
    const row = rows[rk] || {}
    const rest = {}
    for (const k of Object.keys(row)) {
      if (k !== 'cells') rest[k] = row[k]
    }
    if (Object.keys(rest).length) out[rk] = rest
  }
  return out
}

/**
 * 结构 / 样式签名：文本之外的一切（样式表、合并、列宽行高、冻结、校验、筛选…）。
 * 只用于判断「是否发生了非文本变更」，从而决定要不要把整表快照写回节点属性。
 */
export function structSignature(data) {
  const wb = firstSheet(data)
  return JSON.stringify({
    styles: wb.styles ?? null,
    merges: wb.merges ?? null,
    cols: wb.cols ?? null,
    rows: rowsWithoutCells(wb.rows),
    freeze: wb.freeze ?? null,
    validations: wb.validations ?? null,
    autofilter: wb.autofilter ?? null,
  })
}

/**
 * 把文本表叠加到工作簿数据上，返回新的工作簿对象（不修改入参）。
 * 用于「Y.Map 为准」地构造 loadData 的入参：老文档首次协同迁移时，
 * 若 Y.Map 已有内容则以 Y.Map 为准，否则用节点里的 data。
 */
export function mergeTexts(data, texts) {
  const wb = JSON.parse(JSON.stringify(firstSheet(data) || {}))
  if (!wb.rows) wb.rows = {}
  // 先把 texts 里出现过的行补出来
  for (const k of Object.keys(texts)) {
    const rc = parseCellKey(k)
    if (!rc) continue
    const [r, c] = rc
    if (!wb.rows[r]) wb.rows[r] = {}
    if (!wb.rows[r].cells) wb.rows[r].cells = {}
    wb.rows[r].cells[c] = { ...(wb.rows[r].cells[c] || {}), text: texts[k] }
  }
  return wb
}

/**
 * 创建协同驱动。
 *
 * @param {object}   opts
 * @param {object}   opts.ymap          Y.Map（key "r:c" → 文本）；无协同环境时传 null（纯本地）
 * @param {object}   opts.engine        引擎适配器 { cellText, reRender }
 * @param {function} opts.getData       取当前整表数据（引擎 getData 的包装）
 * @param {function} opts.onStructChange 结构/样式变更时的回调（参数为整表数据），由组件写回节点属性
 * @returns {{bootstrap, onLocalChange, destroy, isApplying}}
 */
export function createSheetCollab({ ymap, engine, getData, onStructChange }) {
  let lastTexts = {}
  let lastStruct = ''
  let applying = false
  let destroyed = false
  let observer = null

  /** 本地引擎变更 → 同步到 Y.Map（文本）/ 回调（结构样式） */
  function onLocalChange() {
    if (destroyed) return
    const full = getData()
    const texts = extractTexts(full)

    // 1) 文本：细粒度写 Y.Map
    if (ymap && !applying) {
      const { set, del } = diffTexts(lastTexts, texts)
      if (Object.keys(set).length || del.length) {
        ymap.doc?.transact(() => {
          for (const k of Object.keys(set)) ymap.set(k, set[k])
          for (const k of del) ymap.delete(k)
        })
      }
    }
    lastTexts = texts

    // 2) 结构 / 样式：整表快照回调（LWW）
    const sig = structSignature(full)
    if (lastStruct && sig !== lastStruct) {
      try { onStructChange?.(full) } catch { /* 忽略 */ }
    }
    lastStruct = sig
  }

  /** 远程 Y.Map 变更 → 应用到引擎（不打断本地选区；末尾统一重绘以重算公式） */
  function applyRemote() {
    if (destroyed || !ymap) return
    applying = true
    try {
      const next = {}
      ymap.forEach((v, k) => { next[k] = String(v) })
      const { set, del } = diffTexts(lastTexts, next)
      let touched = false
      for (const k of Object.keys(set)) {
        const rc = parseCellKey(k)
        if (!rc) continue
        engine.cellText(rc[0], rc[1], set[k])
        touched = true
      }
      for (const k of del) {
        const rc = parseCellKey(k)
        if (!rc) continue
        engine.cellText(rc[0], rc[1], '')
        touched = true
      }
      if (touched) {
        engine.reRender()      // 重绘 + 公式重算
        lastTexts = next
      }
    } finally {
      applying = false
    }
  }

  /**
   * 幂等地构造「供 loadData 使用的整表数据」：Y.Map 有内容则以它为准（协同真相），
   * 否则用节点里的本地数据。引擎因尺寸变化等原因重建时可反复调用。
   */
  function buildData(localData) {
    if (!ymap) return firstSheet(localData)
    const remoteTexts = {}
    ymap.forEach((v, k) => { remoteTexts[k] = String(v) })
    if (Object.keys(remoteTexts).length === 0) return firstSheet(localData)
    return mergeTexts(localData, remoteTexts)
  }

  /**
   * 初始化：把本地文本播种进 Y.Map（老文档首次进入协同 / 新建块），并开始监听远程变更。
   * @param {object} localData 节点属性里的整表数据
   * @returns {object} 供 loadData 使用的整表数据
   */
  function bootstrap(localData) {
    const localTexts = extractTexts(localData)
    if (!ymap) {
      // 纯本地（无协同环境）：直接用节点数据
      lastTexts = localTexts
      lastStruct = structSignature(localData)
      return firstSheet(localData)
    }

    const remoteTexts = {}
    ymap.forEach((v, k) => { remoteTexts[k] = String(v) })

    if (Object.keys(remoteTexts).length === 0 && Object.keys(localTexts).length) {
      // Y.Map 还是空的：用节点数据播种（老文档迁移 / 新建块）
      ymap.doc?.transact(() => {
        for (const k of Object.keys(localTexts)) ymap.set(k, localTexts[k])
      })
    }

    const merged = buildData(localData)
    lastTexts = extractTexts(merged)
    lastStruct = structSignature(localData)
    observer = () => applyRemote()
    ymap.observe(observer)
    return merged
  }

  function destroy() {
    destroyed = true
    if (ymap && observer) {
      try { ymap.unobserve(observer) } catch { /* 忽略 */ }
    }
    observer = null
  }

  return {
    bootstrap,
    buildData,
    onLocalChange,
    destroy,
    isApplying: () => applying,
    /** 仅供测试：读取内部快照 */
    _snapshot: () => ({ lastTexts, lastStruct }),
  }
}

/** 生成块的协同标识（首次挂载时写入节点属性） */
export function genSheetId() {
  return 'sh' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
}

/** 该块在 Y.Doc 中的 Y.Map 名 */
export function sheetMapName(sid) {
  return 'kb-sheet:' + sid
}

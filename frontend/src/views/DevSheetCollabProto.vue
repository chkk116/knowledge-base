<template>
  <div style="max-width: 980px; margin: 24px auto; font-family: sans-serif">
    <h3>电子表格协同驱动 · 原型验证</h3>
    <p style="color:#888;font-size:13px">
      验证 x-data-spreadsheet 是否具备「单元格级外部写入 + 公式重算 + 选区保持」的能力——
      这决定能否自研一个 Yjs 协同驱动（替代当前的整表 + 编辑锁）。
    </p>
    <div ref="el" class="proto-sheet"></div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import Spreadsheet from 'x-data-spreadsheet'
import 'x-data-spreadsheet/dist/xspreadsheet.css'

const el = ref(null)
let sheet = null
const changes = []

onMounted(() => {
  sheet = new Spreadsheet(el.value, {
    mode: 'edit',
    showToolbar: true,
    showGrid: true,
    showContextmenu: true,
    view: { height: () => 420, width: () => 940 },
    row: { len: 100, height: 25 },
    col: { len: 26, width: 100, indexWidth: 60, minWidth: 60 },
  })
  sheet.loadData({ rows: { 0: { cells: { 0: { text: '5' }, 1: { text: '=A1*2' } } } } })
  sheet.change((d) => { changes.push(JSON.stringify(d).length) })

  // 暴露给验收脚本
  window.__p = {
    get changes() { return changes },
    reset() { changes.length = 0 },
    getData: () => sheet.getData(),
    cellText: (r, c, t) => { sheet.cellText(r, c, t); return true },
    reRender: () => { sheet.reRender(); return true },
    /** 读取某格的「计算后显示值」与原始 text */
    cell: (r, c) => {
      const cd = sheet.cell(r, c)
      return cd ? { text: cd.text, value: cd.value, style: cd.style } : null
    },
    /** 当前选区 [ri, ci] */
    selection: () => {
      const s = sheet.sheet
      const sel = s && s.data && s.data.selector
      return sel ? [sel.ri, sel.ci] : null
    },
    /** 程序化选中某格 */
    select: (r, c) => {
      const s = sheet.sheet
      if (s && s.data && s.data.selector) { s.data.selector.set(r, c); return true }
      return false
    },
    /** 引擎内部：数据里所有非空单元格（用于 diff 可行性评估） */
    dumpCells: () => {
      const d = sheet.getData()
      const wb = Array.isArray(d) ? d[0] : d
      const out = []
      const rows = (wb && wb.rows) || {}
      Object.keys(rows).forEach((r) => {
        const cells = (rows[r] && rows[r].cells) || {}
        Object.keys(cells).forEach((c) => {
          out.push({ r: +r, c: +c, text: cells[c].text })
        })
      })
      return out
    },
  }
})
</script>

<style scoped>
.proto-sheet { height: 420px; border: 1px solid #ddd; }
</style>

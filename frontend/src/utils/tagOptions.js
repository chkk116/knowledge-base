/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

/**
 * 写作页标签下拉的数据合并。
 *
 * 两个来源：
 *  - 文档里实际用过的标签（`GET /docs/tags`，形如 `{ tag, count }`）——线上真正有内容的是这份；
 *  - 规范标签表（`GET /tags`，形如 `{ id, name, count }`）——管理员在「标签体系」里维护的，
 *    线上一直是空表，只读它会让下拉永远「无数据」（N38）。
 *
 * 合并口径：
 *  - 名称去首尾空白后去重，同日两次出现取计数较大者；
 *  - 规范标签的 id 优先（删除/管理要用真实 id），没有规范行时用 `doc-<name>` 作稳定 key；
 *  - 结果按使用频次降序，同频次按名称排序，保证列表顺序稳定。
 */
export function mergeTagOptions(docTags, canonTags) {
  const map = new Map()
  for (const t of docTags || []) {
    const name = String(t?.tag || '').trim()
    if (!name) continue
    map.set(name, { id: 'doc-' + name, name, count: Number(t.count) || 0 })
  }
  for (const t of canonTags || []) {
    const name = String(t?.name || '').trim()
    if (!name) continue
    const prev = map.get(name)
    map.set(name, {
      id: t.id != null ? t.id : 'doc-' + name,
      name,
      count: Math.max(prev ? prev.count : 0, Number(t.count) || 0),
    })
  }
  return [...map.values()].sort((a, b) => (b.count - a.count) || a.name.localeCompare(b.name, 'zh-Hans-CN'))
}

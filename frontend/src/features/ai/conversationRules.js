/** 会话列表操作的纯规则（无 Vue / DOM / 网络依赖）。 */

/** 切换单个会话的选中状态，返回新数组 */
export function toggleSelection(selected, id) {
  const out = [...selected]
  const i = out.indexOf(id)
  if (i >= 0) out.splice(i, 1)
  else out.push(id)
  return out
}

/** 已全选则清空，否则全选；返回新数组 */
export function toggleAllSelection(selected, allIds) {
  return selected.length === allIds.length ? [] : [...allIds]
}

/** 删掉已删除的会话，id 类型可能是数字或字符串，统一按字符串比较 */
export function removeConversations(list, ids) {
  const gone = new Set(ids.map(String))
  return list.filter((c) => !gone.has(String(c.id)))
}

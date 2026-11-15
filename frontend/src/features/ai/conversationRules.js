/** 会话列表操作的纯规则（无 Vue / DOM / 网络依赖）。 */

/** 切换单个会话的选中状态，返回新数组；id 统一按字符串比较，避免数字/字符串混用时切不掉 */
export function toggleSelection(selected, id) {
  const key = String(id)
  const out = [...selected]
  const i = out.findIndex((x) => String(x) === key)
  if (i >= 0) out.splice(i, 1)
  else out.push(id)
  return out
}

/** 真正全选才清空（长度相等但混有陈旧 id 时不应误判），否则全选；返回新数组 */
export function toggleAllSelection(selected, allIds) {
  const sel = new Set(selected.map(String))
  const allSelected = allIds.length > 0 && allIds.every((id) => sel.has(String(id)))
  return allSelected ? [] : [...allIds]
}

/** 删掉已删除的会话，id 类型可能是数字或字符串，统一按字符串比较 */
export function removeConversations(list, ids) {
  const gone = new Set(ids.map(String))
  return list.filter((c) => !gone.has(String(c.id)))
}

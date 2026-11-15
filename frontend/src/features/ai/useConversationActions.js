import { ref, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteConversation, batchDeleteConversations, updateConversation } from '../../api/ai'
import { toggleSelection, toggleAllSelection, removeConversations } from './conversationRules'

/**
 * 会话列表的批量删除、重命名与自定义指令。
 * 从 AiChat.vue 抽离：只依赖会话列表、消息缓存、当前会话 id 与账号隔离的存储键，
 * 不与发送流程交织。传入的四个引用由调用方持有，模板变量名保持不变。
 */
export function useConversationActions({ conversations, convMessages, currentId, convStorageKey }) {
  const batchMode = ref(false)
  const selectedIds = ref([])
  const renamingId = ref(null)
  const renameTitle = ref('')
  const showInstructionsDrawer = ref(false)
  const instructionsForm = ref({ id: null, title: '', instructions: '' })
  const savingInstructions = ref(false)

  async function removeConversation(id, e) {
    e?.stopPropagation()
    try {
      await ElMessageBox.confirm('确定删除这条会话及其历史消息吗？', '删除会话', { type: 'warning' })
      await deleteConversation(id)
      conversations.value = removeConversations(conversations.value, [id])
      delete convMessages.value[id]
      if (currentId.value === id) { currentId.value = null; localStorage.removeItem(convStorageKey()) }
      ElMessage.success('已删除')
    } catch (e) {
      if (e !== 'cancel') ElMessage.error('删除失败')
    }
  }

  function toggleBatchMode() {
    batchMode.value = !batchMode.value
    selectedIds.value = []
  }

  function isSelected(id) { return selectedIds.value.some((x) => String(x) === String(id)) }

  function toggleSelect(id) { selectedIds.value = toggleSelection(selectedIds.value, id) }

  function selectAllConvs() {
    selectedIds.value = toggleAllSelection(selectedIds.value, conversations.value.map((c) => c.id))
  }

  async function removeSelectedConvs() {
    const ids = [...selectedIds.value]
    if (!ids.length) return
    try {
      await ElMessageBox.confirm(
        `确定删除选中的 ${ids.length} 个会话？删除后聊天记录无法恢复。`,
        '批量删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
      )
    } catch { return }
    try {
      await batchDeleteConversations(ids)
      conversations.value = removeConversations(conversations.value, ids)
      ids.forEach((id) => { delete convMessages.value[id] })
      // 当前正在看的会话被删掉了 → 切到剩下的第一条，别停在空白页
      if (ids.some((id) => String(id) === String(currentId.value))) {
        currentId.value = conversations.value[0]?.id ?? null
        if (currentId.value == null) localStorage.removeItem(convStorageKey())
      }
      ElMessage.success(`已删除 ${ids.length} 个会话`)
      batchMode.value = false
      selectedIds.value = []
    } catch (e) {
      ElMessage.error('批量删除失败：' + ((e && e.message) || e))
    }
  }

  function startRename(c, e) {
    e?.stopPropagation()
    renamingId.value = c.id
    renameTitle.value = c.title
    nextTick(() => { const el = document.querySelector('.conv-rename-input input'); el?.focus(); el?.select() })
  }

  async function submitRename(c) {
    const title = renameTitle.value.trim()
    if (!title || title === c.title) { renamingId.value = null; return }
    try { await updateConversation(c.id, { title }); c.title = title; ElMessage.success('已重命名') }
    catch { ElMessage.error('重命名失败') } finally { renamingId.value = null }
  }

  function openInstructions(c) {
    instructionsForm.value = { id: c.id, title: c.title, instructions: c.instructions || '' }
    showInstructionsDrawer.value = true
  }

  async function submitInstructions() {
    const { id, title, instructions } = instructionsForm.value
    savingInstructions.value = true
    try {
      const { data } = await updateConversation(id, { title, instructions })
      const conv = conversations.value.find((c) => c.id === id)
      if (conv) { conv.title = data.title; conv.instructions = data.instructions }
      showInstructionsDrawer.value = false
      ElMessage.success('已保存')
    } catch { ElMessage.error('保存失败') } finally { savingInstructions.value = false }
  }

  return {
    batchMode, selectedIds, renamingId, renameTitle,
    showInstructionsDrawer, instructionsForm, savingInstructions,
    removeConversation, toggleBatchMode, isSelected, toggleSelect, selectAllConvs,
    removeSelectedConvs, startRename, submitRename, openInstructions, submitInstructions
  }
}

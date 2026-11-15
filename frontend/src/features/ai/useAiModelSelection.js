import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listAiModels } from '../../api/ai'
import { mkey } from '../../utils/aiPure'
import { THINK_LEVELS } from '../../utils/aiContext'
import {
  CTX_SET_KEY, THINK_KEY, DEFAULT_MAX_WINDOW,
  parseModelSelection, clampCtxWindow, ctxWindowLabelOf
} from './modelRules'

/**
 * AI 模型选择 + 上下文窗口 + 思考强度：从 AiChat.vue 抽出的独立状态块。
 * 模型由管理员在「AI 模型管理」页配置，这里只选不配；用量环与压缩仍留在组件里
 * （它们依赖会话消息），通过返回的 ctxWindow / modelPayload 衔接。
 */
export function useAiModelSelection() {
  const modelList = ref([])
  const selectedModelId = ref('')   // '' = 走后端默认模型

  function modelPayload() { return parseModelSelection(selectedModelId.value) }

  async function loadModels() {
    try {
      const { data } = await listAiModels()
      modelList.value = data || []
      // 下拉里选过、但管理员把该模型停用了 → 回到默认模型
      if (selectedModelId.value && !modelList.value.some((m) => mkey(m) === selectedModelId.value)) {
        selectedModelId.value = ''
      }
    } catch {
      modelList.value = []
    }
  }

  function onModelChange() {
    // 只影响后续提问：切换模型不会改写已有回答（历史回答保留当时的模型与耗时）
    ElMessage.info(selectedModelId.value ? '下一句将使用所选模型' : '下一句将使用默认模型')
  }

  const currentModel = computed(() =>
    modelList.value.find((m) => mkey(m) === selectedModelId.value) || null)
  const currentModelLabel = computed(() => currentModel.value?.name || '默认模型')
  const currentModelProvider = computed(() => currentModel.value?.provider || '')
  // 未指定时按后端默认模型估算窗口
  const effectiveModel = computed(() =>
    currentModel.value || modelList.value.find((m) => m.isDefault) || modelList.value[0] || null)
  const modelMaxWindow = computed(() => Number(effectiveModel.value?.contextWindow) || DEFAULT_MAX_WINDOW)

  /** 按模型 id 分别记忆窗口设定，换模型不会互相污染；0 = 跟随模型最大值 */
  const ctxWindowSet = ref(0)
  const ctxWindow = computed(() => clampCtxWindow(ctxWindowSet.value, modelMaxWindow.value))
  const ctxWindowLabel = computed(() => ctxWindowLabelOf(ctxWindow.value, modelMaxWindow.value))

  function loadCtxWindow() {
    try {
      const raw = localStorage.getItem(CTX_SET_KEY)
      const map = raw ? JSON.parse(raw) : {}
      const id = effectiveModel.value?.id || 'default'
      ctxWindowSet.value = Number(map[id]) || 0
    } catch { ctxWindowSet.value = 0 }
  }

  function saveCtxWindow() {
    try {
      const raw = localStorage.getItem(CTX_SET_KEY)
      const map = raw ? JSON.parse(raw) : {}
      const id = effectiveModel.value?.id || 'default'
      if (ctxWindowSet.value > 0) map[id] = ctxWindowSet.value
      else delete map[id]
      localStorage.setItem(CTX_SET_KEY, JSON.stringify(map))
    } catch { /* 存不了就算了，只影响下次进页面 */ }
  }

  watch(effectiveModel, () => loadCtxWindow())

  // 思考强度作用于提示词而非厂商私有参数，所以对所有模型都成立
  const think = ref(localStorage.getItem(THINK_KEY) || 'normal')
  watch(think, (v) => {
    localStorage.setItem(THINK_KEY, v)
    const t = THINK_LEVELS.find((x) => x.value === v)
    ElMessage.success('思考强度：' + (t ? t.label : v))
  })

  function pickModel(id) {
    if (selectedModelId.value === id) return
    selectedModelId.value = id
    onModelChange()
  }

  return {
    modelList, selectedModelId, modelPayload, loadModels, onModelChange,
    currentModel, currentModelLabel, currentModelProvider, effectiveModel, modelMaxWindow,
    ctxWindowSet, ctxWindow, ctxWindowLabel, loadCtxWindow, saveCtxWindow,
    think, pickModel
  }
}

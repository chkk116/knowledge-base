import { ref } from 'vue'
import api from '../api/request'

// 外观配置（SysSetting key=appearance）全站共享：登录后拉一次并缓存，
// Logo（App.vue 侧栏）与资料页背景（Profile/UserProfile）都从这里取，避免四处各自拉取/解析漂移。
export const appearanceLogo = ref('')
export const appearanceProfileBg = ref('')
let loaded = false

/** 安全解析 appearance JSON（坏数据返回空对象，不抛错） */
export function parseAppearance(value) {
  try { return JSON.parse(value || '') || {} } catch { return {} }
}

/** 拉取外观配置；force=true 强制刷新（外观设置页保存后调用） */
export async function loadAppearance(force = false) {
  if (loaded && !force) return
  try {
    const { data } = await api.get('/admin/settings/appearance')
    const cfg = parseAppearance(data?.value)
    appearanceLogo.value = cfg.logo || ''
    appearanceProfileBg.value = cfg.profileBg || ''
    loaded = true
  } catch { /* 无配置/无权限时保持内置默认 */ }
}

/** 登出时重置：回到内置默认 Logo/背景，与刷新后的匿名态一致 */
export function resetAppearance() {
  appearanceLogo.value = ''
  appearanceProfileBg.value = ''
  loaded = false
}

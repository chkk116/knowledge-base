/**
 * 应用内更新的下载状态（全局共享 + 刷新可恢复）
 *
 * 为什么单独抽一个模块：
 *  - 下载是在**原生层的后台线程**里跑的（ApkInstallerPlugin），WebView 切页/刷新**不会中断它**；
 *    但 JS 侧的进度监听会随页面销毁而丢失 —— 用户看到的就是「下载中的那条栏不见了」，
 *    不知道下载还在不在（2026-10-11 用户反馈）。
 *  - 所以把状态放在模块级 ref（跨路由共享）+ sessionStorage（刷新后恢复），
 *    App 壳里渲染一条常驻横幅，并且「消息」页也挂一条，随时能看到进度。
 *
 * ⚠️ 只存 sessionStorage（关掉 App 就清），不写 localStorage —— 避免长期残留假状态。
 */
import { ref } from 'vue'

const KEY = 'kb_update_dl'
const STALE_MS = 30 * 60 * 1000   // 超过 30 分钟视为过期状态

export const dlActive = ref(false)    // 正在下载
export const dlPercent = ref(-1)      // -1 = 未知（原生还没报第一个进度）
export const dlSpeed = ref(0)         // 字节/秒（0 = 未知），原生 progress 事件带出
export const dlVersion = ref('')      // 目标版本名
export const dlDone = ref(false)      // 刚下载完，等待系统安装界面

/** 速度格式化：≥1MB/s 用 MB/s，否则 KB/s */
export function fmtSpeed(bps) {
  const n = Number(bps) || 0
  if (n <= 0) return ''
  return n >= 1048576 ? (n / 1048576).toFixed(1) + ' MB/s' : Math.round(n / 1024) + ' KB/s'
}

function persist() {
  try {
    sessionStorage.setItem(KEY, JSON.stringify({
      active: dlActive.value,
      percent: dlPercent.value,
      speed: dlSpeed.value,
      version: dlVersion.value,
      done: dlDone.value,
      at: Date.now(),
    }))
  } catch { /* 隐私模式/配额满：不影响本次会话内的展示 */ }
}

/** 开始下载（由 downloadAndInstall 调用） */
export function beginUpdate(version = '') {
  dlActive.value = true
  dlDone.value = false
  dlPercent.value = -1
  dlSpeed.value = 0
  dlVersion.value = version || ''
  persist()
}

export function setUpdateProgress(p, speed) {
  dlPercent.value = typeof p === 'number' ? p : -1
  if (typeof speed === 'number' && speed > 0) dlSpeed.value = speed
  persist()
}

/** 下载完成（系统安装界面已拉起）——保留 60s 提示，方便用户看到结果 */
export function finishUpdate() {
  dlActive.value = false
  dlDone.value = true
  persist()
  setTimeout(() => { dlDone.value = false; persist() }, 60_000)
}

export function failUpdate() {
  dlActive.value = false
  dlDone.value = false
  persist()
}

/** 冷启动/刷新后恢复：原生下载仍在后台跑，横幅要继续显示 */
export function restoreUpdateState() {
  try {
    const o = JSON.parse(sessionStorage.getItem(KEY) || 'null')
    if (!o || typeof o !== 'object') return
    if (Date.now() - (o.at || 0) > STALE_MS) { sessionStorage.removeItem(KEY); return }
    dlActive.value = !!o.active
    dlDone.value = !!o.done
    dlPercent.value = typeof o.percent === 'number' ? o.percent : -1
    dlSpeed.value = typeof o.speed === 'number' ? o.speed : 0
    dlVersion.value = o.version || ''
  } catch { /* 坏数据忽略 */ }
}

/** 用户手动关掉横幅 */
export function dismissUpdateState() {
  dlActive.value = false
  dlDone.value = false
  try { sessionStorage.removeItem(KEY) } catch { /* ignore */ }
}

/** 文案：给横幅/消息页共用 */
export function updateProgressText() {
  if (dlActive.value) {
    const sp = fmtSpeed(dlSpeed.value)
    const base = dlPercent.value >= 0 ? `正在下载 ${dlPercent.value}%` : '正在下载…'
    return sp ? `${base} · ${sp}` : base
  }
  if (dlDone.value) return '下载完成，请在弹出的安装界面完成安装'
  return ''
}

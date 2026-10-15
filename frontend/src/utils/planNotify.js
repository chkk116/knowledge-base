/**
 * 学习计划到点系统通知（移动端）
 *
 * 设计：通知完全由「我的计划」推导而来，任何改动后统一重排（先全部取消、再按需排新的），
 * 因此不需要增量维护，也不会有「计划删了通知还在」的孤儿。
 *   - 通知 id = PLAN_NOTIFY_BASE + planId，稳定可取消
 *   - 已完成（全部条目勾选）的计划不排；没有提醒时间的计划不排；已过点的不排
 *   - 用户在设置里关掉开关后同样不排（同时清掉已排的通知）
 *
 * ⚠️ 本项目约定：前端不引入任何 @capacitor/* npm 依赖（见 MobileApp.vue 注释），
 * 原生能力一律通过 WebView 注入的 window.Capacitor.Plugins.* 调用，插件只在
 * mobile/node_modules 安装并手工登记原生侧。因此这里不能 import 包，必须走全局。
 */
import { isNative } from './runtimeBase'
import { listStudyPlans } from '../api/studyPlan'

const PLAN_NOTIFY_BASE = 1000
const ENABLE_KEY = 'kb_plan_notify_on'
const RESYNC_MIN_GAP_MS = 20_000   // 短时间内多次重排只执行最后一次，避免连续取消/排布抖动

let lastResyncAt = 0
let resyncTimer = null
let listenersBound = false

export function notifyEnabled() {
  try { return localStorage.getItem(ENABLE_KEY) !== '0' } catch { return true }
}
export function setNotifyEnabled(on) {
  try { on ? localStorage.removeItem(ENABLE_KEY) : localStorage.setItem(ENABLE_KEY, '0') } catch { /* ignore */ }
}

/** 原生容器里 Capacitor 桥会在页面加载后把插件挂到 window.Capacitor.Plugins 上 */
function getPlugin() {
  if (!isNative()) return null
  return window.Capacitor?.Plugins?.LocalNotifications || null
}

const notifyIdOf = (planId) => PLAN_NOTIFY_BASE + Number(planId || 0)
const planIdOf = (notifyId) => Number(notifyId) - PLAN_NOTIFY_BASE

/** 检查/申请通知权限。返回 granted / denied / unsupported */
export async function ensureNotifyPermission() {
  const ln = getPlugin()
  if (!ln) return 'unsupported'
  try {
    let r = await ln.checkPermissions()
    if (r.display !== 'granted') r = await ln.requestPermissions()
    return r.display === 'granted' ? 'granted' : 'denied'
  } catch {
    return 'denied'
  }
}

/** 未完成项数量；全部勾选的计划不提醒 */
function pendingCount(plan) {
  const items = Array.isArray(plan.items) ? plan.items : []
  if (!items.length) return 0
  return items.filter((i) => !i.done).length
}

function buildNotifications(plans) {
  const out = []
  const now = Date.now()
  for (const p of plans) {
    if (!p.remindAt) continue
    const pending = pendingCount(p)
    if (!pending) continue
    const at = new Date(p.remindAt).getTime()
    if (Number.isNaN(at) || at < now) continue
    out.push({
      id: notifyIdOf(p.id),
      title: '该复习了',
      body: `${p.title} · 还有 ${pending} 项未完成`,
      schedule: { at: new Date(at), allowWhileIdle: true },
      sound: 'default',
      extra: { planId: Number(p.id) },
    })
  }
  return out
}

/**
 * 统一重排通知（防抖 + 幂等）。任何计划数据变化后调用一次即可。
 * @returns {Promise<{ok:boolean, reason?:string, scheduled?:number}>}
 */
export async function resyncPlanNotifications(force = false) {
  const ln = getPlugin()
  if (!ln) return { ok: false, reason: 'unsupported' }
  if (!notifyEnabled()) {
    await cancelAll()
    return { ok: false, reason: 'disabled' }
  }
  const now = Date.now()
  if (!force && now - lastResyncAt < RESYNC_MIN_GAP_MS) {
    return { ok: true, reason: 'throttled' }
  }
  lastResyncAt = now
  try {
    const { data } = await listStudyPlans()
    const list = Array.isArray(data) ? data : []
    await cancelAll()
    const pending = buildNotifications(list)
    if (pending.length) await ln.schedule({ notifications: pending })
    return { ok: true, scheduled: pending.length }
  } catch (e) {
    console.warn('[planNotify] 重排通知失败', e)
    return { ok: false, reason: e?.message || 'error' }
  }
}

export async function cancelAll() {
  const ln = getPlugin()
  if (!ln) return
  try {
    const listed = await ln.getPending()
    const ids = (listed?.notifications || []).map((n) => n.id).filter((id) => id >= PLAN_NOTIFY_BASE)
    if (ids.length) await ln.cancel({ notifications: ids.map((id) => ({ id })) })
  } catch (e) {
    console.warn('[planNotify] 取消通知失败', e)
  }
}

/** 防抖版重排：连续调用（列表刷新 + 勾选 + 刷新）只跑最后一次 */
export function resyncPlanNotificationsSoon() {
  if (resyncTimer) clearTimeout(resyncTimer)
  resyncTimer = setTimeout(() => { resyncTimer = null; resyncPlanNotifications(true) }, 300)
}

/**
 * 绑定通知点击 → 跳对应计划详情。幂等，重复调用不会重复绑定。
 */
export async function initPlanNotifications() {
  const ln = getPlugin()
  if (!ln) return
  if (!listenersBound) {
    listenersBound = true
    try {
      ln.addListener('localNotificationActionPerformed', async (event) => {
        const planId = event?.notification?.extra?.planId ?? planIdOf(event?.notification?.id)
        if (!planId) return
        try {
          const { default: router } = await import('../router/mobile')
          router.push(`/plans/${planId}`)
        } catch { /* ignore */ }
      })
    } catch { /* ignore */ }
  }
  resyncPlanNotificationsSoon()
}

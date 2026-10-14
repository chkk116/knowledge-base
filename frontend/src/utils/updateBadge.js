/**
 * 「关于」入口的更新红点
 *
 * 后台有新版本（versionCode 大于已装）时亮起点，让用户在设置页就能看到
 * 「有更新」而不必手动点「检查更新」。由 MobileApp 冷启动时探测写入。
 */

export const UPDATE_BADGE_EVENT = 'kb-update-available-changed'

export function hasUpdateBadge() {
  try { return localStorage.getItem('kb_update_available') === '1' } catch { return false }
}

export function setUpdateBadge(on) {
  try {
    if (on) localStorage.setItem('kb_update_available', '1')
    else localStorage.removeItem('kb_update_available')
  } catch { /* 隐私模式下拉取不了，忽略 */ }
  window.dispatchEvent(new Event(UPDATE_BADGE_EVENT))
}

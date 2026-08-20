import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as apiLogin, me as apiMe } from '../api/auth'

// 权限码 → 中文名（后台菜单、用户管理、头像下拉统一复用，避免各页面各自维护漂移）
export const PERM_LABELS = {
  USER_MANAGE: '用户管理',
  DOC_REVIEW: '文章审核',
  STATS_VIEW: '数据看板',
  BLACKLIST_MANAGE: '黑名单管理',
  TAXONOMY_MANAGE: '分类标签管理',
  ANNOUNCEMENT_MANAGE: '公告管理',
}

/** 将权限码转为中文名；未知码原样返回 */
export function permLabel(code) {
  return PERM_LABELS[code] || code
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('kb_token') || '')
  const user = ref(null)
  const loggedIn = ref(!!token.value)
  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  // 细粒度权限码数组（如 ['USER_MANAGE','DOC_REVIEW']）；ADMIN 角色隐式拥有全部
  const permissions = computed(() => {
    if (!user.value) return []
    if (user.value.role === 'ADMIN') return ['*']
    const p = user.value.permissions
    if (!p || typeof p !== 'string' || !p.trim()) return []
    return p.split(',').map(s => s.trim()).filter(Boolean)
  })

  /** 是否拥有某权限码（ADMIN 或包含该码或通配 * 即视为拥有） */
  function hasPerm(code) {
    return isAdmin.value || permissions.value.includes(code) || permissions.value.includes('*')
  }

  function applyToken(t) {
    token.value = t || ''
    if (t) localStorage.setItem('kb_token', t)
    else localStorage.removeItem('kb_token')
    loggedIn.value = !!t
  }

  function toUser(data) {
    return {
      id: data.id || 0,
      username: data.username,
      role: data.role,
      permissions: data.permissions || '',
      displayName: data.displayName,
      avatar: data.avatar || '',
      email: data.email || '',
      bio: data.bio || '',
      gender: data.gender || '',
      hideGender: !!data.hideGender,
      createdAt: data.createdAt || '',
      points: data.points || 0,
    }
  }

  async function login(username, password, captchaToken, captchaCode) {
    // 注意：后端 LoginRequest 字段名为 captcha（非前端变量名 captchaCode）
    const { data } = await apiLogin({ username, password, captchaToken, captcha: captchaCode })
    applyToken(data.token)
    user.value = toUser(data)
    // 标记「刚登录」，供首页置顶公告仅在此刻弹出（刷新/直达不弹）
    sessionStorage.setItem('kb_just_logged_in', '1')
    return data
  }

  async function fetchMe() {
    if (!token.value) return
    try {
      const { data } = await apiMe()
      user.value = toUser(data)
      loggedIn.value = true
    } catch (e) {
      applyToken('')
      user.value = null
    }
  }

  /** 更新个人资料（昵称 / 头像 / 邮箱 / 简介 / 性别 / 隐藏性别）后同步到本地状态 */
  function setProfile(data) {
    user.value = toUser(data)
  }

  function logout() {
    applyToken('')
    user.value = null
  }

  return { token, user, loggedIn, isAdmin, permissions, hasPerm, login, fetchMe, setProfile, logout }
})

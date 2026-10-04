import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as apiLogin, me as apiMe } from '../api/auth'

// 权限码 → 中文名（后台菜单、用户管理、头像下拉统一复用，避免各页面各自维护漂移）
export const PERM_LABELS = {
  USER_MANAGE: '用户管理',
  DOC_REVIEW: '文章审核',
  POST_MANAGE: '帖子管理',
  STATS_VIEW: '数据看板',
  BLACKLIST_MANAGE: '黑名单管理',
  TAXONOMY_MANAGE: '分类标签管理',
  ANNOUNCEMENT_MANAGE: '公告管理',
  COURSE_MANAGE: '培训管理',
  EXAM_MANAGE: '考试管理',
  SHARE_CREATE: '创建团队空间',
  SHARE_VIEW: '共享文档管理（查看）',
  SHARE_DELETE: '共享文档管理（删除空间）',
  SHARE_CAPACITY: '共享文档管理（容量分配）',
  CORE_MAINTAIN: '功能开关与站点设置',
  DOC_EXPORT: '文档导出',
  BANNED_WORDS_MANAGE: '违禁词管理',
  DOC_SHARE: '共享文档管理（旧权限，已拆分）',
  ASSET_MANAGE: '资产管理',
  AVATAR_REVIEW: '头像审核',
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

  /** 是否拥有某权限码（ADMIN 或包含该码或通配 * 即视为拥有；兼容旧 DOC_SHARE 码 = 拥有全部 SHARE_* 细分权限） */
  function hasPerm(code) {
    if (isAdmin.value) return true
    const perms = permissions.value
    if (perms.includes(code) || perms.includes('*')) return true
    if (code && code.startsWith('SHARE_') && perms.includes('DOC_SHARE')) return true
    return false
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
      // 头像审核状态：avatarPending=待审新头像 URL（null=无待审），avatarStatus=PENDING/APPROVED/REJECTED
      avatarPending: data.avatarPending || null,
      avatarStatus: data.avatarStatus || 'APPROVED',
      email: data.email || '',
      bio: data.bio || '',
      gender: data.gender || '',
      hideGender: !!data.hideGender,
      createdAt: data.createdAt || '',
      points: data.points || 0,
      departmentId: data.departmentId || null,
      departmentName: data.departmentName || '',
      aiPersona: data.aiPersona || '',
      displayBadge: data.displayBadge || '',
      // 佩戴的勋章 id 列表（最多 3 枚）；优先取后端 wornBadges 数组，旧字段 displayBadge 单值/逗号串兜底
      wornBadges: Array.isArray(data.wornBadges) ? data.wornBadges
        : (data.displayBadge ? String(data.displayBadge).split(',').map(s => s.trim()).filter(Boolean) : []),
    }
  }

  async function login(username, password, captchaToken, captchaCode) {
    // 注意：后端 LoginRequest 字段名为 captcha（非前端变量名 captchaCode）
    const { data } = await apiLogin({ username, password, captchaToken, captcha: captchaCode })
    // 业务失败（密码错/限流/验证码过期等）后端返回 200 + { ok:false, error }，不抛 HTTP 错误
    if (data && data.ok === false) {
      const err = new Error(data.error || '登录失败')
      err.biz = true
      throw err
    }
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

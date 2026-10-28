import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
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
  AI_MANAGE: 'AI 模型管理',
  AUDIT_VIEW: '操作审计',
  LOGIN_LOG_VIEW: '登录日志',
  ADMIN_VIEW: '后台只读浏览（审计/日志/附件等）',
  DEMO_MODE: '演示权限（只读，禁止改动数据）',
}

/** 将权限码转为中文名；未知码原样返回 */
export function permLabel(code) {
  return PERM_LABELS[code] || code
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('kb_token') || '')
  // 首屏先用本地缓存的用户信息渲染（离线冷启动时拿不到接口，否则会一片空白）
  const user = ref((() => {
    try {
      const raw = localStorage.getItem('kb_user_cache')
      return raw ? JSON.parse(raw) : null
    } catch { return null }
  })())
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
  /**
   * 是否持有权限码。支持 "A|B" 形式（任一命中即通过）—— 用于「新细分码 或 总码」的兼容，
   * 例如 'SITE_FEATURE_MANAGE|CORE_MAINTAIN'：勾了新的细分码或老的站点总码都能进。
   */
  function hasPerm(code) {
    if (isAdmin.value) return true
    const perms = permissions.value
    const codes = Array.isArray(code) ? code : String(code || '').split('|').filter(Boolean)
    if (codes.some((c) => perms.includes(c) || perms.includes('*'))) return true
    if (codes.some((c) => c.startsWith('SHARE_') && perms.includes('DOC_SHARE'))) return true
    return false
  }

  /**
   * 是否演示账号（携带 DEMO_MODE 只读约束标记）。
   * 演示账号：后台各页面照常可打开查看，但所有保存/删除/发布等写操作被服务端拒绝，
   * 前端据此把编辑入口降级为只读并把点击转成「演示账户不能…」提示。
   * 真实 ADMIN 即便带标记也不受影响（ADMIN 隐式全权优先）。
   */
  const isDemo = computed(() => {
    if (isAdmin.value) return false
    return permissions.value.includes('DEMO_MODE')
  })

  /** 统一的演示拦截提示；返回 true 表示已拦截（调用处直接 return） */
  function demoBlock(action) {
    const what = action ? `不能${action}` : '不能执行该操作'
    ElMessage.warning(`演示账户${what}，请使用正式账号`)
    return true
  }

  // 把演示状态同步到 window，供 main.js 的全局点击拦截（无法直接访问 pinia）判断
  watch(isDemo, (v) => { window.__kbIsDemo = () => !!v }, { immediate: true })

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

  /** 外部身份源（LDAP/OIDC）登录成功后，用同一套逻辑落地会话 */
  function applySession(data) {
    if (!data || !data.token) throw new Error('登录响应缺少令牌')
    applyToken(data.token)
    user.value = toUser(data)
    sessionStorage.setItem('kb_just_logged_in', '1')
    return data
  }

  async function fetchMe() {
    if (!token.value) {
      // 离线冷启动兜底：token 可能来自本地缓存，user 从缓存恢复
      restoreCachedUser()
      return
    }
    try {
      const { data } = await apiMe()
      user.value = toUser(data)
      loggedIn.value = true
      cacheUser(user.value)
    } catch (e) {
      // 关键区分：
      //  - 401/403：token 真的失效 → 清空登录态
      //  - 网络错误（离线/超时）：**不能**清 token，否则断网一刷新就被登出，
      //    离线写文档的能力也没了。此时回退到本地缓存的用户信息。
      const status = e && (e.status || (e.response && e.response.status))
      if (status === 401 || status === 403) {
        applyToken('')
        user.value = null
        clearCachedUser()
      } else {
        restoreCachedUser()
        if (!user.value) loggedIn.value = !!token.value
      }
    }
  }

  /** 缓存用户信息（供离线时恢复界面） */
  function cacheUser(u) {
    try { localStorage.setItem('kb_user_cache', JSON.stringify(u)) } catch { /* 忽略 */ }
  }
  function restoreCachedUser() {
    if (user.value) return
    try {
      const raw = localStorage.getItem('kb_user_cache')
      if (!raw) return
      user.value = JSON.parse(raw)
      // 只有确实持有 token 才算登录态：否则多标签页登出/残留缓存会渲染成假登录，所有请求 401
      loggedIn.value = !!token.value
    } catch { /* 忽略 */ }
  }
  function clearCachedUser() {
    try { localStorage.removeItem('kb_user_cache') } catch { /* 忽略 */ }
  }

  /** 更新个人资料（昵称 / 头像 / 邮箱 / 简介 / 性别 / 隐藏性别）后同步到本地状态 */
  function setProfile(data) {
    user.value = toUser(data)
  }

  function logout() {
    applyToken('')
    user.value = null
    clearCachedUser()
    // ⚠️ 登出不再清离线文档缓存（2026-10-14 修）：读写路径均已按 userId 严格隔离
    //   （cacheDoc 标记 userId + mine() 过滤），换号后读不到前任的缓存，清了纯属自伤——
    //   此前「版本更新过程中一次 401 → 登出 → 清缓存」会让用户下载的离线文档全部消失。
    //   待同步草稿（queue 表）本来就保留待下次联网同步。
  }

  return { token, user, loggedIn, isAdmin, isDemo, permissions, hasPerm, demoBlock, login, applySession, fetchMe, setProfile, logout }
})

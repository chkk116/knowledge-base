import api from './request'

export const login = (data) =>
  api.post('/auth/login', data)

export const me = () =>
  api.get('/auth/me')

export const captcha = () =>
  api.get('/auth/captcha')

export const captchaSlide = (token, x, trajectory) =>
  api.post('/auth/captcha/slide', { token, x, trajectory })

export const register = (data) =>
  api.post('/auth/register', data)

export const updateProfile = (data) =>
  api.put('/auth/profile', data)

export const ranking = () =>
  api.get('/points/ranking')

export const pointsLog = () =>
  api.get('/points/log')

// 积分明细（分页 + 类型筛选）：明细页「查看完整明细」使用
export const pointsLogPaged = (page = 0, size = 20, type = '') =>
  api.get('/points/log/paged', { params: { page, size, ...(type ? { type } : {}) } })

// ---- 企业身份源（LDAP/AD）----
export const ssoOptions = () => api.get('/auth/sso/options')
export const ssoLdapLogin = (username, password) => api.post('/auth/sso/ldap/login', { username, password })

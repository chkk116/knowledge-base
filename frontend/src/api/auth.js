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

// ---- 邮箱绑定（验证码验证所有权）----
export const emailStatus = () =>
  api.get('/account/email/status')
export const sendEmailCode = (email) =>
  api.post('/account/email/send-code', { email })
export const verifyEmailCode = (email, code) =>
  api.post('/account/email/verify', { email, code })
export const unbindEmailApi = () =>
  api.post('/account/email/unbind')

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
export const ssoExchange = (ticket) => api.post('/auth/sso/ticket/exchange', { ticket })

// ---- 个人数据导出（隐私政策「你的权利」）----
// 返回 JSON 附件（blob）；后端每用户每小时限 3 次
export const exportMyData = () => api.get('/me/export', { responseType: 'blob', timeout: 120000 })

// ---- 密码重置链接（公开，无需登录）----
export const resetPwdCheck = (token) => api.get('/auth/reset-password/check', { params: { token } })
export const resetPwd = (token, password) => api.post('/auth/reset-password', { token, password })

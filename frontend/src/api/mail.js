import api from './request'

/** 可绑定的服务商模板（含默认 IMAP/SMTP） */
export const mailProviders = () => api.get('/mail/providers')

export const listMailAccounts = () => api.get('/mail/accounts')

/** 授权码绑定：{ provider, email, code, imapHost?, imapPort?, smtpHost?, smtpPort? } */
export const bindMailByCode = (payload) => api.post('/mail/accounts/code', payload)

/** OAuth 绑定第一步：拿授权地址（GMAIL / OUTLOOK） */
export const startMailOauth = (provider) => api.get(`/mail/oauth/${provider}/start`)

export const retestMailAccount = (id) => api.post(`/mail/accounts/${id}/retest`)

export const removeMailAccount = (id) => api.delete(`/mail/accounts/${id}`)

export const syncMailAccount = (id) => api.post(`/mail/accounts/${id}/sync`)

export const listMailMessages = (params) => api.get('/mail/messages', { params })

export const getMailMessage = (id) => api.get(`/mail/messages/${id}`)

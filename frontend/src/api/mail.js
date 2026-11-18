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

/** 发信 / 回复 / 转发：{ accountId, to:[], cc:[], subject, body, html?, inReplyTo?, references? } */
export const sendMail = (payload) => api.post('/mail/send', payload)

export const markMailRead = (id, read = true) => api.post(`/mail/messages/${id}/read`, null, { params: { read } })

export const deleteMail = (id, syncImap = true) =>
  api.delete(`/mail/messages/${id}`, { params: { syncImap } })

/** 保存草稿（不投递）：{ id?, accountId, to, subject, body } */
export const saveMailDraft = (payload) => api.post('/mail/drafts', payload)

/** 收件人自动补全：历史发件人 */
export const listMailContacts = (q) => api.get('/mail/contacts', { params: { q: q || '' } })

/** 账户签名 */
export const saveMailSignature = (accountId, signature) =>
  api.put(`/mail/accounts/${accountId}/signature`, { signature })

/** 该账户在服务器上的 IMAP 文件夹 */
export const listMailFolders = (accountId) => api.get(`/mail/accounts/${accountId}/folders`)

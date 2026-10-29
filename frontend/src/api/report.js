import api from './request'

// 举报 / 反馈
export const submitReport = (payload) => api.post('/reports', payload)
export const myReports = () => api.get('/reports/mine')
export const adminReports = (status, page = 0, size = 20) =>
  api.get('/admin/reports', { params: { ...(status ? { status } : {}), page, size } })
export const handleReport = (id, status, note, action = 'NONE') => api.put('/admin/reports/' + id, { status, note, action })

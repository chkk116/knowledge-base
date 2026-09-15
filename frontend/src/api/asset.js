import api from './request'

// 资产台账（需 ASSET_MANAGE 权限）
export const assetStats = () => api.get('/admin/assets/stats')
export const nextAssetCode = () => api.get('/admin/assets/next-code')
export const listAssets = (params = {}) => api.get('/admin/assets', { params })
// 单条详情：详情抽屉用它拿最新数据（列表行可能是保存前的旧对象）
export const getAsset = (id) => api.get('/admin/assets/' + id)
export const createAsset = (d) => api.post('/admin/assets', d)
export const updateAsset = (id, d) => api.put('/admin/assets/' + id, d)
export const deleteAsset = (id) => api.delete('/admin/assets/' + id)
export const assetBorrows = (id) => api.get('/admin/assets/' + id + '/borrows')
export const borrowAsset = (id, d) => api.post('/admin/assets/' + id + '/borrow', d)
export const returnAsset = (id, borrowId, d) => api.post('/admin/assets/' + id + '/borrow/' + borrowId + '/return', d)
export const assetLogs = (id) => api.get('/admin/assets/' + id + '/logs')
export const listAssetCategories = () => api.get('/admin/assets/categories')
export const createAssetCategory = (d) => api.post('/admin/assets/categories', d)
export const updateAssetCategory = (id, d) => api.put('/admin/assets/categories/' + id, d)
export const deleteAssetCategory = (id) => api.delete('/admin/assets/categories/' + id)

// Excel 批量导入 / 导出（导入可能较慢，单独放宽 timeout，避免被全局 15s 掐断）
export const exportAssets = (params = {}) => api.get('/admin/assets/export', { params, responseType: 'blob' })
// 批量删除：尽力删除，返回 { ok, deleted, skipped }
export const batchDeleteAssets = (ids) => api.post('/admin/assets/batch-delete', ids)
export const downloadAssetTemplate = () => api.get('/admin/assets/template', { responseType: 'blob' })
export const importAssets = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return api.post('/admin/assets/import', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
  })
}

/** 触发浏览器下载 blob 响应 */
export function saveBlob(data, filename) {
  const url = URL.createObjectURL(new Blob([data]))
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

// 扫码只读（需登录，脱敏）
export const scanAsset = (code) => api.get('/assets/' + encodeURIComponent(code))

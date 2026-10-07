import api from './request'

// 通用图片上传（需登录），返回 { errno, data: { url } }
// url 形如 /uploads/2026/08/14/xxxx.jpg，前端直接作为 <img src> 使用
// ⚠️ 这条走编辑器内联上传，**不会**登记进「我的文件」
export const uploadFile = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return api.post('/files', fd)
}

// ---- 「我的文件」文件中心：上传即登记归属，可列表 / 复制 / 删除 ----
// 与上面的 uploadFile 区分开：编辑器正文里的每张配图不该灌进文件中心，
// 也不该因为用户在文件中心删一下就让文档里的图变裂图（后端删除时会做引用检查）。
export const listMyFiles = () => api.get('/files/mine')

/**
 * 上传到文件中心（带进度回调）
 * @param {File} file
 * @param {(percent:number)=>void} [onProgress] 0~100
 */
export const uploadManagedFile = (file, onProgress) => {
  const fd = new FormData()
  fd.append('file', file)
  return api.post('/files/mine', fd, {
    // 大文件 + 移动端弱网，默认 15s 会掐断
    timeout: 120000,
    onUploadProgress: (e) => {
      if (!onProgress || !e.total) return
      onProgress(Math.round((e.loaded * 100) / e.total))
    }
  })
}

export const deleteMyFile = (id) => api.delete('/files/mine/' + id)

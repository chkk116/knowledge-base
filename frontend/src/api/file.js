import api from './request'

// 通用图片上传（需登录），返回 { errno, data: { url } }
// url 形如 /uploads/2026/08/14/xxxx.jpg，前端直接作为 <img src> 使用
export const uploadFile = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return api.post('/files', fd)
}

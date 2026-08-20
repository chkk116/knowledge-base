import axios from 'axios'
import { ElMessage } from 'element-plus'

// 共享 axios 实例：统一 baseURL + 自动附带令牌 + 401 处理
const api = axios.create({ baseURL: '/api', timeout: 15000 })

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('kb_token')
  if (token) config.headers.Authorization = 'Bearer ' + token
  return config
})

api.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response && err.response.status === 401) {
      localStorage.removeItem('kb_token')
      window.dispatchEvent(new Event('kb-unauthorized'))
      // 跳转到登录页（动态导入避免与 router 形成循环依赖）
      import('../router').then((m) => m.default.push('/login')).catch(() => {})
      return Promise.reject(err)
    }
    // 服务器异常（5xx）或网络故障（无 status）：全局兜底提示，
    // 避免部分接口失败时静默无反馈。4xx 通常由各调用处自行处理，这里不重复弹。
    const status = err.response && err.response.status
    if (!status || status >= 500) {
      if (!err.__silent) ElMessage.error('服务器异常，请稍后重试')
    }
    return Promise.reject(err)
  }
)

export default api

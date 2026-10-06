import axios from 'axios'
import { ElMessage } from 'element-plus'
import { apiBase } from '../utils/runtimeBase'

// 共享 axios 实例：统一 baseURL + 自动附带令牌 + 401 处理
//
// baseURL 说明：
//   网页端（浏览器直接访问）→ 相对路径 '/api'，由 nginx 同源反代到后端，无跨域。
//   App 端（Capacitor 套壳）→ 页面由 WebView 从本地文件加载，相对路径 '/api'
//     会被 Capacitor 判定为「本地资源」而 404，必须走绝对地址。
//   切换逻辑收敛在 utils/runtimeBase.js，本文件不再硬编码。
const api = axios.create({ baseURL: apiBase(), timeout: 15000 })

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
      // 令牌失效同样要清文档缓存：这是最常见的「换号后仍读到前任缓存」路径（不经过 auth.logout）
      import('../utils/offlineStore').then((m) => m.clearDocCacheForLogout()).catch(() => {})
      window.dispatchEvent(new Event('kb-unauthorized'))
      // 跳转到登录页（动态导入避免与 router 形成循环依赖）
      import('../router').then((m) => m.default.push('/login')).catch(() => {})
      return Promise.reject(err)
    }
    // 演示账号写操作被拒（403 + demo:true）：给出统一、明确的「演示账户不能…」提示，
    // 而不是各页面零散的错误文案；标记 err.demo 供调用处识别并静默处理。
    const rdata = err.response && err.response.data
    if (err.response && err.response.status === 403 && rdata && rdata.demo) {
      err.demo = true
      if (!err.__silent && !err.config?.silent) ElMessage.warning(rdata.error || '演示账户不能执行该操作')
      return Promise.reject(err)
    }
    // 服务器异常（5xx）或网络故障（无 status）：全局兜底提示，
    // 避免部分接口失败时静默无反馈。4xx 通常由各调用处自行处理，这里不重复弹。
    const status = err.response && err.response.status
    const offline = typeof navigator !== 'undefined' && navigator.onLine === false
    if (!status && offline) {
      // 离线：不弹「服务器异常」——顶部已有离线提示条，页面会走本地缓存；
      // 否则每个请求都弹一次，离线时直接刷屏（2026-10-06 用户反馈）
      err.offline = true
      return Promise.reject(err)
    }
    if (!status || status >= 500) {
      if (!err.__silent && !err.config?.silent) ElMessage.error('服务器异常，请稍后重试')
    }
    return Promise.reject(err)
  }
)

export default api

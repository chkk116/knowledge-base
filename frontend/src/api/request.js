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

// 5xx 全局提示的节流时间戳（模块级、跨请求共享）：故障期只提示一次，不刷屏
let lastServerErrAt = 0

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
    // 网络故障（无 status）：连不上 / 超时 / DNS 失败 / 被 CORS 拦截 / 服务器重启中。
    // 这是弱网与离线时的常态，页面会走本地缓存或空态展示。
    // ⚠️ 不能只看 navigator.onLine —— 手机连着 WiFi 但无外网时 onLine 仍是 true，
    //    旧逻辑会走 5xx 分支、每个失败请求弹一次「服务器异常」→ 刷屏 + 误以为「App 打不开」。
    //    因此对「无响应」一律静默，仅打标记供页面识别（顶部离线条已有提示）。
    const status = err.response && err.response.status
    if (!status) {
      err.network = true
      return Promise.reject(err)
    }
    // 5xx：服务端错误（重启 / 故障）。节流提示：60 秒内只弹一次，避免故障期刷屏。
    if (status >= 500) {
      err.server = true
      const now = Date.now()
      if (!err.__silent && !err.config?.silent && now - lastServerErrAt > 60000) {
        lastServerErrAt = now
        ElMessage.error('服务暂时不可用，请稍后重试')
      }
    }
    return Promise.reject(err)
  }
)

export default api

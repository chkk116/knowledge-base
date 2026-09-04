import { defineStore } from 'pinia'
import api from '../api/request'

const LS_KEY = 'kb_watermark'

// 默认水印配置（管理员可调）
const DEFAULT_CONFIG = {
  enabled: false,            // 是否开启全局屏幕水印
  text: '内部资料 请勿外传',  // 自定义文本（如部门 / 密级）
  showUser: true,            // 是否叠加当前登录用户名（防泄密：标明「谁在查看」）
  showTime: true,            // 是否叠加当前时间
  showHost: false,           // 是否叠加当前电脑主机名（防泄密：标明「在哪台机器查看」）
  fontSize: 16,              // 字号
  color: 'rgba(140,140,140,0.20)', // 颜色（含透明度）—— 日间模式（浅底）使用
  darkColor: 'rgba(255,255,255,0.18)', // 暗色模式（深底）使用的颜色，默认浅色，保证暗色下可见
  rotate: -22,               // 旋转角度
  gapX: 180,                 // 横向间距
  gapY: 120,                 // 纵向间距
  zIndex: 9999,              // 层级
}

function defaults() {
  return JSON.parse(JSON.stringify(DEFAULT_CONFIG))
}
function loadLocal() {
  try { return JSON.parse(localStorage.getItem(LS_KEY) || 'null') } catch { return null }
}

export const useWatermarkStore = defineStore('watermark', {
  state: () => ({
    // 启动时优先用 localStorage 兜底，load() 会再用后端配置覆盖
    config: { ...defaults(), ...(loadLocal() || {}) },
    loaded: false,
    // 当前是否暗色模式：由 App.vue 在主题切换时同步，用于水印颜色按主题自适应
    dark: false,
    // 查看者（客户端）主机信息：ip + 反向 DNS 解析出的主机名，用于「显示主机名」水印
    clientInfo: { ip: '', host: '' },
  }),
  getters: {
    // 注意：canvas ctx.font 不支持 'inherit'，必须给真实字族，否则整条赋值被丢弃、字号改动无效、文字还会向下错位。
    // 同时强制 Number(fontSize) 并补 fontWeight/fontStyle，避免任何来源（后端/缓存）把 fontSize 带成字符串或字段缺失导致 ctx.font 非法。
    // 颜色按主题（s.dark）自适应：暗色底用 darkColor，浅色底用 color，确保两种模式下都可见。
    font: (s) => ({
      fontSize: Number(s.config.fontSize) || 16,
      color: s.dark ? (s.config.darkColor || 'rgba(255,255,255,0.18)') : s.config.color,
      fontFamily: '"Microsoft YaHei", "PingFang SC", "Hiragino Sans GB", sans-serif',
      fontWeight: 'normal',
      fontStyle: 'normal',
    }),
    gap: (s) => [s.config.gapX, s.config.gapY],
    rotate: (s) => s.config.rotate,
    zIndex: (s) => s.config.zIndex,
  },
  actions: {
    // 拉取服务端统一配置（管理员改一次、全站生效）；失败则保留本地值
    async load() {
      try {
        const { data } = await api.get('/admin/settings/watermark')
        if (data && data.value) {
          this.config = { ...defaults(), ...JSON.parse(data.value) }
        }
      } catch (e) {
        const l = loadLocal()
        if (l) this.config = { ...defaults(), ...l }
      }
      // 拉取查看者（客户端）主机信息，供「显示主机名」水印使用；失败则留空不影响其它水印。
      // 带上 X-Client-Host = 浏览器导航所用的主机名（如 http://jifang:5173 时为 "jifang"），
      // 后端据此在反向 DNS 解析不到时回退为「本机计算机名」。
      try {
        const { data } = await api.get('/system/client-info', {
          headers: { 'X-Client-Host': window.location.hostname },
        })
        if (data) this.clientInfo = { ip: data.ip || '', host: data.host || '' }
      } catch (e) { /* 忽略，主机名留空 */ }
      this.loaded = true
    },
    // 保存：本地即时生效 + 同步到后端（后端未编译时仅本地生效，不报错）
    async save() {
      localStorage.setItem(LS_KEY, JSON.stringify(this.config))
      try {
        await api.put('/admin/settings/watermark', { value: JSON.stringify(this.config) })
      } catch (e) { /* 后端未编译则仅本地生效 */ }
    },
    reset() {
      this.config = defaults()
      this.save()
    },
  },
})

/**
 * 构建防泄密水印内容：显示「谁在查看」+ 时间 + 自定义文本。
 * @param user  当前登录用户（auth.user）
 * @param cfg   水印配置
 * @param now   时间（默认 new Date()，便于预览传入固定值）
 */
export function buildContent(user, cfg, now = new Date(), clientInfo = null) {
  const lines = []
  if (cfg.showUser && user) lines.push(user.displayName || user.username || '用户')
  if (cfg.showTime) {
    const p = (n) => String(n).padStart(2, '0')
    lines.push(`${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())} ${p(now.getHours())}:${p(now.getMinutes())}`)
  }
  if (cfg.showHost && clientInfo && clientInfo.host) lines.push('主机: ' + clientInfo.host)
  if (cfg.text) lines.push(cfg.text)
  return lines.length ? lines : ['内部资料']
}

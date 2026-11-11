import { defineStore } from 'pinia'
import api from '../api/request'

/**
 * 客户端下载中心配置（页脚入口 + 下载页）。
 *
 * 数据源：GET /api/public/download-config（免登录），后端读 kb_sys_setting.download_center。
 * 默认**启用**：未配置时页脚显示「客户端下载」入口；管理员在后台关掉后入口与下载页一起隐藏。
 *
 * 字段：
 *   - enabled：总开关
 *   - app：是否展示 Android App
 *   - desktop：是否展示 Windows 桌面端
 *   - title / desc：下载页标题与说明
 */
const DEFAULTS = {
  enabled: true,
  app: true,
  desktop: true,
  title: '客户端下载',
  desc: '下载知识库客户端，随时随地使用'
}

const truthy = (v) => v === true || v === 'true'

export const useDownloadStore = defineStore('download', {
  state: () => ({ ...DEFAULTS, loaded: false }),

  getters: {
    /** 是否有可展示的入口：配置拉取完成后再判定，避免后台已关闭但首帧仍闪出入口 */
    visible: (s) => s.loaded && truthy(s.enabled) && (truthy(s.app) || truthy(s.desktop)),
    payload: (s) => JSON.stringify({
      enabled: s.enabled,
      app: s.app,
      desktop: s.desktop,
      title: s.title,
      desc: s.desc
    })
  },

  actions: {
    async load() {
      try {
        const { data } = await api.get('/public/download-config')
        if (data) {
          this.enabled = truthy(data.enabled)
          this.app = truthy(data.app)
          this.desktop = truthy(data.desktop)
          if (data.title) this.title = data.title
          if (data.desc) this.desc = data.desc
        }
      } catch (e) {
        // 拉取失败保持默认（启用），不阻塞页面
      } finally {
        this.loaded = true
      }
    },

    /** 保存（需 SITE_SETTINGS_MANAGE / CORE_MAINTAIN） */
    async save() {
      await api.put('/admin/settings/download_center', { value: this.payload })
      // 回读后端落库值，避免表单里的非布尔值（如 'false'）留在 state 造成开关状态漂移
      await this.load()
    }
  }
})

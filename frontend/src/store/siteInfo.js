import { defineStore } from 'pinia'
import api from '../api/request'

/**
 * 站点页脚信息（备案号 / 开源仓库 / 版权）
 *
 * 为什么做成可配置而不是写死在代码里：
 *   备案号与域名一一绑定，属于「某个部署环境」的信息，不该进仓库。
 *   默认**全部为空** → 本地开发环境页脚不渲染任何内容；
 *   线上由管理员在「页脚设置」里填一次，全站生效。
 *
 * 版本号不在此列：它由构建期从 package.json 注入（__APP_VERSION__），属代码产物。
 */

const DEFAULTS = {
  enabled: false,
  icpNo: '',
  policeNo: '',
  giteeUrl: '',
  copyright: ''
}

/** 工信部备案查询系统（官方要求备案号必须可点击跳转到这里） */
export const ICP_URL = 'https://beian.miit.gov.cn/'

/** 公安联网备案查询页：由备案号里的数字串拼出 */
export function policeUrl(no) {
  const code = String(no || '').replace(/\D/g, '')
  return code ? `https://beian.mps.gov.cn/#/query/webSearch?code=${code}` : 'https://beian.mps.gov.cn/'
}

export const useSiteInfoStore = defineStore('siteInfo', {
  state: () => ({ ...DEFAULTS, loaded: false }),

  getters: {
    /** 是否有内容可展示：关闭开关或全部为空时不渲染页脚 */
    visible: (s) => !!s.enabled && !!(s.icpNo || s.policeNo || s.giteeUrl || s.copyright),
    /** 提交给后端的 JSON（只含配置字段，不含 loaded） */
    payload: (s) => JSON.stringify({
      enabled: s.enabled,
      icpNo: s.icpNo,
      policeNo: s.policeNo,
      giteeUrl: s.giteeUrl,
      copyright: s.copyright
    })
  },

  actions: {
    /** 拉取公开配置（未登录也能调用，登录页页脚需要） */
    async load() {
      try {
        const { data } = await api.get('/public/site-info')
        if (data) {
          this.enabled = data.enabled === true || data.enabled === 'true'
          this.icpNo = data.icpNo || ''
          this.policeNo = data.policeNo || ''
          this.giteeUrl = data.giteeUrl || ''
          this.copyright = data.copyright || ''
        }
      } catch (e) {
        // 拉取失败保持默认（空），不阻塞页面
      } finally {
        this.loaded = true
      }
    },

    /** 保存到后端（需 CORE_MAINTAIN 权限） */
    async save() {
      await api.put('/admin/settings/site_info', { value: this.payload })
      this.loaded = true
    },

    /** 恢复为空（仅本地状态，需再 save 才落库） */
    reset() {
      Object.assign(this, { ...DEFAULTS })
    }
  }
})

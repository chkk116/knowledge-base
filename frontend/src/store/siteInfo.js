import { defineStore } from 'pinia'
import api from '../api/request'

/**
 * 站点页脚信息（备案号 + 自定义展示内容）
 *
 * 为什么做成可配置而不是写死在代码里：
 *   备案号与域名一一绑定，属于「某个部署环境」的信息，不该进仓库。
 *   默认**全部为空** → 本地开发环境页脚不渲染任何内容；
 *   线上由管理员在「页脚设置」里填一次，全站生效。
 *
 * 字段说明：
 *   - icpNo / policeNo 为**固定项**：合规要求备案号必须展示在页脚底部，
 *     且链接到工信部/公安查询系统，链接与图标由代码自动生成，不可自定义；
 *   - items 为**自定义展示内容**：管理员可自行增删，每项 = 展示文字 + 可选链接。
 *     开源仓库、版权、联系方式、友情链接等都走这里，不再为每种内容预留单独字段。
 *
 * 版本号不在此列：它由构建期从 package.json 注入（__APP_VERSION__），属代码产物。
 */

const DEFAULTS = {
  enabled: false,
  icpNo: '',
  policeNo: ''
}

/** 工信部备案查询系统（官方要求备案号必须可点击跳转到这里） */
export const ICP_URL = 'https://beian.miit.gov.cn/'

/** 公安联网备案查询页：由备案号里的数字串拼出 */
export function policeUrl(no) {
  const code = String(no || '').replace(/\D/g, '')
  return code ? `https://beian.mps.gov.cn/#/query/webSearch?code=${code}` : 'https://beian.mps.gov.cn/'
}

/** 规范化一条自定义内容；name 为空则丢弃 */
function normItem(it) {
  if (!it) return null
  const name = String(it.name ?? it.text ?? '').trim()
  if (!name) return null
  return { name, url: String(it.url ?? it.link ?? '').trim() }
}

/** 解析后端下发的 items（数组或 JSON 字符串），任何异常都退化成空数组，绝不阻塞页面 */
function parseItems(v) {
  if (Array.isArray(v)) return v.map(normItem).filter(Boolean)
  if (typeof v === 'string' && v.trim()) {
    try {
      const a = JSON.parse(v)
      return Array.isArray(a) ? a.map(normItem).filter(Boolean) : []
    } catch (e) {
      return []
    }
  }
  return []
}

export const useSiteInfoStore = defineStore('siteInfo', {
  state: () => ({ ...DEFAULTS, items: [], loaded: false }),

  getters: {
    /** 是否有内容可展示：关闭开关或全部为空时不渲染页脚 */
    visible: (s) => !!s.enabled && !!(s.icpNo || s.policeNo || s.items.length),
    /** 提交给后端的 JSON（只含配置字段，不含 loaded） */
    payload: (s) => JSON.stringify({
      enabled: s.enabled,
      icpNo: s.icpNo,
      policeNo: s.policeNo,
      items: s.items.map(normItem).filter(Boolean)
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
          let items = parseItems(data.items)
          // 旧版数据迁移：早期只有 giteeUrl / copyright 两个固定字段，
          // 首次读到时自动转成自定义条目，避免线上已填内容丢失。
          if (!items.length) {
            if (data.giteeUrl) items.push({ name: '开源仓库', url: data.giteeUrl })
            if (data.copyright) items.push({ name: data.copyright, url: '' })
          }
          this.items = items
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

    /** 新增一条自定义内容 */
    addItem(name = '', url = '') {
      this.items.push({ name, url })
    },

    /** 删除一条自定义内容 */
    removeItem(i) {
      if (i >= 0 && i < this.items.length) this.items.splice(i, 1)
    },

    /** 上移（i>0 时与上一项交换）；下移用 moveDown */
    moveUp(i) {
      if (i > 0 && i < this.items.length) {
        const t = this.items[i - 1]
        this.items[i - 1] = this.items[i]
        this.items[i] = t
      }
    },
    moveDown(i) {
      if (i >= 0 && i < this.items.length - 1) {
        const t = this.items[i + 1]
        this.items[i + 1] = this.items[i]
        this.items[i] = t
      }
    },

    /** 恢复为空（仅本地状态，需再 save 才落库） */
    reset() {
      Object.assign(this, { ...DEFAULTS, items: [] })
      // 必须连 loaded 一起清：否则重进设置页因「已加载」跳过拉取，看到空表单误保存会覆盖线上配置
      this.loaded = false
    }
  }
})

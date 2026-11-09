import { defineStore } from 'pinia'
import api from '../api/request'
import { GENERAL_PACK, normalizePack } from '../utils/scenarioPacks'

/**
 * 场景模板 store：登录后从后端拉一次 scenario_pack，全站术语随取随用。
 *
 * - 读接口 /admin/settings/{key} 对所有登录用户开放（后端约定：读开放、写要权限）；
 * - 未登录 / 接口失败 / 未配置 → 回落通用模板（GENERAL_PACK，terms 为空 = 默认文案）；
 * - 保存只由管理员在「站点设置 → 场景模板」操作（写接口需 CORE_MAINTAIN）。
 */
export const useScenarioStore = defineStore('scenario', {
  state: () => ({
    key: 'general',
    pack: GENERAL_PACK,
    loaded: false,
  }),
  getters: {
    terms: (s) => s.pack?.terms || {},
    quickPrompts: (s) => s.pack?.quickPrompts || [],
  },
  actions: {
    /** 术语映射：terms[key] 优先，否则回落 fallback（代码里的默认文案） */
    t(key, fallback) {
      return this.terms[key] || fallback
    },
    async load() {
      if (this.loaded) return
      this.loaded = true
      try {
        const { data } = await api.get('/admin/settings/scenario_pack')
        const raw = String(data?.value || '')
        if (raw) {
          const p = normalizePack(JSON.parse(raw))
          this.key = p.key
          this.pack = p
        }
      } catch {
        /* 未登录 / 无网络 / 未配置：保持通用模板，不打扰 */
      }
    },
    async save(pack) {
      const p = normalizePack(pack)
      await api.put('/admin/settings/scenario_pack', { value: JSON.stringify(p) })
      this.key = p.key
      this.pack = p
    },
  },
})

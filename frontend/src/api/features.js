import api from './request'

// 核心维护 · 功能开关
export const listFeatures = () => api.get('/admin/features')
// 批量开关状态（featureKey → enabled），路由守卫一次性拉取
export const featureMap = () => api.get('/admin/features/map')
// 编辑单个功能的完整维护状态（enabled + 维护公告 + 预计恢复时间 + 跳转后台页）
export const updateFeature = (key, payload) =>
  api.put('/admin/features/' + key, payload)

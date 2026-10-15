import api from './request'

// 学习计划（仅本人可见）：计划增删改查 + 条目增删改 + 待复习汇总
export const listStudyPlans = () => api.get('/study-plans')
export const studyPlanSummary = () => api.get('/study-plans/summary')
export const getStudyPlan = (id) => api.get('/study-plans/' + id)
export const createStudyPlan = (d) => api.post('/study-plans', d)
export const updateStudyPlan = (id, d) => api.put('/study-plans/' + id, d)
export const deleteStudyPlan = (id) => api.delete('/study-plans/' + id)

// 条目：type = DOC | COURSE | TEXT
export const addStudyPlanItem = (id, d) => api.post('/study-plans/' + id + '/items', d)
export const updateStudyPlanItem = (id, itemId, d) => api.put('/study-plans/' + id + '/items/' + itemId, d)
export const deleteStudyPlanItem = (id, itemId) => api.delete('/study-plans/' + id + '/items/' + itemId)

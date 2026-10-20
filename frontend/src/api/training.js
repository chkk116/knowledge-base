import api from './request'

// ===================== 培训（学员侧） =====================
export const listCourses = (category) =>
  api.get('/courses', { params: category ? { category } : {} })

export const courseCategories = () =>
  api.get('/courses/categories')

export const getCourse = (id) =>
  api.get('/courses/' + id)

export const courseProgress = (id) =>
  api.get('/courses/' + id + '/progress')

export const finishChapter = (courseId, chapterId) =>
  api.post('/courses/' + courseId + '/chapters/' + chapterId + '/finish')

export const myLearning = () =>
  api.get('/courses/mine')

export const myCertificates = () =>
  api.get('/courses/certificates')

export const courseCertificate = (id) =>
  api.get('/courses/' + id + '/certificate')

// ===================== 培训（管理侧） =====================
export const adminCourses = () =>
  api.get('/admin/courses')

export const adminCreateCourse = (c) =>
  api.post('/admin/courses', c)

export const adminUpdateCourse = (id, c) =>
  api.put('/admin/courses/' + id, c)

export const adminDeleteCourse = (id) =>
  api.delete('/admin/courses/' + id)

export const adminChapters = (courseId) =>
  api.get('/admin/courses/' + courseId + '/chapters')

export const adminCreateChapter = (ch) =>
  api.post('/admin/courses/chapters', ch)

export const adminUpdateChapter = (id, ch) =>
  api.put('/admin/courses/chapters/' + id, ch)

export const adminDeleteChapter = (id) =>
  api.delete('/admin/courses/chapters/' + id)

// ===================== 考试（学员侧） =====================
export const listPapers = () =>
  api.get('/exams')

export const getPaper = (id) =>
  api.get('/exams/' + id)

export const startExam = (id) =>
  api.post('/exams/' + id + '/start')

export const submitExam = (paperId, recordId, items) =>
  api.post('/exams/' + paperId + '/submit', { recordId, items })

export const myExams = () =>
  api.get('/exams/mine')

export const examRecord = (rid) =>
  api.get('/exams/records/' + rid)

// ===================== 考试（管理侧） =====================
export const adminQuestions = (category) =>
  api.get('/admin/exams/questions', { params: category ? { category } : {} })

export const adminCreateQuestion = (question, options) =>
  api.post('/admin/exams/questions', { question, options })

/** 单题详情（含选项）：列表接口不返回 options，编辑前必须先拉一次 */
export const adminGetQuestion = (id) =>
  api.get('/admin/exams/questions/' + id)

export const adminUpdateQuestion = (id, question, options) =>
  api.put('/admin/exams/questions/' + id, { question, options })

export const adminDeleteQuestion = (id) =>
  api.delete('/admin/exams/questions/' + id)

/**
 * AI 出题：只生成候选题目，不落库（保存走 adminBatchCreateQuestions）
 *
 * ⚠️ 必须单独放宽超时：全局是 15s，而一次生成要 20~60s（要取资料 + 调模型）。
 * 用默认超时会导致每次都 timeout，Agent 于是反复重试、反复弹确认框（2026-10-20 实际踩到）。
 */
export const adminAiGenerateQuestions = (payload) =>
  api.post('/admin/exams/ai/generate', payload, { timeout: 180000 })

/** 批量入库：后端一个事务，任一条不合法整批回滚（50 条也不慢，但仍给足余量） */
export const adminBatchCreateQuestions = (items) =>
  api.post('/admin/exams/questions/batch', items, { timeout: 60000 })

export const adminPapers = () =>
  api.get('/admin/exams/papers')

export const adminCreatePaper = (p) =>
  api.post('/admin/exams/papers', p)

export const adminUpdatePaper = (id, p) =>
  api.put('/admin/exams/papers/' + id, p)

export const adminDeletePaper = (id) =>
  api.delete('/admin/exams/papers/' + id)

export const adminAssemble = (paperId, items) =>
  api.post('/admin/exams/papers/' + paperId + '/assemble', { items })

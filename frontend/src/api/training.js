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

// 重学：进度归零（服务端会扣回该章已发积分，重新完成再发，净额不变）
export const resetChapter = (courseId, chapterId) =>
  api.post('/courses/' + courseId + '/chapters/' + chapterId + '/reset')

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

// ---- 练习模式（2026-10-21）：不落成绩、逐题判分 ----
/** 按试卷取练习题（无需 start，不含答案） */
export const practicePaperQuestions = (paperId) =>
  api.get('/exams/' + paperId + '/practice')

/** 题库随机抽题（可按分类，count 默认 10，后端上限 50） */
export const practiceRandomQuestions = (category, count) =>
  api.get('/exams/practice/random', { params: { category: category || undefined, count } })

/** 开启练习会话：返回 { sessionId, paper, questions }；判分必须带 sessionId（防答案查询） */
export const startPractice = (params) =>
  api.post('/exams/practice/session', params || {})

/** 逐题判分：返回 { correct, manual, correctAnswer:[{label,content}], analysis } */
export const practiceCheck = (sessionId, questionId, answer) =>
  api.post('/exams/practice/check', { sessionId, questionId, answer })

/** 练习统计：{ total, correct, rate, wrong } */
export const practiceStats = () =>
  api.get('/exams/practice/stats')

/** 错题本：最近一次答错的题（含答案与解析） */
export const practiceWrongList = () =>
  api.get('/exams/practice/wrong')

/** 错题本：标记已掌握 */
export const practiceWrongClear = (questionId) =>
  api.delete('/exams/practice/wrong/' + questionId)

/** 当前模拟卷（未配置时 paperId 为 null） */
export const getMockPaper = () =>
  api.get('/exams/mock')

/** 管理端：当前模拟卷 */
export const adminGetMockPaper = () =>
  api.get('/admin/exams/mock')

/** 管理端：把某份试卷设为 / 取消全站模拟卷（试卷自身的属性） */
export const adminSetPaperMock = (paperId, enabled) =>
  api.post('/admin/exams/papers/' + paperId + '/mock', { enabled })

// ===================== 考试（管理侧） =====================
export const adminQuestions = (category) =>
  api.get('/admin/exams/questions', { params: category ? { category } : {} })

// 题库批量导入：Excel（.xlsx/.xls），返回 { imported, skipped, failedCount, failed:[{row,reason}] }
export const importQuestions = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return api.post('/admin/exams/questions/import', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000
  })
}

// 下载导入模板（blob）
export const downloadQuestionsTemplate = () =>
  api.get('/admin/exams/questions/import-template', { responseType: 'blob' })

export const adminCreateQuestion = (question, options) =>
  api.post('/admin/exams/questions', { question, options })

/** 单题详情（含选项）：列表接口不返回 options，编辑前必须先拉一次 */
export const adminGetQuestion = (id) =>
  api.get('/admin/exams/questions/' + id)

export const adminUpdateQuestion = (id, question, options) =>
  api.put('/admin/exams/questions/' + id, { question, options })

export const adminDeleteQuestion = (id) =>
  api.delete('/admin/exams/questions/' + id)

/** 批量删除题目：{ ids: [..] }，后端逐条走级联清理（组卷关联 + 试卷总分重算） */
export const adminBatchDeleteQuestions = (ids) =>
  api.post('/admin/exams/questions/batch-delete', { ids })

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

/** 批量删除试卷：{ ids: [..] }，连带清理组卷关联与作答记录 */
export const adminBatchDeletePapers = (ids) =>
  api.post('/admin/exams/papers/batch-delete', { ids })

export const adminAssemble = (paperId, items) =>
  api.post('/admin/exams/papers/' + paperId + '/assemble', { items })

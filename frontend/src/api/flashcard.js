import api from './request'

/** 我的全部卡片（按到期时间升序） */
export const listFlashcards = () => api.get('/flashcards')

/** 概览：{ total, due, learned, new } */
export const flashcardSummary = () => api.get('/flashcards/summary')

/** 今天到期、该复习的卡片 */
export const dueFlashcards = () => api.get('/flashcards/due')

/** 新建：{ front, back, source?, shareId?, docId? } */
export const createFlashcard = (body) => api.post('/flashcards', body)

/** 修改（只传要改的字段） */
export const updateFlashcard = (id, body) => api.put('/flashcards/' + id, body)

export const removeFlashcard = (id) => api.delete('/flashcards/' + id)

/** 复习一次：grade = again | hard | good | easy */
export const reviewFlashcard = (id, grade) => api.post(`/flashcards/${id}/review`, { grade })

import test from 'node:test'
import assert from 'node:assert/strict'
import { FB_REASONS, resolveRatingClick } from '../src/features/ai/feedbackRules.js'

test('首次点赞提交赞，再次点击取消', () => {
  assert.deepEqual(resolveRatingClick({ qaId: 1, fbRating: 0 }, 1), { action: 'submit', rating: 1, reason: '' })
  assert.deepEqual(resolveRatingClick({ qaId: 1, fbRating: 1 }, 1), { action: 'submit', rating: 0, reason: '' })
})

test('点踩先展开原因面板，已踩时再点则取消', () => {
  assert.deepEqual(resolveRatingClick({ qaId: 2, fbRating: 0 }, -1), { action: 'toggle' })
  assert.deepEqual(resolveRatingClick({ qaId: 2, fbRating: -1 }, -1), { action: 'submit', rating: 0, reason: '' })
})

test('从赞切到踩时展开面板，没有 qaId 一律忽略', () => {
  assert.deepEqual(resolveRatingClick({ qaId: 3, fbRating: 1 }, -1), { action: 'toggle' })
  assert.equal(resolveRatingClick({}, 1), null)
  assert.equal(resolveRatingClick(null, 1), null)
})

test('踩的原因选项固定且非空', () => {
  assert.equal(FB_REASONS.length, 4)
  assert.ok(FB_REASONS.every((r) => typeof r === 'string' && r.length > 0))
})

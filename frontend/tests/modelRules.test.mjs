import test from 'node:test'
import assert from 'node:assert/strict'
import {
  CTX_SET_KEY, THINK_KEY, DEFAULT_MAX_WINDOW, MIN_CTX_WINDOW,
  parseModelSelection, clampCtxWindow, ctxWindowLabelOf
} from '../src/features/ai/modelRules.js'

test('模型 key：空值走默认，系统/自有模型加前缀区分', () => {
  assert.deepEqual(parseModelSelection(''), { modelId: null })
  assert.deepEqual(parseModelSelection(null), { modelId: null })
  assert.deepEqual(parseModelSelection('s:3'), { modelId: 3 })
  assert.deepEqual(parseModelSelection('u:7'), { modelId: null, userModelId: 7 })
  assert.deepEqual(parseModelSelection('12'), { modelId: 12 })
})

test('窗口设定只能调小且有下限，0 表示跟随模型上限', () => {
  assert.equal(clampCtxWindow(0, 128000), 128000)
  assert.equal(clampCtxWindow(64000, 128000), 64000)
  assert.equal(clampCtxWindow(1000, 128000), MIN_CTX_WINDOW)
  assert.equal(clampCtxWindow(200000, 128000), 128000, '不能超过模型上限')
  assert.equal(clampCtxWindow(0, 0), DEFAULT_MAX_WINDOW, '模型未配置窗口时用默认值')
  assert.equal(MIN_CTX_WINDOW, 8000)
})

test('窗口档位文案按阈值分档', () => {
  assert.equal(ctxWindowLabelOf(128000, 128000), '模型上限')
  assert.equal(ctxWindowLabelOf(500000, 1000000), '超长')
  assert.equal(ctxWindowLabelOf(128000, 1000000), '长')
  assert.equal(ctxWindowLabelOf(32000, 1000000), '标准')
  assert.equal(ctxWindowLabelOf(16000, 1000000), '精简')
})

test('本地存储键保持原值，避免旧用户设置失效', () => {
  assert.equal(CTX_SET_KEY, 'kb_ai_ctx_window')
  assert.equal(THINK_KEY, 'kb_ai_think')
})

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  MAX_ATTACHMENTS, ATT_TEXT_MAX, ATT_SNIPPET, DOC_SIZE_LIMIT, IMAGE_SIZE_LIMIT,
  attachmentSizeLimit, isPlainTextFile, truncateAttachmentText, makeAttachmentId
} from '../src/features/ai/attachmentRules.js'

const DOC_RE = /\.(pdf|docx?|pptx?|xlsx?)$/i

test('文档类附件 20MB，其余 8MB', () => {
  assert.equal(attachmentSizeLimit('手册.pdf', DOC_RE), DOC_SIZE_LIMIT)
  assert.equal(attachmentSizeLimit('照片.PNG', DOC_RE), IMAGE_SIZE_LIMIT)
  assert.equal(attachmentSizeLimit('', DOC_RE), IMAGE_SIZE_LIMIT)
  assert.equal(MAX_ATTACHMENTS, 4)
})

test('纯文本文件按扩展名或 MIME 识别', () => {
  assert.equal(isPlainTextFile('notes.md', ''), true)
  assert.equal(isPlainTextFile('a.CSV', 'application/octet-stream'), true)
  assert.equal(isPlainTextFile('blob', 'text/plain'), true)
  assert.equal(isPlainTextFile('scan.pdf', ''), false)
  assert.equal(isPlainTextFile('x.bin', 'application/octet-stream'), false)
})

test('长文本截断并标注，短文本原样', () => {
  assert.equal(truncateAttachmentText('短内容'), '短内容')
  const long = '字'.repeat(ATT_SNIPPET + 10)
  const out = truncateAttachmentText(long)
  assert.equal(out.slice(0, ATT_SNIPPET), '字'.repeat(ATT_SNIPPET))
  assert.match(out, /内容过长已截断/)
  assert.equal(truncateAttachmentText(null), '')
  assert.equal(ATT_TEXT_MAX, 200 * 1024)
})

test('附件 id 唯一且可带前缀', () => {
  const a = makeAttachmentId()
  const b = makeAttachmentId('p1')
  assert.notEqual(a, b)
  assert.match(a, /^\d+-[a-z0-9]+$/)
  assert.match(b, /^\d+-p1-[a-z0-9]+$/)
})

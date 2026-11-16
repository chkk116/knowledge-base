import test from 'node:test'
import assert from 'node:assert/strict'
import {
  safeName, xmlEscape, colLetter, crc32, zipStore, aoaToSheetXml
} from '../src/features/docShare/exportDoc.js'

test('safeName 清洗非法字符并限长', () => {
  // 连续的非法字符（?"<>|）会合并成一个下划线
  assert.equal(safeName('a/b\\c:d*e?"<>|'), 'a_b_c_d_e_')
  assert.equal(safeName(''), 'document')
  assert.equal(safeName('x'.repeat(100)), 'x'.repeat(80))
})

test('xmlEscape 转义五个实体', () => {
  assert.equal(xmlEscape('<a href="x">&\'</a>'), '&lt;a href=&quot;x&quot;&gt;&amp;&apos;&lt;/a&gt;')
})

test('列号转 Excel 列字母', () => {
  assert.equal(colLetter(1), 'A')
  assert.equal(colLetter(26), 'Z')
  assert.equal(colLetter(27), 'AA')
  assert.equal(colLetter(702), 'ZZ')
})

test('crc32 标准值', () => {
  const buf = new TextEncoder().encode('hello')
  assert.equal(crc32(buf), 0x3610a686)
})

test('zipStore 产出合法 store 结构（local + central + EOCD）', () => {
  const data = new TextEncoder().encode('hi')
  const zip = zipStore([
    { name: 'a.txt', data },
    { name: 'b.xml', data: new Uint8Array([1, 2, 3]) },
  ])
  const dv = new DataView(zip.buffer)
  assert.equal(dv.getUint32(0, true), 0x04034b50, '第一个 local header 签名')
  assert.equal(dv.getUint16(8, true), 0, 'store 无压缩')
  // EOCD 在末尾 22 字节
  const eocd = zip.length - 22
  assert.equal(dv.getUint32(eocd, true), 0x06054b50)
  assert.equal(dv.getUint16(eocd + 10, true), 2, '文件数')
  // 两个 central directory header
  let found = 0
  for (let i = 0; i < zip.length - 3; i++) {
    if (dv.getUint32(i, true) === 0x02014b50) found++
  }
  assert.equal(found, 2)
})

test('aoaToSheetXml 生成 inlineStr 单元格与列宽', () => {
  const xml = aoaToSheetXml([['标题'], ['a', 'b']])
  assert.ok(xml.includes('<sheetData>'))
  assert.ok(xml.includes('<c r="A1" t="inlineStr"'))
  assert.ok(xml.includes('<t xml:space="preserve">标题</t>'))
  assert.ok(xml.includes('<c r="B2"'))
  assert.ok(xml.includes('customWidth="1"'), '列宽配置')
})

test('表格行展开成多列，前后空行被裁掉', () => {
  const xml = aoaToSheetXml([[], ['r1c1', 'r1c2'], []])
  assert.ok(xml.includes('r1c1'))
  assert.ok(xml.includes('r1c2'))
  assert.ok(!xml.includes('A4'), '裁掉尾部空行后不应有第 4 行')
})

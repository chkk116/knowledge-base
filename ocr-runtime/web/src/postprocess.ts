// 数字后处理（与 python/ocr_runtime/postprocess.py 逐字符一致）。
// 仅在“明确应为数字”的正则片段内做保守字符校正，绝不触碰普通中英文文本。
const CONFUSABLE: Record<string, string> = {
  O: "0", o: "0", D: "0", Q: "0",
  l: "1", I: "1", i: "1", "|": "1",
  Z: "2", z: "2",
  S: "5", s: "5",
  B: "8", g: "8",
};

const PATTERNS: RegExp[] = [
  /1[3-9]\d{1,2}[-\s]?\d{4}[-\s]?\d{4}/g,
  /¥?\s?\d{1,3}(,\d{3})*(\.\d{1,2})?/g,
  /\d{4}[-/年.]\d{1,2}[-/月.]\d{1,2}/g,
  /(?<![A-Za-z])\d{6,}(?![A-Za-z])/g,
  /\d{3,4}-\d{3,4}-\d{3,4}/g,
];

function fixSegment(seg: string): string {
  let out = "";
  for (const ch of seg) out += CONFUSABLE[ch] ?? ch;
  return out;
}

export function fixNumbers(text: string): string {
  if (!text) return text;
  let out = text;
  for (const pat of PATTERNS) out = out.replace(pat, (m) => fixSegment(m));
  return out;
}

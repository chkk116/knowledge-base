/**
 * 离线题目解析：把「复制过来的题库文本」拆成结构化题目。
 *
 * 全部在本地正则完成，不发请求——断网也能解析、能预览，只有最后保存需要联网。
 *
 * 支持（尽量宽松，粘贴来源五花八门）：
 *   1. 题干              → 编号 `1.` / `1、` / `1)` / `（1）`，也支持无编号（按空行分块）
 *   A. 选项 / A、选项 / （A）选项 / A）选项
 *   答案：B / 正确答案:B / 参考答案：ABD
 *   解析：xxx / 分析：xxx / 解答：xxx
 *
 * 输出不合法/缺答案的题会带 warn，交由界面提示，不静默丢弃。
 */

const RE_QNUM = /^\s*(?:第\s*)?(\d{1,3})\s*[.、)）．]\s*/;
const RE_QNUM_PAREN = /^\s*[（(]\s*(\d{1,3})\s*[)）]\s*/;
const RE_OPT = /^\s*[（(]?([A-Ha-h])\s*[.、)）．]\s*(.+)$/;
const RE_ANS = /^\s*(?:正确)?(?:参考)?答\s*案\s*[:：]\s*(.+)$/;
const RE_ANA = /^\s*(?:解析|分析|解答|说明)\s*[:：]\s*([\s\S]*)$/;

const stripHtml = (s) => String(s || '').replace(/<[^>]+>/g, '').trim();

/** 从答案文本里抽选项字母：支持 "B"、"A、C"、"ABD"、"b" */
function parseAnswerLetters(raw) {
  const t = String(raw || '').toUpperCase();
  const letters = t.match(/[A-H]/g) || [];
  // 「对/错」「正确/错误」「是/否」→ 判断题，用 T/F 标记
  if (!letters.length) {
    if (/^(对|正确|是|√|✓)$/i.test(t.trim())) return { letters: ['A'], judge: true, judgeText: '正确' };
    if (/^(错|错误|否|×|✗|x)$/i.test(t.trim())) return { letters: ['B'], judge: true, judgeText: '错误' };
  }
  return { letters: [...new Set(letters)], judge: false };
}

export function parseQuestions(text) {
  const raw = String(text || '').replace(/\r\n?/g, '\n');
  if (!raw.trim()) return [];

  // 1) 切块：优先用题号；没有任何题号就按空行切
  let blocks = [];
  const lines = raw.split('\n');
  const hasNumbering = lines.some((l) => RE_QNUM.test(l) || RE_QNUM_PAREN.test(l));
  if (hasNumbering) {
    let cur = null;
    for (const line of lines) {
      const m = line.match(RE_QNUM) || line.match(RE_QNUM_PAREN);
      if (m) {
        if (cur && cur.length) blocks.push(cur);
        cur = [line.replace(m[0], '')];
      } else if (cur) {
        cur.push(line);
      }
    }
    if (cur && cur.length) blocks.push(cur);
  } else {
    blocks = raw.split(/\n{2,}/).map((b) => b.split('\n'));
  }

  const out = [];
  for (const block of blocks) {
    const content = [];
    const options = [];
    let answerRaw = '';
    let analysis = '';
    let inAnalysis = false;

    for (const line of block) {
      const t = line.trim();
      if (!t) continue;

      const mAns = t.match(RE_ANS);
      if (mAns) { answerRaw = mAns[1].trim(); inAnalysis = false; continue; }

      const mAna = t.match(RE_ANA);
      if (mAna) { analysis = mAna[1].trim(); inAnalysis = true; continue; }

      if (inAnalysis) { analysis += (analysis ? '\n' : '') + t; continue; }

      const mOpt = t.match(RE_OPT);
      if (mOpt && (options.length || content.length)) {
        options.push({ label: mOpt[1].toUpperCase(), content: stripHtml(mOpt[2]) });
        continue;
      }
      // 续行：选项已经开始时，非选项行并入最后一个选项
      if (options.length) {
        options[options.length - 1].content += ' ' + stripHtml(t);
        continue;
      }
      content.push(t);
    }

    const title = stripHtml(content.join(' '));
    if (!title) continue;

    const warn = [];
    const { letters, judge } = parseAnswerLetters(answerRaw);
    if (!options.length && !judge) warn.push('没有识别到选项');
    if (!answerRaw) warn.push('没有识别到答案');

    // 判断题：没有选项时补「正确/错误」
    if (!options.length && judge) {
      options.push({ label: 'A', content: '正确' }, { label: 'B', content: '错误' });
    }
    const opts = options.map((o) => ({ ...o, isCorrect: letters.includes(o.label) }));

    let type = 'SINGLE';
    if (judge && opts.length === 2 && opts[0].content === '正确') type = 'JUDGE';
    else if (letters.length > 1) type = 'MULTI';

    if (answerRaw && !letters.length) warn.push(`答案「${answerRaw}」无法识别`);
    if (letters.length && !opts.some((o) => o.isCorrect)) warn.push('答案字母不在选项里');

    out.push({
      question: { type, content: title, analysis: analysis || '', difficulty: 1 },
      options: opts,
      warn,
    });
  }
  return out;
}

/** 统计：多少题可用 / 多少条有警告 */
export function summarize(parsed) {
  const total = parsed.length;
  const bad = parsed.filter((p) => p.warn.length).length;
  return { total, bad, ok: total - bad };
}

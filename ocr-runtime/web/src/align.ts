// Web 端对齐测试：用与 Python 基准相同的加权字符准确率，在 27 张黄金样本上，
// 对比「Web 运行时(版面重排+数字后处理)」与「Python OcrEngine(运行时)」的结果。
// 运行：npm run test:align
import fs from "node:fs";
import path from "node:path";
import { createNodeOcrEngine, decodeNode } from "./node.js";
import { OcrEngine } from "./engine.js";

const DATA = path.resolve("../../outputs/ocr-bench/dataset");
const GT = path.join(DATA, "ground_truth.json");

interface GtItem { file: string; text: string; category: string; difficulty: string }

function norm(s: string): string { return s.split(/\s+/).join(""); }

function levenshtein(a: string, b: string): number {
  if (a === b) return 0;
  const la = a.length, lb = b.length;
  if (la === 0) return lb;
  if (lb === 0) return la;
  let prev = Array.from({ length: lb + 1 }, (_, i) => i);
  const ca = Array.from(a), cb = Array.from(b);
  for (let i = 1; i <= la; i++) {
    const cur = [i];
    for (let j = 1; j <= lb; j++) {
      const cost = ca[i - 1] === cb[j - 1] ? 0 : 1;
      cur.push(Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost));
    }
    prev = cur;
  }
  return prev[lb];
}

function charAcc(ref: string, pred: string): number {
  const r = norm(ref), p = norm(pred);
  if (!r) return 1.0;
  return Math.max(0.0, 1.0 - levenshtein(r, p) / Array.from(r).length);
}

// Python 运行时（RapidOCR + 版面重排 + 数字后处理）实测，来自 benchmark_results.md
const PYTHON_RUNTIME: Record<string, number> = {
  clean_line: 0.929, paragraph: 0.973, scanned: 0.984, two_column: 0.946, mixed: 0.746,
};
const PYTHON_RUNTIME_OVERALL = 0.918;
// Python 裸 RapidOCR 基线，来自 results.json
const PYTHON_BARE: Record<string, number> = {
  clean_line: 0.9286, paragraph: 0.9727, scanned: 0.984, two_column: 0.527, mixed: 0.7464,
};
const CAT_LABEL: Record<string, string> = {
  clean_line: "干净单行", paragraph: "多行段落", scanned: "扫描噪声", two_column: "双栏", mixed: "中英数混排",
};

async function main() {
  const gt: GtItem[] = JSON.parse(fs.readFileSync(GT, "utf-8"));
  console.log("加载 Web 运行时（onnxruntime-web wasm）…");
  const full = await createNodeOcrEngine(); // columnAware=true, postprocess=true
  const bare = await createNodeOcrEngine({ columnAware: false, postprocess: false });

  type Agg = { chars: number; accSum: number; times: number[] };
  const mk = (): Agg => ({ chars: 0, accSum: 0, times: [] });
  const perFull: Record<string, Agg> = {}, perBare: Record<string, Agg> = {};
  let fullChars = 0, fullAcc = 0, bareChars = 0, bareAcc = 0;
  const rows: { file: string; cat: string; full: number; bare: number; text: string }[] = [];

  // warm-up
  await full.recognize(path.join(DATA, gt[0].file));

  for (const item of gt) {
    const imgPath = path.join(DATA, item.file);
    const t0 = performance.now();
    const rf = await full.recognize(imgPath);
    const dtFull = performance.now() - t0;
    const t1 = performance.now();
    const rb = await bare.recognize(imgPath);
    const dtBare = performance.now() - t1;

    const aFull = charAcc(item.text, rf.text);
    const aBare = charAcc(item.text, rb.text);
    const c = Array.from(norm(item.text)).length;
    if (!perFull[item.category]) { perFull[item.category] = mk(); perBare[item.category] = mk(); }
    perFull[item.category].chars += c; perFull[item.category].accSum += aFull * c; perFull[item.category].times.push(dtFull);
    perBare[item.category].chars += c; perBare[item.category].accSum += aBare * c; perBare[item.category].times.push(dtBare);
    fullChars += c; fullAcc += aFull * c; bareChars += c; bareAcc += aBare * c;
    rows.push({ file: item.file, cat: item.category, full: aFull, bare: aBare, text: rf.text.replace(/\n/g, " ").slice(0, 40) });
    console.log(`  ${item.file.padEnd(14)} bare=${(aBare * 100).toFixed(1)}%  runtime=${(aFull * 100).toFixed(1)}%  "${rf.text.replace(/\n/g, " ").slice(0, 32)}"`);
  }

  const overallFull = fullAcc / fullChars;
  const overallBare = bareAcc / bareChars;
  console.log("\n=== 分类对比（字符准确率）===");
  console.log("类别        裸Web   Web运行时  Python运行时  差异(pt)");
  const cats = ["clean_line", "paragraph", "scanned", "two_column", "mixed"];
  const result: any = { overall_full: +overallFull.toFixed(4), overall_bare: +overallBare.toFixed(4), per_category: {} };
  for (const cat of cats) {
    const f = perFull[cat].accSum / perFull[cat].chars;
    const b = perBare[cat].accSum / perBare[cat].chars;
    const py = PYTHON_RUNTIME[cat];
    const diff = (f - py) * 100;
    result.per_category[cat] = { bare: +b.toFixed(4), runtime: +f.toFixed(4), python_runtime: py, python_bare: PYTHON_BARE[cat] };
    console.log(`${CAT_LABEL[cat].padEnd(10)} ${(b * 100).toFixed(1).padStart(5)}%  ${(f * 100).toFixed(1).padStart(8)}%  ${(py * 100).toFixed(1).padStart(10)}%  ${diff >= 0 ? "+" : ""}${diff.toFixed(1)}`);
  }
  console.log(`\n整体：裸Web ${(overallBare * 100).toFixed(1)}%  Web运行时 ${(overallFull * 100).toFixed(1)}%  Python运行时 ${(PYTHON_RUNTIME_OVERALL * 100).toFixed(1)}%  差异 ${((overallFull - PYTHON_RUNTIME_OVERALL) * 100).toFixed(1)}pt`);

  const outPath = path.resolve("alignment_results.json");
  fs.writeFileSync(outPath, JSON.stringify(result, null, 2));
  console.log(`结果已写入 ${outPath}`);
}

main().catch((e) => { console.error(e); process.exit(1); });

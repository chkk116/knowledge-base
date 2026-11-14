// 文本识别（PP-OCRv3 Rec）：前处理 + CTC 贪婪解码（与 python 端逐字符一致）。
import { Tensor } from "onnxruntime-web";
import type { OrtSession } from "./ort.js";
import type { BgrImage } from "./types.js";
import { resizeBgr } from "./image.js";

export interface RecConfig {
  recImageShape: [number, number, number]; // [3, 48, 320]
  recBatchNum: number;
}

/** 由模型元数据里的 character 字符串构造字典：['blank', ...chars, ' ']。 */
export function buildCharacterList(metaChars: string): string[] {
  const lines = metaChars.split("\n").map((s) => s.replace(/\r$/, ""));
  return ["blank", ...lines, " "];
}

/**
 * 单张图的识别前处理：按比例 resize 到 (resizedW,48)，归一化到 [-1,1]，布局 CHW 并右填充。
 * 关键：填充宽度是「动态 batch 宽度」 imgW = int(ih * maxWhRatio)，不是 rec_image_shape 里
 * 的固定 320。PaddleOCR 按 batch 内最宽图决定模型输入的时间步数；写死会截断长行文本。
 */
export function recPreprocess(
  img: BgrImage, maxWhRatio: number, shape: [number, number, number],
): { data: Float32Array; width: number } {
  const [c, ih] = shape;
  const { bgr, width: w, height: h } = img;
  const ratio = w / h;
  const imgW = Math.floor(ih * maxWhRatio); // 动态 batch 宽度
  let resizedW = Math.ceil(ih * ratio);
  if (resizedW > imgW) resizedW = imgW;
  if (resizedW < 1) resizedW = 1;
  const resized = resizeBgr(bgr, w, h, resizedW, ih);
  const out = new Float32Array(c * ih * imgW);
  for (let y = 0; y < ih; y++) {
    for (let x = 0; x < resizedW; x++) {
      for (let cc = 0; cc < c; cc++) {
        const v = resized[(y * resizedW + x) * 3 + cc] / 255;
        out[cc * ih * imgW + y * imgW + x] = v * 2 - 1;
      }
    }
  }
  return { data: out, width: imgW };
}

/** CTC 贪婪解码：去重复 + 跳过 blank(0)，字符索引映射到字典。 */
export function ctcDecode(
  preds: Float32Array, N: number, T: number, C: number, characterList: string[],
): { text: string; score: number }[] {
  const results: { text: string; score: number }[] = [];
  for (let n = 0; n < N; n++) {
    let text = "";
    let conf = 0;
    let cnt = 0;
    let prev = -1;
    for (let t = 0; t < T; t++) {
      const base = (n * T + t) * C;
      let maxI = 0;
      let maxV = -Infinity;
      for (let c = 0; c < C; c++) {
        const v = preds[base + c];
        if (v > maxV) { maxV = v; maxI = c; }
      }
      if (maxI === 0) { prev = 0; continue; } // blank
      if (maxI === prev) { prev = maxI; continue; }
      text += characterList[maxI] ?? "";
      conf += maxV;
      cnt++;
      prev = maxI;
    }
    results.push({ text, score: cnt > 0 ? conf / cnt : 0 });
  }
  return results;
}

/** 批量识别：按宽高比排序加速，分批送入会话。 */
export async function recognize(
  session: OrtSession, crops: BgrImage[], characterList: string[], cfg: RecConfig,
): Promise<{ text: string; score: number }[]> {
  if (crops.length === 0) return [];
  const [c, ih] = cfg.recImageShape;
  const widthList = crops.map((im) => im.width / im.height);
  const indices = widthList.map((_, i) => i).sort((a, b) => widthList[a] - widthList[b]);
  const num = crops.length;
  const recRes: [string, number][] = new Array(num).fill(null).map(() => ["", 0]);

  for (let beg = 0; beg < num; beg += cfg.recBatchNum) {
    const end = Math.min(num, beg + cfg.recBatchNum);
    let maxWh = 0;
    for (let i = beg; i < end; i++) {
      const r = widthList[indices[i]];
      if (r > maxWh) maxWh = r;
    }
    const batch: Float32Array[] = [];
    let batchW = 0;
    for (let i = beg; i < end; i++) {
      const r = recPreprocess(crops[indices[i]], maxWh, cfg.recImageShape);
      batch.push(r.data);
      batchW = r.width; // 同一 batch 内 maxWhRatio 相同 → 宽度一致
    }
    const N = batch.length;
    const tensorData = new Float32Array(N * c * ih * batchW);
    for (let i = 0; i < N; i++) tensorData.set(batch[i], i * c * ih * batchW);
    const feeds: Record<string, Tensor> = {};
    feeds[session.inputNames[0]] = new Tensor("float32", tensorData, [N, c, ih, batchW]);
    const out = await session.run(feeds);
    const outTensor = out[session.outputNames[0]];
    const preds = outTensor.data as Float32Array;
    const dims = outTensor.dims; // [N, T, C]
    const T = dims[1];
    const C = dims[2];
    const dec = ctcDecode(preds, N, T, C, characterList);
    for (let r = 0; r < dec.length; r++) recRes[indices[beg + r]] = [dec[r].text, dec[r].score];
  }
  return recRes.map(([text, score]) => ({ text, score }));
}

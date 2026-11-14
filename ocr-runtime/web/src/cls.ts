// 方向分类（PP-OCRv2 Cls）：前处理 + argmax；标签 '180' 且分数够高则旋转 180°。
import { Tensor } from "onnxruntime-web";
import type { OrtSession } from "./ort.js";
import type { BgrImage } from "./types.js";
import { resizeBgr } from "./image.js";

export interface ClsConfig {
  clsImageShape: [number, number, number]; // [3, 48, 192]
  clsBatchNum: number;
  clsThresh: number;
  labelList: string[]; // ['0', '180']
}

export function clsPreprocess(img: BgrImage, shape: [number, number, number]): Float32Array {
  const [c, ih, iw] = shape;
  const { bgr, width: w, height: h } = img;
  const ratio = w / h;
  let resizedW = Math.ceil(ih * ratio) > iw ? iw : Math.ceil(ih * ratio);
  if (resizedW < 1) resizedW = 1;
  const resized = resizeBgr(bgr, w, h, resizedW, ih);
  const out = new Float32Array(c * ih * iw);
  for (let y = 0; y < ih; y++) {
    for (let x = 0; x < resizedW; x++) {
      for (let cc = 0; cc < c; cc++) {
        const v = resized[(y * resizedW + x) * 3 + cc] / 255;
        out[cc * ih * iw + y * iw + x] = v * 2 - 1;
      }
    }
  }
  return out;
}

function rotate180Bgr(bgr: Uint8ClampedArray, w: number, h: number): Uint8ClampedArray {
  const out = new Uint8ClampedArray(w * h * 3);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      for (let c = 0; c < 3; c++) {
        out[(y * w + x) * 3 + c] = bgr[((h - 1 - y) * w + (w - 1 - x)) * 3 + c];
      }
    }
  }
  return out;
}

/** 批量分类：返回（可能旋转后的）裁剪图与标签。 */
export async function classify(
  session: OrtSession, crops: BgrImage[], cfg: ClsConfig,
): Promise<{ crops: BgrImage[]; labels: { label: string; score: number }[] }> {
  if (crops.length === 0) return { crops, labels: [] };
  const [c, ih, iw] = cfg.clsImageShape;
  const num = crops.length;
  const outCrops: BgrImage[] = new Array(num);
  const outLabels: { label: string; score: number }[] = new Array(num);
  for (let beg = 0; beg < num; beg += cfg.clsBatchNum) {
    const end = Math.min(num, beg + cfg.clsBatchNum);
    const batch: Float32Array[] = [];
    for (let i = beg; i < end; i++) batch.push(clsPreprocess(crops[i], cfg.clsImageShape));
    const N = batch.length;
    const tensorData = new Float32Array(N * c * ih * iw);
    for (let i = 0; i < N; i++) tensorData.set(batch[i], i * c * ih * iw);
    const feeds: Record<string, Tensor> = {};
    feeds[session.inputNames[0]] = new Tensor("float32", tensorData, [N, c, ih, iw]);
    const out = await session.run(feeds);
    const prob = out[session.outputNames[0]].data as Float32Array; // [N, 2]
    for (let i = 0; i < N; i++) {
      const p0 = prob[i * 2], p1 = prob[i * 2 + 1];
      const idx = p1 > p0 ? 1 : 0;
      const score = idx === 1 ? p1 : p0;
      const label = cfg.labelList[idx];
      let crop = crops[beg + i];
      if (label === "180" && score > cfg.clsThresh) {
        crop = { bgr: rotate180Bgr(crop.bgr, crop.width, crop.height), width: crop.width, height: crop.height };
      }
      outCrops[beg + i] = crop;
      outLabels[beg + i] = { label, score };
    }
  }
  return { crops: outCrops, labels: outLabels };
}

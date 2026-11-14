// 文本检测（PP-OCRv3 Det）：前处理 + DB 后处理。
// 后处理用纯 TS 复刻 RapidOCR(cV2)：x 轴覆盖图找竖向间隙（实际用连通分量+凸包最小外接矩形）。
import { Tensor } from "onnxruntime-web";
import type { OrtSession } from "./ort.js";
import type { RawImage } from "./types.js";
import { rawToBgr, resizeBgr, detResizeShape } from "./image.js";
import { connectedComponents, getMiniBoxes, orderPointsClockwise, pointInPoly, type Pt } from "./geometry.js";
import { unclip } from "./clipperUtil.js";

export interface DetConfig {
  limitSideLen: number;
  limitType: "min" | "max";
  thresh: number;
  boxThresh: number;
  maxCandidates: number;
  unclipRatio: number;
  useDilation: boolean;
  scoreMode: "fast" | "slow";
}

export const DEFAULT_DET: DetConfig = {
  limitSideLen: 736,
  limitType: "min",
  thresh: 0.3,
  boxThresh: 0.5,
  maxCandidates: 1000,
  unclipRatio: 1.6,
  useDilation: true,
  scoreMode: "fast",
};

/** 检测前处理：按 limit_side_len 缩放（取整到 32 倍数），归一化到 (x/255 - mean)/std，布局 CHW。 */
export function detPreprocess(raw: RawImage, cfg: DetConfig): {
  tensor: Float32Array; rh: number; rw: number; ratioH: number; ratioW: number; h: number; w: number;
} {
  const { bgr, width: w, height: h } = rawToBgr(raw);
  const { rh, rw, ratioH, ratioW } = detResizeShape(h, w, cfg.limitSideLen, cfg.limitType);
  const resized = resizeBgr(bgr, w, h, rw, rh);
  const mean = [0.485, 0.456, 0.406];
  const std = [0.229, 0.224, 0.225];
  const chw = new Float32Array(3 * rh * rw);
  for (let y = 0; y < rh; y++) {
    for (let x = 0; x < rw; x++) {
      for (let c = 0; c < 3; c++) {
        const v = resized[(y * rw + x) * 3 + c] / 255;
        chw[c * rh * rw + y * rw + x] = (v - mean[c]) / std[c];
      }
    }
  }
  return { tensor: chw, rh, rw, ratioH, ratioW, h, w };
}

/**
 * 2x2 结构元的膨胀，等价于 cv2.dilate(mask, np.ones((2,2)))。
 * cv2 对偶数核的锚点为 (1,1)：dst(x,y) = max src(x-1..x, y-1..y)，即看左上 2x2 窗口。
 * 早期实现看的是右下窗口，会让 mask 整体偏移 1px，进而影响检测框与多栏分栏判定。
 */
function dilate2x2(mask: Uint8Array, W: number, H: number): Uint8Array {
  const out = new Uint8Array(W * H);
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      let v = 0;
      for (let dy = -1; dy <= 0 && !v; dy++) {
        for (let dx = -1; dx <= 0; dx++) {
          const xx = x + dx, yy = y + dy;
          if (xx >= 0 && yy >= 0 && mask[yy * W + xx]) { v = 1; break; }
        }
      }
      out[y * W + x] = v;
    }
  }
  return out;
}

/** numpy.round 语义：.5 取最近偶数（banker's rounding），与 Python np.round 对齐。 */
function npRound(x: number): number {
  const f = Math.floor(x);
  const frac = x - f;
  if (frac < 0.5 - 1e-9) return f;
  if (frac > 0.5 + 1e-9) return f + 1;
  return f % 2 === 0 ? f : f + 1;
}

/** 多边形区域内 pred 均值（等价于 cv2.mean(pred, mask)）。 */
function boxScoreFast(pred: Float32Array, W: number, H: number, box: Pt[]): number {
  let xmin = Infinity, xmax = -Infinity, ymin = Infinity, ymax = -Infinity;
  for (const [x, y] of box) {
    if (x < xmin) xmin = x; if (x > xmax) xmax = x;
    if (y < ymin) ymin = y; if (y > ymax) ymax = y;
  }
  xmin = Math.max(0, Math.floor(xmin));
  xmax = Math.min(W - 1, Math.ceil(xmax));
  ymin = Math.max(0, Math.floor(ymin));
  ymax = Math.min(H - 1, Math.ceil(ymax));
  if (xmax < xmin || ymax < ymin) return 0;
  const poly = box.map((p) => [Math.round(p[0]) - xmin, Math.round(p[1]) - ymin] as Pt);
  let sum = 0, cnt = 0;
  for (let y = ymin; y <= ymax; y++) {
    for (let x = xmin; x <= xmax; x++) {
      if (pointInPoly(x - xmin, y - ymin, poly)) {
        sum += pred[y * W + x];
        cnt++;
      }
    }
  }
  return cnt ? sum / cnt : 0;
}

/** DB 后处理主流程：pred 为 [H,W] 概率图。返回原图坐标系下的文本框与分数。 */
export function detPostprocess(
  pred: Float32Array, W: number, H: number,
  destW: number, destH: number, cfg: DetConfig,
): { boxes: number[][][]; scores: number[] } {
  const segmentation = new Uint8Array(W * H);
  for (let i = 0; i < W * H; i++) segmentation[i] = pred[i] > cfg.thresh ? 1 : 0;
  const mask = cfg.useDilation ? dilate2x2(segmentation, W, H) : segmentation;

  const comps = connectedComponents(mask, W, H);
  const boxes: number[][][] = [];
  const scores: number[] = [];
  const limit = Math.min(comps.length, cfg.maxCandidates);
  for (let ci = 0; ci < limit; ci++) {
    const comp = comps[ci];
    const { box, sside } = getMiniBoxes(comp);
    if (sside < 3) continue;
    const score = boxScoreFast(pred, W, H, box);
    if (cfg.boxThresh > score) continue;
    const expanded = unclip(box, cfg.unclipRatio);
    const { box: box2, sside: sside2 } = getMiniBoxes(expanded);
    if (sside2 < 5) continue;
    const scaled = box2.map((p) => [
      Math.max(0, Math.min(destW, npRound((p[0] * destW) / W))),
      Math.max(0, Math.min(destH, npRound((p[1] * destH) / H))),
    ]);
    boxes.push(scaled);
    scores.push(score);
  }
  return { boxes, scores };
}

/** 运行检测：返回 text boxes（已按阅读顺序初排）与裁剪用的 BGR 图。 */
export async function detect(
  session: OrtSession, raw: RawImage, cfg: DetConfig,
): Promise<{ boxes: number[][][]; scores: number[] }> {
  const { tensor, rh, rw, h, w } = detPreprocess(raw, cfg);
  const feeds: Record<string, Tensor> = {};
  feeds[session.inputNames[0]] = new Tensor("float32", tensor, [1, 3, rh, rw]);
  const out = await session.run(feeds);
  const outTensor = out[session.outputNames[0]];
  const dims = outTensor.dims; // [1, 1, H, W]
  const H = dims[2], W = dims[3];
  const full = outTensor.data as Float32Array;
  const map = new Float32Array(H * W);
  for (let i = 0; i < H * W; i++) map[i] = full[i];
  return detPostprocess(map, W, H, w, h, cfg);
}

/** 复刻 RapidOCR.sorted_boxes：先按 (y0,x0) 排序，相邻 y 差 <10 且 x 逆序则交换。 */
export function sortedBoxes(boxes: number[][][]): number[][][] {
  const arr = boxes.map((b, i) => ({ b, i }));
  arr.sort((a, b) => a.b[0][1] - b.b[0][1] || a.b[0][0] - b.b[0][0]);
  const sorted = arr.map((x) => x.b);
  const n = sorted.length;
  for (let i = 0; i < n - 1; i++) {
    if (Math.abs(sorted[i + 1][0][1] - sorted[i][0][1]) < 10 && sorted[i + 1][0][0] < sorted[i][0][0]) {
      const tmp = sorted[i];
      sorted[i] = sorted[i + 1];
      sorted[i + 1] = tmp;
    }
  }
  return sorted;
}

/** 复刻 filter_tag_det_res：顺时针排序 + 裁剪到图像内 + 过滤过小的框。 */
export function filterDetBoxes(boxes: number[][][], h: number, w: number): number[][][] {
  const out: number[][][] = [];
  for (const box of boxes) {
    const ordered = orderPointsClockwise(box as Pt[]);
    const clip = ordered.map((p) => [Math.max(0, Math.min(w - 1, p[0])), Math.max(0, Math.min(h - 1, p[1]))]);
    const rw = Math.round(Math.hypot(clip[0][0] - clip[1][0], clip[0][1] - clip[1][1]));
    const rh = Math.round(Math.hypot(clip[0][0] - clip[3][0], clip[0][1] - clip[3][1]));
    if (rw <= 3 || rh <= 3) continue;
    out.push(clip);
  }
  return out;
}

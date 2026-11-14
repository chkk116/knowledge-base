// 图像缓冲与基础变换（纯 TS，无外部依赖）。通道约定为 cv2 的 BGR。
import type { RawImage, BgrImage } from "./types.js";

/** RGBA -> BGR（cv2 约定）。 */
export function rgbaToBgr(rgba: Uint8ClampedArray, w: number, h: number): Uint8ClampedArray {
  const out = new Uint8ClampedArray(w * h * 3);
  for (let i = 0; i < w * h; i++) {
    out[i * 3] = rgba[i * 4 + 2];
    out[i * 3 + 1] = rgba[i * 4 + 1];
    out[i * 3 + 2] = rgba[i * 4];
  }
  return out;
}

/** 双线性缩放 BGR 缓冲（近似 cv2 INTER_LINEAR）。 */
export function resizeBgr(
  src: Uint8ClampedArray, sw: number, sh: number, dw: number, dh: number,
): Uint8ClampedArray {
  const out = new Uint8ClampedArray(dw * dh * 3);
  if (dw === 0 || dh === 0) return out;
  const xr = sw / dw, yr = sh / dh;
  for (let y = 0; y < dh; y++) {
    const sy = Math.min(sh - 1, Math.floor(y * yr));
    const sy1 = Math.min(sh - 1, sy + 1);
    const fy = y * yr - sy;
    for (let x = 0; x < dw; x++) {
      const sx = Math.min(sw - 1, Math.floor(x * xr));
      const sx1 = Math.min(sw - 1, sx + 1);
      const fx = x * xr - sx;
      for (let c = 0; c < 3; c++) {
        const p00 = src[(sy * sw + sx) * 3 + c];
        const p01 = src[(sy * sw + sx1) * 3 + c];
        const p10 = src[(sy1 * sw + sx) * 3 + c];
        const p11 = src[(sy1 * sw + sx1) * 3 + c];
        const top = p00 + (p01 - p00) * fx;
        const bot = p10 + (p11 - p10) * fx;
        out[(y * dw + x) * 3 + c] = top + (bot - top) * fy;
      }
    }
  }
  return out;
}

/** RawImage(RGBA) -> BgrImage。 */
export function rawToBgr(raw: RawImage): BgrImage {
  return { bgr: rgbaToBgr(raw.rgba, raw.width, raw.height), width: raw.width, height: raw.height };
}

/**
 * 检测前处理中使用的缩放：限制短边为 limitSideLen（min 模式），并取整到 32 的倍数。
 * 返回缩放后尺寸与比例。
 */
export function detResizeShape(
  h: number, w: number, limitSideLen: number, limitType: "min" | "max",
): { rh: number; rw: number; ratioH: number; ratioW: number } {
  let ratio: number;
  if (limitType === "max") {
    ratio = max(h, w) > limitSideLen ? (max(h, w) === h ? limitSideLen / h : limitSideLen / w) : 1;
  } else {
    ratio = min(h, w) < limitSideLen ? (min(h, w) === h ? limitSideLen / h : limitSideLen / w) : 1;
  }
  let rh = Math.round((h * ratio) / 32) * 32;
  let rw = Math.round((w * ratio) / 32) * 32;
  if (rh <= 0) rh = 32;
  if (rw <= 0) rw = 32;
  return { rh, rw, ratioH: rh / h, ratioW: rw / w };
}

function min(a: number, b: number) { return a < b ? a : b; }
function max(a: number, b: number) { return a > b ? a : b; }

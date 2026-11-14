// 浏览器端入口：fetch .onnx 模型 + 字符表，构造浏览器解码器，返回 OcrEngine。
// 首次可把模型/字符表缓存进 IndexedDB（此处提供可选 hook）。
import * as ort from "onnxruntime-web";
import type { InferenceSession } from "onnxruntime-web";
import { OcrEngine, type ModelBundle, type OcrOptions } from "./engine.js";
import { buildCharacterList } from "./rec.js";
import type { RawImage } from "./types.js";

export interface WebEngineOptions extends OcrOptions {
  modelBaseUrl?: string; // 模型目录（含 3 个 .onnx 与 ppocr_keys.txt），默认 /models/
  wasmPaths?: string; // onnxruntime-web wasm 目录 URL
  onProgress?: (stage: string, pct: number) => void;
  /** 可选：自定义资源缓存（如 IndexedDB），返回 ArrayBuffer。 */
  cacheGet?: (url: string) => Promise<ArrayBuffer | null>;
  cacheSet?: (url: string, data: ArrayBuffer) => Promise<void>;
}

function join(base: string, name: string): string {
  return (base.endsWith("/") ? base : base + "/") + name;
}

async function fetchBuffer(url: string, o: WebEngineOptions): Promise<ArrayBuffer> {
  const hit = o.cacheGet ? await o.cacheGet(url) : null;
  if (hit) return hit;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`加载模型失败 ${url}: HTTP ${res.status}`);
  const buf = await res.arrayBuffer();
  if (o.cacheSet) await o.cacheSet(url, buf);
  return buf;
}

function isRawImage(x: unknown): x is RawImage {
  return !!x && typeof x === "object" && "rgba" in (x as object) && "width" in (x as object) && "height" in (x as object);
}

/** 浏览器解码：支持 RawImage / Blob / ArrayBuffer / ImageBitmap / HTMLImageElement / ImageData。 */
export async function decodeWeb(input: unknown): Promise<RawImage> {
  if (isRawImage(input)) return input;
  let bitmap: ImageBitmap;
  if (input instanceof ImageData) {
    return { width: input.width, height: input.height, rgba: new Uint8ClampedArray(input.data) };
  }
  if (typeof ImageBitmap !== "undefined" && input instanceof ImageBitmap) {
    bitmap = input;
  } else if (typeof Blob !== "undefined" && input instanceof Blob) {
    bitmap = await createImageBitmap(input);
  } else if (input instanceof ArrayBuffer || input instanceof Uint8Array) {
    const bytes = input instanceof Uint8Array ? input : new Uint8Array(input);
    const ab = new ArrayBuffer(bytes.byteLength);
    new Uint8Array(ab).set(bytes);
    bitmap = await createImageBitmap(new Blob([ab]));
  } else if (typeof HTMLImageElement !== "undefined" && input instanceof HTMLImageElement) {
    bitmap = await createImageBitmap(input);
  } else {
    throw new Error("不支持的图像输入：需 Blob / ArrayBuffer / ImageBitmap / ImageData / HTMLImageElement");
  }
  const canvas = document.createElement("canvas");
  canvas.width = bitmap.width;
  canvas.height = bitmap.height;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  if (!ctx) throw new Error("无法获取 2D canvas 上下文");
  ctx.drawImage(bitmap, 0, 0);
  const data = ctx.getImageData(0, 0, bitmap.width, bitmap.height);
  return { width: bitmap.width, height: bitmap.height, rgba: new Uint8ClampedArray(data.data) };
}

function toSession(s: InferenceSession) {
  return s as unknown as ModelBundle["det"];
}

/** 创建浏览器端 OcrEngine。 */
export async function createWebOcrEngine(opts: WebEngineOptions = {}): Promise<OcrEngine> {
  const base = opts.modelBaseUrl ?? "/models/";
  ort.env.wasm.numThreads = 1; // 无需 COOP/COEP，纯静态站点即可运行
  if (opts.wasmPaths) ort.env.wasm.wasmPaths = opts.wasmPaths;
  const progress = opts.onProgress ?? (() => {});

  const names = [
    "ch_PP-OCRv3_det_infer.onnx",
    "ch_PP-OCRv3_rec_infer.onnx",
    "ch_ppocr_mobile_v2.0_cls_infer.onnx",
    "ppocr_keys.txt",
  ];
  const buffers: ArrayBuffer[] = [];
  for (let i = 0; i < names.length; i++) {
    progress(names[i], Math.round((i / names.length) * 60));
    buffers.push(await fetchBuffer(join(base, names[i]), opts));
  }
  progress("初始化推理会话", 65);
  const mk = (buf: ArrayBuffer) => ort.InferenceSession.create(new Uint8Array(buf));
  const [det, rec, cls] = await Promise.all([mk(buffers[0]), mk(buffers[1]), mk(buffers[2])]);
  const keys = new TextDecoder("utf-8").decode(buffers[3]);
  progress("就绪", 100);
  const bundle: ModelBundle = {
    det: toSession(det),
    rec: toSession(rec),
    cls: opts.useAngleCls === false ? null : toSession(cls),
    characterList: buildCharacterList(keys),
  };
  return new OcrEngine(bundle, decodeWeb, opts);
}

export { OcrEngine } from "./engine.js";
export type { OcrResult, OcrLine, RawImage } from "./types.js";

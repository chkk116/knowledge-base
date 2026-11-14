// Node 端入口：加载本地 .onnx 模型 + 字符表，构造解码器，返回 OcrEngine。
// 用于 CI/对齐测试；生产浏览器端见 web.ts。
import * as ort from "onnxruntime-web";
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { PNG } from "pngjs";
import type { InferenceSession } from "onnxruntime-web";
import { OcrEngine, type ModelBundle, type OcrOptions } from "./engine.js";
import { buildCharacterList } from "./rec.js";
import type { RawImage } from "./types.js";

export interface NodeEngineOptions extends OcrOptions {
  modelDir?: string; // 含 3 个 .onnx 与 ppocr_keys.txt
  distDir?: string; // onnxruntime-web 的 dist（含 .wasm）
}

function resolveModelDir(o: NodeEngineOptions): string {
  return o.modelDir ?? path.resolve("models");
}

/** PNG/Buffer/路径 -> RawImage(RGBA)。 */
export async function decodeNode(input: unknown): Promise<RawImage> {
  if (isRawImage(input)) return input;
  let buf: Buffer;
  if (typeof input === "string") buf = fs.readFileSync(input);
  else if (input instanceof Uint8Array) buf = Buffer.from(input);
  else if (input instanceof ArrayBuffer) buf = Buffer.from(input);
  else throw new Error("不支持的图像输入：需 RawImage / 路径 / Buffer / PNG 字节");
  const png = PNG.sync.read(buf);
  // pngjs 输出 RGBA
  return { width: png.width, height: png.height, rgba: new Uint8ClampedArray(png.data) };
}

function isRawImage(x: unknown): x is RawImage {
  return !!x && typeof x === "object" && "rgba" in (x as object) && "width" in (x as object) && "height" in (x as object);
}

function toSession(s: InferenceSession) {
  // InferenceSession 已实现 OrtSession 的 run/inputNames/outputNames
  return s as unknown as ModelBundle["det"];
}

/** 创建 Node 端 OcrEngine（会同步配置 wasm 路径为单线程）。 */
export async function createNodeOcrEngine(opts: NodeEngineOptions = {}): Promise<OcrEngine> {
  const modelDir = resolveModelDir(opts);
  const distDir = opts.distDir ?? path.resolve("node_modules/onnxruntime-web/dist");
  ort.env.wasm.numThreads = 1;
  ort.env.wasm.wasmPaths = pathToFileURL(distDir + "/").href;

  const load = async (name: string): Promise<InferenceSession> => {
    const buf = fs.readFileSync(path.join(modelDir, name));
    return ort.InferenceSession.create(new Uint8Array(buf));
  };
  const [det, rec, cls] = await Promise.all([
    load("ch_PP-OCRv3_det_infer.onnx"),
    load("ch_PP-OCRv3_rec_infer.onnx"),
    load("ch_ppocr_mobile_v2.0_cls_infer.onnx"),
  ]);
  const keys = fs.readFileSync(path.join(modelDir, "ppocr_keys.txt"), "utf-8");
  const bundle: ModelBundle = {
    det: toSession(det),
    rec: toSession(rec),
    cls: opts.useAngleCls === false ? null : toSession(cls),
    characterList: buildCharacterList(keys),
  };
  return new OcrEngine(bundle, decodeNode, opts);
}

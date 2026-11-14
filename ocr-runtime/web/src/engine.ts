// 跨端统一 OCR 引擎（Web 端实现）。与 python/ocr_runtime/engine.py 行为对齐。
import type { OrtSession } from "./ort.js";
import type { RawImage, OcrResult, OcrLine, BgrImage } from "./types.js";
import type { RawLine } from "./shared.js";
import { rawToBgr } from "./image.js";
import { detect, sortedBoxes, filterDetBoxes, DEFAULT_DET, type DetConfig } from "./det.js";
import { getRotateCropImage } from "./crop.js";
import { classify, type ClsConfig } from "./cls.js";
import { recognize, type RecConfig } from "./rec.js";
import { columnAwareReorder } from "./layout.js";
import { fixNumbers } from "./postprocess.js";

export interface ModelBundle {
  det: OrtSession;
  cls: OrtSession | null;
  rec: OrtSession;
  characterList: string[];
}

export interface OcrOptions {
  lang?: string;
  device?: string; // cpu / gpu（信息性；具体 EP 由会话创建时决定）
  columnAware?: boolean;
  postprocess?: boolean;
  useAngleCls?: boolean;
  textScore?: number;
  minHeight?: number;
  widthHeightRatio?: number;
  detConfig?: Partial<DetConfig>;
  clsConfig?: Partial<ClsConfig>;
  recConfig?: Partial<RecConfig>;
}

export const DEFAULT_CLS: ClsConfig = {
  clsImageShape: [3, 48, 192],
  clsBatchNum: 6,
  clsThresh: 0.9,
  labelList: ["0", "180"],
};

export const DEFAULT_REC: RecConfig = {
  recImageShape: [3, 48, 320],
  recBatchNum: 6,
};

type DecodeImage = (input: unknown) => Promise<RawImage>;

export class OcrEngine {
  private bundle: ModelBundle;
  private decode: DecodeImage;
  readonly lang: string;
  readonly device: string;
  private columnAware: boolean;
  private postprocess: boolean;
  private useAngleCls: boolean;
  private textScore: number;
  private minHeight: number;
  private widthHeightRatio: number;
  private detCfg: DetConfig;
  private clsCfg: ClsConfig;
  private recCfg: RecConfig;

  constructor(bundle: ModelBundle, decode: DecodeImage, opts: OcrOptions = {}) {
    this.bundle = bundle;
    this.decode = decode;
    this.lang = opts.lang ?? "ch";
    this.device = opts.device ?? "cpu";
    this.columnAware = opts.columnAware ?? true;
    this.postprocess = opts.postprocess ?? true;
    this.useAngleCls = opts.useAngleCls ?? true;
    this.textScore = opts.textScore ?? 0.5;
    this.minHeight = opts.minHeight ?? 30;
    this.widthHeightRatio = opts.widthHeightRatio ?? 8;
    this.detCfg = { ...DEFAULT_DET, ...(opts.detConfig ?? {}) };
    this.clsCfg = { ...DEFAULT_CLS, ...(opts.clsConfig ?? {}) };
    this.recCfg = { ...DEFAULT_REC, ...(opts.recConfig ?? {}) };
  }

  async recognize(image: unknown): Promise<OcrResult> {
    const t0 = performance.now();
    const raw = await this.decode(image);
    const src: BgrImage = rawToBgr(raw);
    const h = raw.height, w = raw.width;

    let boxes: number[][][];
    let crops: BgrImage[];

    // 与 RapidOCR 一致：太矮 / 过于宽扁的图不做检测，直接整图识别
    const useLimitRatio = w / h > this.widthHeightRatio;
    const useDet = h > this.minHeight && !useLimitRatio;
    if (useDet) {
      const { boxes: detBoxes } = await detect(this.bundle.det, raw, this.detCfg);
      const filtered = filterDetBoxes(detBoxes, h, w);
      if (filtered.length === 0) {
        return this.emptyResult(performance.now() - t0);
      }
      boxes = sortedBoxes(filtered);
      crops = boxes.map((box) => getRotateCropImage(src, box));
    } else {
      boxes = [[[0, 0], [w, 0], [w, h], [0, h]]];
      crops = [src];
    }

    // 方向分类：可能把 180° 的裁剪图转正
    if (this.useAngleCls && this.bundle.cls) {
      const res = await classify(this.bundle.cls, crops, this.clsCfg);
      crops = res.crops;
    }

    // 识别
    const recRes = await recognize(this.bundle.rec, crops, this.bundle.characterList, this.recCfg);

    // 按置信度过滤（与 RapidOCR.filter_boxes_rec_by_score 一致）
    const items: RawLine[] = [];
    for (let i = 0; i < boxes.length; i++) {
      const text = recRes[i]?.text ?? "";
      const score = recRes[i]?.score ?? 0;
      if (score >= this.textScore) items.push({ box: boxes[i], text, score });
    }

    // 版面阅读顺序重排（保守：单栏零改动）
    let ordered = this.columnAware ? columnAwareReorder(items) : items;

    // 数字后处理
    const lines: OcrLine[] = ordered.map((it, i) => ({
      text: this.postprocess ? fixNumbers(it.text) : it.text,
      box: it.box,
      score: it.score,
      line_no: i,
    }));

    const warnings: string[] = [];
    if (lines.length === 0) warnings.push("未检测到文字");

    return {
      text: lines.map((l) => l.text).join("\n"),
      lines,
      lang: this.lang,
      elapsed_ms: Math.round((performance.now() - t0) * 10) / 10,
      engine: "rapidocr-onnx",
      warnings,
    };
  }

  private emptyResult(elapsed: number): OcrResult {
    return {
      text: "",
      lines: [],
      lang: this.lang,
      elapsed_ms: Math.round(elapsed * 10) / 10,
      engine: "rapidocr-onnx",
      warnings: ["未检测到文字"],
    };
  }
}

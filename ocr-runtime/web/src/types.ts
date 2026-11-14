// 跨端统一 OCR 结果类型（与 python/ocr_runtime/types.py 对齐）
export interface OcrLine {
  text: string; // 该行文本（已过后处理）
  box: number[][]; // 4 角点 [[x1,y1],[x2,y2],[x3,y3],[x4,y4]]（图像像素坐标）
  score: number; // 识别置信度 0~1
  line_no: number; // 阅读顺序编号（重排后）
}

export interface OcrResult {
  text: string; // 已按正确阅读顺序拼接的全文（行用 \n 连接）
  lines: OcrLine[];
  lang: string;
  elapsed_ms: number;
  engine: string;
  warnings: string[];
}

// 解码后的图像（RGBA）
export interface RawImage {
  width: number;
  height: number;
  rgba: Uint8ClampedArray; // length = width*height*4
}

// BGR 像素缓冲（cv2 约定：通道顺序 B,G,R）
export interface BgrImage {
  bgr: Uint8ClampedArray; // length = width*height*3
  width: number;
  height: number;
}

# kb-ocr-web · 跨端统一离线 OCR 运行时（Web / WASM 端）

把 Python 端的 `OcrEngine` API 原样搬到浏览器：同一份 RapidOCR PP-OCRv3 **ONNX 模型**、
同一套 API 契约（`OcrEngine.recognize() → OcrResult`）、同样的版面重排与数字后处理逻辑，
全部在浏览器本地用 **ONNX Runtime Web (WASM)** 推理，**无需联网、无需服务端**。

> **前置条件**：把 3 个 PP-OCRv3 `.onnx` 权重放到 `models/` 后即可运行。权重约 14MB，**本地保留、不入库**（主仓会镜像到公开 GitHub），仓库里只保留 `ppocr_keys.txt` 与代码。

定位：替代当前 Web 端 `Tesseract.js` 兜底（中文准确率明显更高，且可完全离线、私有化部署）。

## 与 Python 端的行为对齐

同一组 27 张合成中文图（5 类难度）实测，指标为字符级准确率 = 1 − CER（去空白归一化）：

| 类别 | 裸 Web | Web 运行时 | Python 运行时 | 差异 |
|---|---|---|---|---|
| 干净单行 | 95.5% | 95.5% | 92.9% | +2.6pt |
| 多行段落 | 97.3% | 97.3% | 97.3% | 0 |
| 扫描噪声 | 97.6% | 97.6% | 98.4% | −0.8pt |
| 双栏 | 51.4% | **92.6%** | 94.6% | −2.0pt |
| 中英数混排 | 73.9% | 73.9% | 74.6% | −0.7pt |
| **整体** | **82.9%** | **91.5%** | **91.8%** | **−0.3pt** |

- 裸 Web 82.9% ≈ Python 裸 RapidOCR 83.0%（推理管线对齐）。
- 版面重排把双栏从 51.4% 拉到 92.6%（+41.2pt），单栏零回归。
- 与 Python 运行时整体差 0.3pt；残余差异来自检测框 ±1px 的取整/插值边界，属可接受范围。

详见 `alignment_results.json` 与 `src/align.ts`。

## 快速开始

```bash
cd web
npm install
npm run demo          # 启动 Vite，打开 http://localhost:5173
npm run test:align    # Node 端跑 27 张黄金样本对齐测试（约 2 分钟）
npm run build         # tsc 类型检查 + 产出 dist/（库）
npx vite build        # 产出 dist-demo/（静态站点，含 models/ 与 wasm/）
```

demo 首页可直接上传图片，或点「用内置示例」现场生成一张中文图测试；
右侧展示识别文本、行数、耗时，左侧叠加检测框。支持切换「运行时全开 / 裸识别」对比效果。

## 作为库使用（浏览器）

```ts
import { createWebOcrEngine } from "kb-ocr-web";

const engine = await createWebOcrEngine({
  modelBaseUrl: "/models/",   // 含 3 个 .onnx 与 ppocr_keys.txt
  wasmPaths: "/wasm/",        // onnxruntime-web 的 .wasm 目录；不传则用打包器默认路径
  columnAware: true,          // 多栏阅读顺序重排
  postprocess: true,          // 数字正则后处理
  onProgress: (stage, pct) => console.log(stage, pct),
  // 可选：把模型缓存进 IndexedDB，实现真正离线
  cacheGet: async (url) => /* ArrayBuffer | null */ null,
  cacheSet: async (url, buf) => {},
});

const result = await engine.recognize(file /* Blob | ArrayBuffer | ImageBitmap | ImageData | HTMLImageElement */);
console.log(result.text);
// result: { text, lines: [{text, box, score, line_no}], lang, elapsed_ms, engine, warnings }
```

`recognize()` 输入、返回结构与 Python / Android / iOS 完全一致，见 `../docs/api-contract.md`。

## 目录

```
web/
├── models/                     # 本地放置的 ONNX 权重（不入库）+ 字符表
│   ├── ch_PP-OCRv3_det_infer.onnx        # 本地文件，不入库
│   ├── ch_PP-OCRv3_rec_infer.onnx        # 本地文件，不入库
│   ├── ch_ppocr_mobile_v2.0_cls_infer.onnx  # 本地文件，不入库
│   └── ppocr_keys.txt          # 从 rec 模型元数据导出（6623 字）
├── src/
│   ├── web.ts / node.ts        # 浏览器 / Node 入口（模型加载 + 图像解码）
│   ├── engine.ts               # OcrEngine：det→crop→cls→rec→过滤→重排→后处理
│   ├── det.ts / crop.ts        # 检测前处理 + DB 后处理、透视裁剪
│   ├── rec.ts / cls.ts         # 识别/方向分类前处理 + CTC 解码
│   ├── geometry.ts             # 凸包/旋转卡壳最小外接矩形/连通域/透视变换（纯 TS）
│   ├── layout.ts / postprocess.ts  # 与 Python 逐行对齐的版面重排 / 数字后处理
│   ├── image.ts / types.ts / ort.ts / clipperUtil.ts
│   └── align.ts                # 27 图对齐测试
├── demo/main.ts + index.html   # Vite 演示页
└── vite.config.ts              # 把本地 models/ 与 .wasm 作为静态资源提供
```

## 实现要点 / 踩坑记录

1. **不依赖 opencv.js**：OpenCV 的 wasm 需联网 CDN 且不打包在 npm 包内，不适合离线运行时。
   检测后处理（`findContours` / `minAreaRect` / `boxPoints` / `fillPoly` / `dilate`）与
   `getPerspectiveTransform` / `warpPerspective` 全部用纯 TS 复刻（凸包 + 旋转卡壳 + 连通域 +
   DLT 单应矩阵），只有 `clipper-lib`（纯 JS）用于多边形外扩 unclip。
2. **识别输入宽度是动态的**（最易踩的坑）：PaddleOCR 按 batch 内最宽图算
   `img_width = int(48 * max_wh_ratio)`，不是固定的 320。写死 320 会静默截断长行文本
   （"…文档协同编辑与" 被吃掉）。见 `rec.ts`。
3. **cv2 膨胀锚点**：`cv2.dilate` 2×2 核的锚点是 (1,1)，看的是左上 2×2 窗口；
   写成右下窗口会让 mask 偏移 1px，进而影响多栏分栏判定。见 `det.ts`。
4. **np.round 是银行家舍入**（.5 取偶），JS `Math.round` 是 .5 进位，需自行对齐。
5. **clipper-lib 是 CJS**：Node ESM 下命名导出挂在 `.default`，Vite 下是命名空间，需兼容。
6. **WASM 单线程**：`ort.env.wasm.numThreads = 1`，避免要求 COOP/COEP 响应头，纯静态站点即可跑。

## 许可证

代码 Apache-2.0。模型来自 PaddleOCR/RapidOCR（Apache-2.0），ONNX Runtime（MIT）。
可商用、可售卖、可再分发，仅需保留署名。

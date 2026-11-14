# 跨端 API 契约与移植计划

目标：同一套 ONNX 模型 + 同一套 API 行为，覆盖后端 / 桌面 / Web / Android / iOS。
各端用 ONNX Runtime 的对应绑定加载同一份 .onnx 模型，并各自实现一份薄胶水
（预处理、调用、版面重排、数字后处理），对外暴露完全一致的接口与返回结构。

---

## 1. 统一 API 契约（所有端必须遵守）

### 构造
OcrEngine(lang="ch", device="cpu", column_aware=true, postprocess=true)
- lang：识别语言（ch / en / ...）
- device：cpu / gpu / npu（按端能力映射为具体 delegate）
- column_aware：是否做多栏阅读顺序重排
- postprocess：是否做数字正则后处理

### 识别
OcrResult recognize(image)
image 在各端的等价输入：
- Python：路径(str) / 字节(bytes) / numpy.ndarray(RGB/HWC)
- Web：Blob / ImageBitmap / ImageData / ArrayBuffer
- Android：Bitmap / 文件路径 / ByteArray
- iOS：UIImage / 文件路径 / Data

### 返回结构 OcrResult（跨端字段名/类型一致）
| 字段 | 类型 | 说明 |
|---|---|---|
| text | string | 已按正确阅读顺序拼接的全文（行用 \n 连接） |
| lines | OcrLine[] | 逐行结果 |
| lang | string | 实际识别语言 |
| elapsed_ms | number | 本次识别耗时（毫秒） |
| engine | string | 底层引擎标识，如 rapidocr-onnx |
| warnings | string[] | 告警（如未检测到文字） |

### OcrLine
| 字段 | 类型 | 说明 |
|---|---|---|
| text | string | 该行文本（已过后处理） |
| box | number[4][2] | 4 个角点 [[x1,y1],...]（图像像素坐标） |
| score | number | 识别置信度 0~1 |
| line_no | number | 阅读顺序编号（重排后） |

行为一致性要求：同一张图在任意端调用，返回的 text 与 lines 阅读顺序应一致。
版面重排与数字后处理的逻辑在各端独立实现，但必须以本仓库 Python 版为参照实现（golden）做对齐测试。

---

## 2. 模型文件（所有端共用）
从 RapidOCR 导出的 ONNX 模型（检测 / 识别 / 方向分类各一个 .onnx），统一放在仓库
models/ 下，各端按需拷贝进各自构建产物。模型许可证随 PaddleOCR（Apache-2.0）。

---

## 3. 各端移植步骤

### 后端 / 桌面（Python）— 已完成
- 依赖：rapidocr-onnxruntime（封装 ONNX Runtime）
- 见 python/ocr_runtime/：engine / layout / postprocess / types
- 直接可作为后端微服务，或桌面端（Compose Desktop / 其他 JVM 应用通过 subprocess/grpc 调用）

### Web（WASM）— 已完成（见 `web/`）
1. 依赖 `onnxruntime-web`（WASM 推理，`numThreads=1` 故不要求 COOP/COEP）+ `clipper-lib`（纯 JS，unclip）。
   **不使用 opencv.js**：其 wasm 未随 npm 包分发、需联网 CDN，与「离线运行时」目标冲突；
   检测几何（连通域 / 凸包 / 旋转卡壳最小外接矩形 / DLT 单应矩阵 / 双线性采样）全部用纯 TS 复刻。
2. 模型 .onnx 与 `ppocr_keys.txt`（从 rec 模型 metadata 导出的 6623 字表）放同源 `models/`；
   `createWebOcrEngine` 支持注入 `cacheGet/cacheSet` 把模型缓存进 IndexedDB。
   打包器（Vite）把 `onnxruntime-web` 的 .wasm 作为 asset 自动分发，静态站点即可离线运行。
3. 图像预处理（Resize/Normalize）→ Tensor → 跑 det/cls/rec 三模型；
   **识别输入宽度为动态 `int(48*max_wh_ratio)`，不能写死 320**（否则长行被静默截断）。
4. TypeScript 复刻 `layout.column_aware_reorder` 与 `postprocess.fix_numbers`，与 Python 逐行对齐。
5. npm 包 `kb-ocr-web` 暴露同样的 `OcrEngine.recognize()` / `OcrResult`，输入支持
   Blob / ArrayBuffer / ImageBitmap / ImageData / HTMLImageElement / RawImage。
6. 对齐测试（`web/src/align.ts`，27 张黄金样本）：整体 **Web 运行时 91.5% vs Python 91.8%（−0.3pt）**，
   裸 Web 82.9% vs Python 裸 83.0%；双栏靠版面重排 51.4% → 92.6%。
7. 浏览器实测（Playwright + Chrome，1440 / 390×844）：内置示例识别正确、无 pageerror、无横向溢出。

### Android（Kotlin）— 已完成（见 `android/`）
1. 依赖 `com.microsoft.onnxruntime:onnxruntime-android:1.20.0`（.aar）。
   **核心源码为纯 Kotlin/JVM，不依赖 Android SDK 类**：`ai.onnxruntime.*` 在 JVM 版与 Android 版
   包名一致，因此同一份源码两端通用；本项目已用 Android aar 的 `classes.jar` 重新编译验证通过。
2. 模型放 `app/src/main/assets/`（det/rec/cls 三个 .onnx + `ppocr_keys.txt`），
   `OcrEngine.fromModelBytes()` 从任意字节来源加载；`Bitmap.getPixels()` → `rawFromArgb()` 转成统一 `RawImage`。
3. 检测几何（连通域、凸包+旋转卡壳、DLT 单应、unclip）全部纯 Kotlin，
   不引入 opencv/clipper 的 JNI so；NNAPI 可选（默认 CPU 最稳，代码已兜底回退）。
4. Kotlin 实现同样的版面重排 / 数字后处理，与 Python、Web 逐行对齐。
5. 暴露 `OcrEngine.recognize(raw: RawImage): OcrResult`，字段/类型与契约一致。
6. 可替代/补充现有 ML Kit，完全离线、可私有化。
7. 对齐测试（`android/src/test/kotlin/com/kb/ocr/AlignMain.kt`，桌面 JVM 跑 27 图，无需真机）：
   **Kotlin 运行时 91.5% vs Python 91.8%（−0.3pt）**，且与 Web 端逐项完全一致；
   双栏 51.4% → 92.6%。详见 `android/alignment_results.json`。

### iOS（Swift）— 待做
1. 引入 onnxruntime-objc，启用 CoreMLExecutionProvider
2. UIImage -> CVPixelBuffer -> Tensor
3. Swift 复刻版面/后处理逻辑
4. 暴露 OcrEngine.recognize(_ image: UIImage) -> OcrResult

---

## 4. 对齐测试
每个端实现后，跑同一组黄金样例（本仓库 outputs/ocr-bench/dataset/ 同款标准答案），
断言 text 与 Python 版一致（允许因浮点/线程导致的极小差异）。CI 里对 Web/Android/iOS
跑最小对齐用例，防止接口漂移。

Web 端已接入：`cd web && npm run test:align`，输出 `web/alignment_results.json`。
Android/Kotlin 端已接入：`cd android && ./gradlew runAlignment`，输出 `android/alignment_results.json`
（用桌面 JVM 版 onnxruntime 加载同一份模型，无需真机）。
两端整体均差 0.3pt（残余来自检测框 ±1px 的取整/插值边界），可作为后续各端的对齐基线。
另：`./gradlew compileKotlin -Pkb.ort.androidJar=<classes.jar>` 可用 Android 版 API 复编译核心，防止接口漂移。

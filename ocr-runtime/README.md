# KB Unified OCR Runtime

> 一套 API、一套 ONNX 模型，跑遍 Android / iOS / 桌面 / Web / 后端 的**离线 OCR 运行时**。
> 不重训模型——底层复用 PaddleOCR / RapidOCR 的 ONNX 模型 + ONNX Runtime 推理引擎，
> 我们自建的是上面那层：**统一接口 + 版面阅读顺序修正 + 数字后处理 + 跨端 API 契约**。

## 为什么需要它

我们自己的多端产品里 OCR 是碎片化的：Android 用 ML Kit、Web 用 Tesseract.js、后端用云端 vision，
三套互不相通、中文准确率参差。本运行时用 **ONNX Runtime** 把同一套 PaddleOCR 模型推理到所有端，
并提供一致的 `recognize(image) -> OcrResult` 接口，外加两个开源引擎普遍缺失的工程能力：

- **版面阅读顺序修正**：多栏 / 论文 / 表格场景，通用 OCR 会按行跨栏交错读取（实测准确率从 ~97% 跌到 ~53%），
  运行时按栏聚类重排，恢复正确阅读顺序。
- **数字后处理**：电话 / 金额 / 日期等中英文+数字混排，通用 OCR 数字易误读（实测 ~75%），
  运行时按正则约束做保守的数字字符校正。

## 许可证

Apache-2.0（与底层 RapidOCR / PaddleOCR / ONNX Runtime 一致，商用友好，允许售卖与再分发）。

## 架构

```
                 ┌─────────────────────────────────────────┐
   任意端调用     │           统一 API: recognize(img)        │
                 │   OcrResult{ text, lines[], elapsed_ms } │
                 └─────────────────────────────────────────┘
                                │
        ┌───────────────┬───────┴────────┬───────────────────┐
     预处理          版面重排           识别(ONNX)          后处理
   尺寸/色彩      按栏聚类重排        RapidOCR(ONNX)      数字正则校正
                                + ONNX Runtime
```

| 端 | 绑定 | 状态 |
|---|---|---|
| 后端 / 桌面 (Python) | `rapidocr-onnxruntime` | MVP 已实现并实测（golden） |
| Web (WASM) | `onnxruntime-web` | **已实现并对齐实测**（见 `web/README.md`） |
| Android (Kotlin) | `onnxruntime-android` (.aar) | **已实现并对齐实测**（见 `android/README.md`） |
| iOS (Swift) | `onnxruntime-objc` (CoreML) | 待移植 |

三端跑同一组 27 张黄金样本，整体字符准确率：Python 运行时 91.8%、Web 91.5%、Android/Kotlin 91.5%
（双栏靠版面重排 51.4% → 92.6%），**跨端差 ≤0.3pt**。
Web 端用纯 TS、Android 端用纯 Kotlin 各自复刻了检测几何与识别管线，均不引入 OpenCV native 依赖。
- Web：浏览器（Chrome/WASM）实测识别成功、无 pageerror、桌面/移动无横向溢出。`cd web && npm run test:align`
- Android：桌面 JVM 加载同一份 ONNX 跑对齐（无需真机），并用 Android aar 的 `classes.jar` 复编译验证核心可上真机。`cd android && ./gradlew runAlignment`

## 快速开始（Python）

```python
from ocr_runtime import OcrEngine

engine = OcrEngine(lang="ch", device="cpu")        # 首次自动下载 ONNX 模型
result = engine.recognize("doc.png")               # 也支持 bytes / numpy 数组
print(result.text)                                 # 已按正确阅读顺序拼接
for line in result.lines:
    print(line.text, line.score, line.box)
```

启用版面重排与数字后处理（默认开）：

```python
engine = OcrEngine(column_aware=True, postprocess=True)
```

## 实测结果（MVP，详见 [benchmark_results.md](benchmark_results.md)）

在合成中文测试集（27 张 / 5 类难度，CPU）上，对比裸 RapidOCR 与本运行时：

| 难度类别 | 裸 RapidOCR | 运行时(版面重排) | 变化 |
|---|---|---|---|
| 干净单行 | 92.9% | 92.9% | 0 |
| 中英文+数字混排 | 74.6% | 74.6% | 0 |
| 多行段落 | 97.3% | 97.3% | 0 |
| 扫描噪声 | 98.4% | 98.4% | 0 |
| **双栏** | **52.7%** | **94.6%** | **+41.9pt** |
| **整体** | **83.0%** | **91.8%** | **+8.8pt** |

> 版面重排把双栏准确率从 52.7% 拉到 94.6%，且单栏文档零回归。
> 数字后处理对当前测试集无效（样本错误是「漏字」而非「字母↔数字混淆」，替换救不回），属已知 scope。

## 目录

```
ocr-runtime/
├── python/            # Python 核心实现（后端/桌面，golden 参照）
│   └── ocr_runtime/
├── web/               # Web(WASM) TS 实现：OcrEngine / det / rec / cls / layout / demo
│   ├── src/           # 纯 TS 推理管线 + 浏览器/Node 双入口 + 对齐测试
│   ├── models/        # 三端共用的 3 个 ONNX 模型 + 字符表
│   └── demo/          # Vite 演示页
├── android/           # Android(Kotlin) 实现：纯 Kotlin/JVM 核心 + 接入层
│   ├── src/main/kotlin/com/kb/ocr/   # 与 Web 同构的推理管线（无 Android SDK 依赖）
│   └── src/test/kotlin/com/kb/ocr/   # 27 图对齐测试（桌面 JVM）
├── docs/              # 跨端 API 契约与移植指南
├── benchmark_results.md      # Python 端实测
├── web/README.md             # Web 端实测与踩坑
├── android/README.md         # Android 端实测、接入示例与踩坑
└── LICENSE            # Apache-2.0
```

详见 docs/api-contract.md。

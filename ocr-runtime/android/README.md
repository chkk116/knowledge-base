# kb-ocr-android · 跨端统一离线 OCR 运行时（Android / Kotlin 端）

把 Python / Web 端同一套 `OcrEngine.recognize() → OcrResult` 搬到 Android：
**同一份 RapidOCR PP-OCRv3 ONNX 模型**、同一套 API 契约、同样的版面重排与数字后处理，
用 `onnxruntime-android` 在设备本地推理，**完全离线、无云端、可私有化**，
可替代/补充现有 ML Kit 文本识别。

> 核心写成**纯 Kotlin/JVM，不依赖任何 Android SDK 类**（图像用 `RawImage` 像素数组，
> 推理用 `ai.onnxruntime.*`）。因此同一份源码既能上真机，也能在桌面 JVM 跑真实对齐测试。

## 与 Python / Web 端的行为对齐

27 张合成中文图（5 类难度），字符级准确率 = 1 − CER（去空白归一化）：

| 类别 | 裸 Kotlin | Kotlin 运行时 | Web 运行时 | Python 运行时 |
|---|---|---|---|---|
| 干净单行 | 95.5% | 95.5% | 95.5% | 92.9% |
| 多行段落 | 97.3% | 97.3% | 97.3% | 97.3% |
| 扫描噪声 | 97.6% | 97.6% | 97.6% | 98.4% |
| 双栏 | 51.4% | **92.6%** | 92.6% | 94.6% |
| 中英数混排 | 73.9% | 73.9% | 73.9% | 74.6% |
| **整体** | **82.9%** | **91.5%** | 91.5% | **91.8%** |

- Kotlin 与 Web **逐项完全一致**（同样的算法逐行移植）；与 Python 运行时差 0.3pt。
- 版面重排把双栏 51.4% → 92.6%（+41.2pt），单栏零回归。
- 残余差异来自检测框 ±1px 的取整/插值边界。
- 详见 `alignment_results.json` 与 `src/test/kotlin/com/kb/ocr/AlignMain.kt`。

## 复现测试（桌面 JVM，无需 Android 真机）

```bash
cd android
./gradlew runAlignment        # 27 张黄金样本，约 1~2 分钟
./gradlew compileKotlin -Pkb.ort.androidJar=<aar 解出的 classes.jar>   # 用 Android 版 API 编译校验
```

`runAlignment` 用桌面 JVM 版 `onnxruntime:1.20.0`（jar 内含 Windows/Linux/macOS native），
加载 `../web/models/` 下本地放置的同一份 `.onnx`（不入库）与 `ppocr_keys.txt`，跑 `../../outputs/ocr-bench/dataset/`。
`compileKotlin -Pkb.ort.androidJar=...` 会把依赖换成 Android 版 aar 的 `classes.jar` 重新编译，
用于证明核心源码在 Android API 下同样可编译 —— 本项目已实测通过。

## 接入 Android App

### 1. 依赖与模型

`app/build.gradle.kts`：

```kotlin
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // 把 android/src/main/kotlin/com/kb/ocr/ 拷进 app 源码，或作为 :ocr 模块依赖
}
```

把 4 个文件放到 `app/src/main/assets/`（可从 `../web/models/` 直接拷）：

```
ch_PP-OCRv3_det_infer.onnx          2.4 MB
ch_PP-OCRv3_rec_infer.onnx         10.7 MB
ch_ppocr_mobile_v2.0_cls_infer.onnx  0.6 MB
ppocr_keys.txt                     字符表（6623 行）
```

> 注意 App 体积会增加约 14MB；如需瘦身可用「首次启动下载到私有目录 + 校验 SHA-256」，
> 运行时仍完全离线。`fromModelBytes()` 接受任意字节来源。

### 2. 初始化（建议单例，加载一次约数百毫秒）

```kotlin
import android.content.Context
import com.kb.ocr.*

class OcrManager(ctx: Context) {
    private fun asset(name: String) = ctx.assets.open(name).use { it.readBytes() }

    val engine: OcrEngine = OcrEngine.fromModelBytes(
        detBytes = asset("ch_PP-OCRv3_det_infer.onnx"),
        recBytes = asset("ch_PP-OCRv3_rec_infer.onnx"),
        clsBytes = asset("ch_ppocr_mobile_v2.0_cls_infer.onnx"),
        keysText = ctx.assets.open("ppocr_keys.txt").bufferedReader().use { it.readText() },
        options = OcrOptions(
            lang = "ch",
            columnAware = true,   // 多栏阅读顺序重排
            postprocess = true,   // 数字正则后处理
            useAngleCls = true,   // 竖排/倒置方向分类
        ),
        useNnapi = false,         // NNAPI 对动态 shape 兼容性不一，默认 CPU 最稳
    )
}
```

### 3. Bitmap → 识别（务必放后台线程）

```kotlin
fun recognize(bitmap: android.graphics.Bitmap): String {
    val px = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val raw = rawFromArgb(px, bitmap.width, bitmap.height)   // ARGB_8888 → RGBA
    val result = engine.recognize(raw)
    return result.text
}
```

`OcrResult`：

```kotlin
data class OcrResult(
    val text: String,                 // 已按正确阅读顺序拼接（行用 \n 连接）
    val lines: List<OcrLine>,         // 每行 text / box(4角点) / score / lineNo
    val lang: String,
    val elapsedMs: Double,
    val engine: String,               // "rapidocr-onnx"
    val warnings: List<String>,       // 如「未检测到文字」
)
```

### 4. 性能与线程

- 首图最慢（会话预热），建议启动时用一张 1×1 空白图 warm-up 一次。
- `OrtSession` 内部非线程安全保证因版本而异，建议同一时刻只跑一路识别（用单线程池 `Executors.newSingleThreadExecutor()`）。
- 大图先降采样到长边 ~1280px 再送入，检测阶段本身会把短边缩放到 736。
- `useNnapi = true` 可在部分设备加速，但动态 shape 下可能回退或报错，代码里已 `try/catch` 兜底 CPU。

## 目录

```
android/
├── build.gradle.kts / settings.gradle.kts / gradle.properties / gradlew
├── src/main/kotlin/com/kb/ocr/
│   ├── Engine.kt        # OcrEngine + 模型加载 + Bitmap→RawImage 入口
│   ├── Session.kt       # ai.onnxruntime.* 会话封装（JVM/Android 通用）
│   ├── Det.kt / Crop.kt / Rec.kt / Cls.kt   # 推理管线
│   ├── Geometry.kt      # 凸包/旋转卡壳/连通域/透视变换/unclip（纯 Kotlin）
│   ├── ImageUtil.kt     # RGBA→BGR、双线性缩放、检测缩放
│   ├── Layout.kt / Postprocess.kt           # 与 Python 逐行对齐
│   ├── Types.kt         # OcrLine/OcrResult/RawImage/BgrImage
│   └── AlignMain 相关见 test
├── src/test/kotlin/com/kb/ocr/
│   ├── AlignMain.kt     # 27 图对齐测试
│   └── ImageIo.kt       # 桌面 JVM 专用 PNG 解码（Android 不含此类）
└── alignment_results.json
```

## 实现要点 / 踩坑

1. **不依赖 opencv / clipper 等native 库**：检测几何（连通域、凸包+旋转卡壳最小外接矩形、
   DLT 单应矩阵、双线性采样、多边形外扩 unclip）全部纯 Kotlin，避免引入 JNI so 增大包体。
   unclip 用 miter 外扩，对凸文本四边形与 clipper 的 round join 等价（随后再取一次 minAreaRect，差异被吸收）。
2. **识别填充宽度是动态 `int(48 * max_wh_ratio)`**，不是固定 320（否则长行被静默截断）。
3. **cv2.dilate 2×2 锚点 (1,1) 看左上窗口**；`np.round` 是银行家舍入，Kotlin 需自行实现。
4. **Kotlin 顶层 `main` 编译为 `<FileName>Kt`**，`JavaExec.mainClass` 要写 `com.kb.ocr.AlignMainKt`。
5. **中文路径**：`JavaExec` 加 `-Dfile.encoding=UTF-8`，否则 Gradle JVM → 子进程传参时中文路径会乱码。
6. **核心不放 JDK-only API**：`javax.imageio.ImageIO` 只在 test sourceSet，Android 运行时没有该类。

## 许可证

代码 Apache-2.0。模型来自 PaddleOCR/RapidOCR（Apache-2.0），ONNX Runtime（MIT）。
可商用、可售卖、可再分发，仅需保留署名。

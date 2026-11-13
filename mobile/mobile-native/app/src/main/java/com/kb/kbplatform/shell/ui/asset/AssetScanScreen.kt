/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.asset

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.kb.kbplatform.shell.ui.common.TopBar
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

private const val TAG = "KbAssetScan"

/**
 * 原生「扫一扫」：CameraX 实时取景 + ML Kit 二维码识别（bundled 模型，不依赖 Play 服务）。
 *
 * 与 WebView 版的区别：相机画面由原生 PreviewView 渲染，不走 getUserMedia，
 * 因此不需要 WebView 的 onPermissionRequest 桥；CAMERA 仍要运行时授权，但只在
 * 本页申请，进页面就能看到取景画面。
 *
 * 识别到内容后把「资产编号」交给上层跳资产详情；二维码既支持新标签的站内链接
 * （/m/asset#code=xxx），也兼容旧标签的纯编号，规则与网页版 parseScanned 一致。
 */
@Composable
fun AssetScanScreen(
    onBack: () -> Unit,
    onCode: (String) -> Unit,
    onOpenWeb: () -> Unit,
) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    // 组合完成后再 launch：在 factory / 组合阶段调用会报 Launcher has not been initialized
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var torchOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var manualOpen by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }

    val holder = remember { ScanCameraHolder() }
    val handled = remember { AtomicBoolean(false) }
    val currentOnCode by rememberUpdatedState(onCode)

    DisposableEffect(holder) {
        onDispose { holder.release() }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        TopBar(
            title = "扫一扫",
            onBack = onBack,
            actions = {
                if (granted && camera?.cameraInfo?.hasFlashUnit() == true) {
                    IconButton(onClick = { torchOn = !torchOn }) {
                        Icon(
                            imageVector = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = if (torchOn) "关闭手电筒" else "打开手电筒",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        )

        if (granted) {
            Box(Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        val previewView = PreviewView(c).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            // TextureView：Compose 叠在上面的取景框/遮罩不会出现 SurfaceView 的层级问题
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                        bindCamera(
                            c, previewView, holder, handled,
                            onCameraReady = { cam -> camera = cam },
                            onResult = { code -> currentOnCode(code) }
                        )
                        previewView
                    },
                    onRelease = { holder.release() }
                )

                ScanFrameOverlay()

                Text(
                    "把资产标签二维码放进框内",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 28.dp)
                )
            }
        } else {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "实时扫码需要相机权限",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "也可以改用网页版拍照识别，或直接手动输入资产编号。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("授予相机权限")
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onOpenWeb) { Text("网页版扫码") }
            TextButton(onClick = { manualOpen = true }) { Text("手动输入编号") }
        }
    }

    // 手电筒：CameraControl 必须在主线程调用；没闪光灯的机器直接忽略
    LaunchedEffect(torchOn, camera) {
        val c = camera ?: return@LaunchedEffect
        if (!c.cameraInfo.hasFlashUnit()) return@LaunchedEffect
        try {
            c.cameraControl.enableTorch(torchOn)
        } catch (e: Exception) {
            Log.w(TAG, "切换手电筒失败", e)
        }
    }

    if (manualOpen) {
        AlertDialog(
            onDismissRequest = { manualOpen = false },
            title = { Text("输入资产编号") },
            text = {
                OutlinedTextField(
                    value = manualText,
                    onValueChange = { manualText = it },
                    singleLine = true,
                    label = { Text("如 ZC-2026-0001") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val c = manualText.trim()
                    if (c.isNotEmpty()) {
                        manualOpen = false
                        if (handled.compareAndSet(false, true)) currentOnCode(c)
                    }
                }) { Text("查询") }
            },
            dismissButton = {
                TextButton(onClick = { manualOpen = false }) { Text("取消") }
            }
        )
    }
}

/** 取景框：四周压暗 + 白色圆角边框，Pure Compose 绘制，不占用额外 View */
@Composable
private fun ScanFrameOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val side = min(w, h) * 0.62f
        val l = (w - side) / 2f
        val t = (h - side) / 2f
        val r = l + side
        val b = t + side
        val scrim = Color.Black.copy(alpha = 0.45f)
        drawRect(scrim, Offset(0f, 0f), Size(w, t))
        drawRect(scrim, Offset(0f, b), Size(w, h - b))
        drawRect(scrim, Offset(0f, t), Size(l, b - t))
        drawRect(scrim, Offset(r, t), Size(w - r, b - t))
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(l, t),
            size = Size(side, side),
            cornerRadius = CornerRadius(16.dp.toPx()),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

/** 把 CameraX 的预览 + 分析绑定到 Activity 生命周期 */
private fun bindCamera(
    context: Context,
    previewView: PreviewView,
    holder: ScanCameraHolder,
    handled: AtomicBoolean,
    onCameraReady: (Camera) -> Unit,
    onResult: (String) -> Unit,
) {
    val owner = context.findLifecycleOwner()
    if (owner == null) {
        Log.w(TAG, "找不到 LifecycleOwner，无法绑定相机")
        return
    }
    val mainExecutor = ContextCompat.getMainExecutor(context)
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        if (holder.released.get()) return@addListener
        try {
            val provider = future.get()
            holder.provider = provider
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(holder.executor) { proxy ->
                analyzeFrame(proxy, holder.scanner, handled, mainExecutor, onResult)
            }
            provider.unbindAll()
            val cam = provider.bindToLifecycle(
                owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
            holder.camera = cam
            onCameraReady(cam)
        } catch (e: Exception) {
            Log.w(TAG, "相机绑定失败", e)
        }
    }, mainExecutor)
}

/** 单帧识别：无论成功/失败/异常都必须 close ImageProxy，否则后续帧会停住 */
private fun analyzeFrame(
    proxy: ImageProxy,
    scanner: BarcodeScanner,
    handled: AtomicBoolean,
    mainExecutor: Executor,
    onResult: (String) -> Unit,
) {
    if (handled.get() || proxy.image == null) {
        proxy.close()
        return
    }
    try {
        val image = InputImage.fromMediaImage(
            proxy.image!!, proxy.imageInfo.rotationDegrees
        )
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (handled.get()) return@addOnSuccessListener
                val raw = barcodes.firstOrNull()?.rawValue
                if (raw.isNullOrEmpty()) return@addOnSuccessListener
                val code = parseScanned(raw)
                if (code.isNotEmpty() && handled.compareAndSet(false, true)) {
                    mainExecutor.execute { onResult(code) }
                }
            }
            .addOnFailureListener { e -> Log.w(TAG, "二维码识别失败", e) }
            .addOnCompleteListener { proxy.close() }
    } catch (e: Exception) {
        Log.w(TAG, "二维码分析异常", e)
        proxy.close()
    }
}

/** Context 可能是 ContextWrapper（主题/设备上下文），逐层找出 ComponentActivity 这个 LifecycleOwner */
private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c != null) {
        if (c is LifecycleOwner) return c
        c = if (c is ContextWrapper) c.baseContext else null
    }
    return null
}

/** 把相机资源集中持有，页面退出时一次性解绑 / 关闭，避免重复绑定和帧回调泄漏 */
private class ScanCameraHolder {
    val executor: ExecutorService = Executors.newSingleThreadExecutor()
    val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )
    val released = AtomicBoolean(false)
    var provider: ProcessCameraProvider? = null
    var camera: Camera? = null

    fun release() {
        if (!released.compareAndSet(false, true)) return
        try { provider?.unbindAll() } catch (e: Exception) { Log.w(TAG, "解绑相机失败", e) }
        try { scanner.close() } catch (e: Exception) { Log.w(TAG, "关闭识别器失败", e) }
        executor.shutdown()
        provider = null
        camera = null
    }
}

# WebView 桥接与反射无关，当前无需要保留的规则；okhttp 自带 consumer 规则。
# 后续引入需要反射的库（如 kotlinx.serialization）时在这里补 keep。

# 原生扫一扫：ML Kit 条码模型通过 JNI/反射加载，release 开 R8 后若被裁掉会运行时报
# 「Barcode scanner internal error」，debug 不复现。AAR 自带 consumer 规则，这里再兜一层。
-keep class com.google.mlkit.vision.barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-dontwarn com.google.android.gms.internal.mlkit_vision_barcode.**

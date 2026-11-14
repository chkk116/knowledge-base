plugins {
    kotlin("jvm") version "2.4.20"
}

group = "com.kb.ocr"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

// 默认用桌面 JVM 版跑 CI / 对齐测试。传入 -Pkb.ort.androidJar=<aar 解出的 classes.jar>
// 即可用 Android 版 API 重新编译核心代码，验证「换依赖即可上真机」。
val ortAndroidJar = providers.gradleProperty("kb.ort.androidJar")
dependencies {
    if (ortAndroidJar.isPresent) {
        implementation(files(ortAndroidJar.get()))
    } else {
        implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    }
}

// 27 张黄金样本对齐测试（纯 JVM，加载同一份 ONNX 模型，无需 Android 真机）
tasks.register<JavaExec>("runAlignment") {
    group = "verification"
    description = "在 27 张黄金样本上对比 Kotlin 运行时与 Python/Web 的字符准确率"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.kb.ocr.AlignMainKt") // Kotlin 顶层 main 编译为 <FileName>Kt
    workingDir = rootProject.projectDir
    jvmArgs("-Dfile.encoding=UTF-8") // 中文路径/字符表在 JVM 间传递
    // 数据集与模型位于仓库上层目录
    systemProperty("kb.ocr.dataset", rootProject.projectDir.resolve("../../outputs/ocr-bench/dataset").absolutePath)
    systemProperty("kb.ocr.models", rootProject.projectDir.resolve("../web/models").absolutePath)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

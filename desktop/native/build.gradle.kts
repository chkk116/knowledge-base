import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.13.0-beta02"
}

repositories {
    // 本机连不上 dl.google.com（androidx 系依赖只能从 Google Maven 拉）→ 阿里云镜像优先
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    mavenCentral()
    google()
}

dependencies {
    implementation("org.jetbrains.compose.ui:ui:1.13.0-beta02")
    implementation("org.jetbrains.compose.foundation:foundation:1.13.0-beta02")
    implementation("org.jetbrains.compose.material3:material3:1.5.0-beta02")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // 下一步接入编辑器内嵌区时启用（Windows 走系统 WebView2，免分发运行时）：
    // implementation("io.github.kdroidfilter:composewebview:1.0.0-beta-02")
}

kotlin {
    jvmToolchain(21)
}

compose.desktop {
    application {
        mainClass = "com.kb.desktop.MainKt"

        nativeDistributions {
            // Windows 专用；出安装包需要 jpackage（JDK 自带）+ WiX
            targetFormats(TargetFormat.Exe)
            packageName = "kb-desktop"
            packageVersion = "1.0.0"
        }
    }
}

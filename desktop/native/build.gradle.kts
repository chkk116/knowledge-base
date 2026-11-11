import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.13.0-beta02"
}

// 打包链上的原生工具（jlink/jpackage/WiX candle、light）在本机处理不了含中文的路径，
// 构建产物统一放纯 ASCII 目录（源码与项目目录不动，构建输出重定向）
layout.buildDirectory.set(file("C:/chkk/tools/kbbuild/native"))

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
    // 阅读视图 WebView（Windows 走系统自带 WebView2 运行时，免分发 CEF）：
    implementation("io.github.kdroidfilter:composewebview:1.0.0-beta-02")
}

kotlin {
    jvmToolchain(21)
}

compose.desktop {
    application {
        mainClass = "com.kb.desktop.MainKt"

        nativeDistributions {
            // Windows 专用；安装包走 jpackage（JDK 自带）+ 插件自动管理的 WiX
            targetFormats(TargetFormat.Exe)
            packageName = "kb-desktop"
            packageVersion = "1.0.2"
            vendor = "kb"

            windows {
                menu = true
                menuGroup = "知识库"
                shortcut = true
                perUserInstall = true
                dirChooser = false
            }
        }
    }
}

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.13.0-beta02"
}

// 打包链上的原生工具（jlink/jpackage/WiX candle、light）在本机处理不了含中文的路径，
// 构建产物统一放纯 ASCII 目录（源码与项目目录不动，构建输出重定向）
layout.buildDirectory.set(file("C:/chkk/tools/kbbuild/native"))

// 版本号唯一真源：AppInfo.kt 的 versionName（jpackage 与 Inno 安装包共用，避免两处漂移）
val kbAppVersion = Regex("versionName[\\w\\s:]*=\\s*\"([^\"]+)\"")
    .find(file("src/main/kotlin/com/kb/desktop/AppInfo.kt").readText())
    ?.groupValues?.get(1)
    ?: error("AppInfo.kt 中未找到 versionName 常量")

repositories {
    // 本机连不上 dl.google.com（androidx 系依赖只能从 Google Maven 拉）→ 阿里云镜像优先
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    mavenCentral()
    google()
}

dependencies {
    implementation("org.jetbrains.compose.ui:ui:1.13.0-beta02")
    implementation("org.jetbrains.compose.foundation:foundation:1.13.0-beta02")
    implementation("org.jetbrains.compose.material3:material3:1.13.0-beta02")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // 阅读视图 WebView（Windows 走系统自带 WebView2 运行时，免分发 CEF）：
    implementation("io.github.kdroidfilter:composewebview:1.0.0-beta-02")
    // 协同正文本地解析：Yrs（Yjs 的 Rust 实现）Java 绑定，jar 内含 windows/linux/macos 原生库，
    // Apache-2.0。用于把 kb_shared_document.yjs_state 还原成 HTML（桌面端只读，不做编辑）。
    implementation("net.carcdr:ycrdt-core:0.3.0")
    implementation("net.carcdr:ycrdt-jni:0.3.0")
}

kotlin {
    jvmToolchain(21)
}

// 出厂默认服务器地址：正式发版传 -PKB_DESKTOP_SERVER=https://<部署域名> 注入到内置资源，
// 真实地址只存在于发版命令/云端配置，仓库里只有占位（与 Android 的 -PKB_BASE_URL 同一套做法）。
val kbDesktopServer = (findProperty("KB_DESKTOP_SERVER") as? String)?.trim().orEmpty()
val kbServerRes = layout.buildDirectory.dir("generated/kbServerRes")
val generateKbServer by tasks.registering {
    // 声明为输入：先注入再不注入时也必须重跑，避免残留上一次的真实地址
    inputs.property("kbDesktopServer", kbDesktopServer)
    outputs.dir(kbServerRes)
    doLast {
        val d = kbServerRes.get().asFile
        d.mkdirs()
        d.resolve("kb-default-server.txt").writeText(kbDesktopServer)
    }
}
tasks.named<ProcessResources>("processResources") {
    from(kbServerRes)
    dependsOn(generateKbServer)
}

// Agent 内容生成纯函数回归测试：gradle runAgentSmoke
tasks.register<JavaExec>("runAgentSmoke") {
    group = "verification"
    description = "验证 mdToHtml / 流程图图谱生成 / 图块 HTML 格式"
    dependsOn(tasks.named("compileTestKotlin"))
    classpath = sourceSets.getByName("test").runtimeClasspath
    mainClass.set("com.kb.desktop.AgentContentSmokeKt")
}

compose.desktop {
    application {
        mainClass = "com.kb.desktop.MainKt"

        nativeDistributions {
            // Windows 专用；安装包主推 Inno Setup（见下方 packageInnoSetup 任务），jpackage Exe 作兜底
            targetFormats(TargetFormat.Exe)
            packageName = "kb-desktop"
            packageVersion = kbAppVersion
            vendor = "kb"
            // ⚠️ 打包的运行时镜像必须显式带上 HTTP 客户端与 EC 加密模块：
            // 否则 HTTPS 请求会 ClassNotFoundException: java.net.http.HttpClient
            // （1.0.0/1.0.1/1.0.2 登录、更新失败的根因；开发用完整 JDK 所以不暴露）
            modules("java.net.http", "jdk.crypto.ec")

            windows {
                menu = true
                menuGroup = "知识库"
                shortcut = true
                perUserInstall = true
                // 兜底安装器也规范化：允许用户选择安装位置
                dirChooser = true
                // 固定升级 UUID：MSI 覆盖升级时识别为同一产品（与 Inno AppId 同一个标识）
                upgradeUuid = "B919BD52-4E07-45B2-AE2A-6BE675D2429F"
            }
        }
    }
}

// Inno Setup 安装程序（主推）：中文向导/安装位置可选/开始菜单卸载快捷方式/完成页立即运行。
// 前置：Inno Setup 6 便携安装于 C:/chkk/tools/inno6，
// 且 packaging/ChineseSimplified.isl 已复制到 C:/chkk/tools/inno6/Languages/。
tasks.register<Exec>("packageInnoSetup") {
    group = "compose desktop"
    description = "用 Inno Setup 把 app-image 打成中文安装程序（kb-desktop-x.y.z-setup.exe）"
    dependsOn("createDistributable")
    val iss = file("packaging/kb-desktop.iss")
    doFirst {
        file("C:/chkk/tools/kbbuild/native/inno").mkdirs()
    }
    commandLine("C:/chkk/tools/inno6/ISCC.exe", "/DAppVer=$kbAppVersion", iss.absolutePath)
}

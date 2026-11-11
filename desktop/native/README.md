# 知识库桌面端（原生 Compose）

`knowledge-base/desktop/native/` — 用 **Kotlin + Compose Multiplatform Desktop** 写的原生 Windows 客户端骨架（非 WebView 套壳）。`desktop/` 上一层的 Electron 版保留不动。

## 已实现（骨架 v1）

- 登录窗：账号密码 + 滑块验证码，与 Web 端同一套接口
  （`GET /api/auth/captcha` → `POST /api/auth/captcha/slide` → `POST /api/auth/login`）
- 主窗：左侧原生侧栏（首页/文章/AI 问答/我的文件/设置）+ WPS 风白底卡内容区
- **服务器地址不进界面**（用户要求）：读 `%APPDATA%\kb-desktop\config.json` 的 `server` 字段
  （或环境变量 `KB_DESKTOP_SERVER`），默认 `http://localhost:8080`；token 不落盘

## 本机运行

需要 JDK 21（后端同款 `~/.jdks/ms-21.0.12`）+ Gradle 8.14.3（`C:\chkk\tools\gradle-8.14.3`，已装）。

```powershell
$env:JAVA_HOME="C:\Users\Administrator\.jdks\ms-21.0.12"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
cd C:\chkk\知识库\knowledge-base\desktop\native
C:\chkk\tools\gradle-8.14.3\bin\gradle.bat run          # 开发运行
C:\chkk\tools\gradle-8.14.3\bin\gradle.bat build        # 编译校验
$env:KB_SMOKE="1"; gradle.bat run                       # 冒烟：启动 5 秒自动退出
```

## 技术锚点

- Compose Multiplatform **1.13.0-beta02**（2026-11，用户拍板用 beta）+ Kotlin **2.4.20** + Gradle 8.14.3，JVM 工具链 21
- 打包：Gradle `compose.desktop` 插件 `packageDistributionForCurrentOS`（jpackage，Exe 目标；MSI 需 WiX）
- 编辑器内嵌（下一步）：`io.github.kdroidfilter:composewebview:1.0.0-beta-02`，
  Windows 走系统自带 WebView2 运行时（Win10+ 基本都有），不需要分发 CEF 二进制

## 约定

- 仓库默认值不写私人域名/密钥；换服务器一律用登录框或 `KB_DESKTOP_SERVER` 环境变量
- 版本号独立于 Web 端（`package.json`/`changelog.js` 是 Web 的，别混）

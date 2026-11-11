# 知识库桌面端（原生 Compose）

`knowledge-base/desktop/native/` — 用 **Kotlin + Compose Multiplatform Desktop** 写的原生 Windows 客户端骨架（非 WebView 套壳）。`desktop/` 上一层的 Electron 版保留不动。

## 已实现（骨架 v1 + 文章/文件）

- 登录窗：账号密码 + 滑块验证码，与 Web 端同一套接口
  （`GET /api/auth/captcha` → `POST /api/auth/captcha/slide` → `POST /api/auth/login`）
- 主窗：左侧原生侧栏（首页/文章/AI 问答/我的文件/设置）+ WPS 风白底卡内容区
- 窗口最小尺寸随会话切换（登录 400×480 / 主界面 960×620）；窗口 <880dp 时侧栏收成图标栏
- **文章页**：左侧原生列表（`GET /api/docs`，字段与 Web 端一致）+ 右侧 WebView2 阅读视图
  （详情 content 为服务端净化过的 HTML，正文注入 `<base href>` 使 `/uploads` 图片可达；
  进入面板先检测系统 WebView2 运行时，缺失时显示安装提示）
- **我的文件**：原生列表（`GET /api/files/mine`），点击在系统浏览器打开
- **服务器地址不进界面**（用户要求）：读 `%APPDATA%\kb-desktop\config.json` 的 `server` 字段
  （或环境变量 `KB_DESKTOP_SERVER`），默认 `http://localhost:8080`；token 不落盘
- **打包**（jpackage 双产物，已实测）：免安装自包含目录（捆绑 JRE，冒烟 exit 0）+
  Exe 安装包（按用户安装、开始菜单组「知识库」+ 桌面快捷方式；安装/卸载零残留）

## 本机运行

需要 JDK 21（后端同款 `~/.jdks/ms-21.0.12`）+ Gradle 8.14.3（`C:\chkk\tools\gradle-8.14.3`，已装）。

```powershell
$env:JAVA_HOME="C:\Users\Administrator\.jdks\ms-21.0.12"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
cd C:\chkk\知识库\knowledge-base\desktop\native
C:\chkk\tools\gradle-8.14.3\bin\gradle.bat run          # 开发运行
C:\chkk\tools\gradle-8.14.3\bin\gradle.bat build        # 编译校验
$env:KB_SMOKE="1"; gradle.bat run                       # 冒烟：启动 5 秒自动退出
C:\chkk\tools\gradle-8.14.3\bin\gradle.bat createDistributable   # 免安装自包含目录（捆绑 JRE）
C:\chkk\tools\gradle-8.14.3\bin\gradle.bat packageExe            # Exe 安装包
# 产物在 C:\chkk\tools\kbbuild\native\compose\binaries\main\{app,exe}\
```

## 技术锚点

- Compose Multiplatform **1.13.0-beta02**（2026-11，用户拍板用 beta）+ Kotlin **2.4.20** + Gradle 8.14.3，JVM 工具链 21
- 打包：`createDistributable`（免 WiX，出 app/kb-desktop 自包含目录）+ `packageExe`
  （Exe 安装包，jpackage + 插件自动管理的 WiX 3.11.2，zip 缓存在 `~/.gradle/compose-jb/wix311.zip`）
- **构建目录重定向**（关键坑）：jlink/jpackage/WiX 在本机处理不了含中文的路径——
  jlink 对 `C:\chkk\知识库\...` 静默失败（exit 0 但不产出目录，Gradle 还会记住"无输出"从此永远
  UP-TO-DATE）；`build.gradle.kts` 已把构建输出指到 `C:/chkk/tools/kbbuild/native`，源码不动。
  若换机器重建：插件要下 WiX 时 GitHub 直连会被重置，可先用代理预埋 zip 到缓存位，
  或设 `WIX_PATH` 环境变量指向现成 WiX 目录
- 应用图标暂用插件默认 .ico（TODO：出正式 .ico）
- 编辑器/阅读区内嵌：`io.github.kdroidfilter:composewebview:1.0.0-beta-02`（已接入，阅读视图用），
  桌面端是 wry 绑定（Tauri 系），Windows 走系统自带 WebView2 运行时，不需要分发 CEF；
  注意桌面端 `loadHtml` 会忽略 baseUrl 参数，相对路径要靠 HTML 里的 `<base>` 标签

## 约定

- 仓库默认值不写私人域名/密钥；换服务器一律用登录框或 `KB_DESKTOP_SERVER` 环境变量
- 版本号独立于 Web 端（`package.json`/`changelog.js` 是 Web 的，别混）

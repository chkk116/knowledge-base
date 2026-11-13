# 知识库桌面端（原生 Compose）

`knowledge-base/desktop/native/` — 用 **Kotlin + Compose Multiplatform Desktop** 写的原生 Windows 客户端（非 WebView 套壳），交付形态为单文件安装程序。`desktop/` 上一层的 Electron 版保留不动。

## 已实现（1.0.6）

- 登录窗：账号密码 + 滑块验证码，与 Web 端同一套接口
  （`GET /api/auth/captcha` → `POST /api/auth/captcha/slide` → `POST /api/auth/login`）
- 主窗：左侧原生侧栏（首页/文章/AI 问答/我的文件/设置）+ WPS 风白底卡内容区
- 窗口：登录初始 960×640（下限 640×540）/ 主界面 1280×800（下限 960×620）；窗口 <880dp 时侧栏收成图标栏
- **登录页**：左品牌渐变栏（黛青 + 月白 + 金点缀，产品名 / 卖点）+ 右表单；品牌瓦片 →「欢迎回来」→ 无标签大输入框
  （44 高/10 圆角/聚焦品牌描边）→ 墨色实心按钮；autofocus、Enter 提交、用户名预填上次账号；
  验证码为「安全验证」卡浮层（标题 + 关闭×，舞台右上角换一张圆钮，药丸滑条）
- **文章页**：左侧原生列表（`GET /api/docs`，字段与 Web 端一致）+ 右侧 WebView2 阅读视图
  （详情 content 为服务端净化过的 HTML，正文注入 `<base href>` 使 `/uploads` 图片可达；
  进入面板先检测系统 WebView2 运行时，缺失时显示安装提示）
- **我的文件**：原生列表（`GET /api/files/mine`），点击在系统浏览器打开
- **AI 问答**：左侧会话列表（`GET/POST/DELETE /api/ai/conversations`）+ 右侧流式对话
  （`POST /api/ai/ask/stream`，SSE 逐段 `{"delta"}/{"reasoning"}`，`done` 事件带 qaId/sources/conversationId；
  「停止」断开连接、已生成内容保留，口径与 Web 端 ai.js 一致）；
  知识库/通用双模式、模型下拉（`GET /api/ai/models`，scope=user 的走 `userModelId`）；
  回答用轻量 markdown 渲染（标题/列表/代码块/粗体/行内码/链接文本），「引用来源」点击跳文章页打开对应文章；
  思考过程折叠展示；当前无会话时先建会话（标题取问题前 24 字）再提问；
  尚未做：Agent 模式（Web 端是前端本地引擎 utils/localAgent 的 ReAct 编排，移植是千行级工程，暂缓）
- **图片提问（vision）**：输入框 📎 选图（AWT FileDialog 多选，Windows 不认扩展名过滤器，
  按可解码性丢弃非图片）→ 本地压缩对齐 Web 端 compressImage（最长边 1280 / JPEG 0.82 / 白底防透明变黑）
  → dataURL 随 `POST /api/ai/ask/stream` 的 `images` 参数发送（后端硬限 ≤4 张、单张解码 ≤2MB）；
  纯图无文字自动补「请识别并说明这些图片的内容」；附件条可缩略图预览/✕ 移除；用户气泡内渲染缩略图；
  与 Web 一致：图片只在当次可见（历史消息不落盘），识文字/OCR 与 PDF/Office 解析未做（Web 端专用）
- **AI 反馈与会话重命名**：回答下方「👍 有帮助 / 👎 没帮助」（`POST /api/ai/feedback`，
  rating 1=赞 -1=踩 **0=取消**，同一键再点一次即取消，与 Web 端一致）；仅本次会话新产生的回答可评
  （历史消息接口不回传 qaId，与 Web 端口径一致）；反馈失败不回滚 UI；
  会话列表行内重命名（✎ 进入编辑，Enter 提交 Esc 取消，`PUT /api/ai/conversations/{id} {title}`）
- **服务器地址不进界面**（用户要求）：侧栏用户名下、页面头、设置「关于」、登录页品牌栏共 4 处均不显示域名；
  地址只读 `%APPDATA%\kb-desktop\config.json` 的 `server` 字段（或 `KB_DESKTOP_SERVER` 环境变量），
  默认值见 `ConfigStore.kt`（正式发版按部署目标替换）；token 不落盘
- **打包**（已实测）：`createDistributable` 出开发态自包含目录（捆绑 JRE，仅开发验证用）；
  `packageInnoSetup` 出**单文件中文安装程序** `kb-desktop-<版本>-setup.exe`（Inno Setup 6，可选安装位置、
  开始菜单组「知识库」+ 桌面快捷方式、固定 AppId 支持覆盖升级）；打包前需先结束占用 app-image 的
  `kb-desktop.exe` 进程，否则 Gradle 删不掉目录

## 更新渠道

- 服务端通道：`GET /api/public/app-version?channel=desktop`（后端白名单 `APP_VERSION_CHANNELS` 已登记 `desktop`），
  数据源 `kb_sys_setting` 的 `app_version_desktop`（JSON：`versionCode/versionName/url/notes/checksum/force`）。
- 客户端：启动后静默拉取该接口，`versionCode` 大于本地 `AppInfo.versionCode` 时顶部提示；
  「设置」页可手动检查更新，发现新版本给出更新说明与「下载更新」（浏览器打开安装包地址）。
- 发版流程：改 `AppInfo.versionCode/versionName`（`packageVersion` 从它读取，勿再单独改一处）→
  `packageInnoSetup` → 上传 setup.exe 到服务器 → 更新 `app_version_desktop` 的
  `versionCode/url/checksum`（**新包必须重算 SHA-256**，旧值会让更新校验失败）。

## 本机运行

需要 JDK 21（后端同款 `~/.jdks/ms-21.0.12`）+ Gradle 8.14.3（`C:\chkk\tools\gradle-8.14.3`，已装）。

```powershell
$env:JAVA_HOME="C:\Users\Administrator\.jdks\ms-21.0.12"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
cd C:\chkk\知识库\knowledge-base\desktop\native
C:\chkk\tools\gradle-8.14.3\bin\gradle.bat run          # 开发运行
C:\chkk\tools\gradle-8.14.3\bin\gradle.bat build        # 编译校验
$env:KB_SMOKE="1"; gradle.bat run                       # 冒烟：启动 5 秒自动退出
C:\chkk\tools\gradle-8.14.3\bin\gradle.bat createDistributable   # 开发态自包含目录（捆绑 JRE）
C:\chkk\tools\gradle-8.14.3\bin\gradle.bat packageInnoSetup       # 单文件中文安装程序（需 Inno Setup 6 便携版）
C:\chkk\tools\gradle-8.14.3\bin\gradle.bat packageExe            # 兜底：jpackage Exe 安装包
# 产物：C:\chkk\tools\kbbuild\native\compose\binaries\main\{app,exe}\ 与 ...\native\inno\*-setup.exe
```

## 技术锚点

- Compose Multiplatform **1.13.0-beta02**（2026-11）+ Kotlin **2.4.20** + Gradle 8.14.3，JVM 工具链 21
  ⚠️ `ui` / `foundation` / `material3` **必须同版本**：曾把 material3 写成 1.5.0-beta02，
  它拖进旧 `material-ripple`（CommonRipple 是普通 `Indication`），而 1.13 的 `clickable` 要求
  `IndicationNodeFactory`，导致进主界面组合期崩溃；对齐到 1.13.0-beta02 后修复
- 打包：`createDistributable`（出 app/kb-desktop 自包含目录）+ `packageInnoSetup`
  （单文件安装程序，Inno Setup 6 + `packaging/ChineseSimplified.isl`）；`packageExe` 作兜底
  （jpackage + 插件自动管理的 WiX 3.11.2，zip 缓存在 `~/.gradle/compose-jb/wix311.zip`）
- **构建目录重定向**（关键坑）：jlink/jpackage/WiX 在本机处理不了含中文的路径——
  jlink 对 `C:\chkk\知识库\...` 静默失败（exit 0 但不产出目录，Gradle 还会记住"无输出"从此永远
  UP-TO-DATE）；`build.gradle.kts` 已把构建输出指到 `C:/chkk/tools/kbbuild/native`，源码不动。
  若换机器重建：插件要下 WiX 时 GitHub 直连会被重置，可先用代理预埋 zip 到缓存位，
  或设 `WIX_PATH` 环境变量指向现成 WiX 目录
- **应用图标（1.0.5 起）**：`packaging/kb-desktop.ico`（由 `frontend/public/icons/icon-192.png` 生成，
  全 BMP 条目 192/128/64/48/32/16）用于安装器本体 / 快捷方式 / 控制面板卸载入口（iss 三处 +
  `{app}\kb-desktop.ico`）；exe 内嵌图标受 CMP 插件限制（1.13 windows DSL 无 iconFile）仍是默认，
  运行时窗口/任务栏图标由 `Main.kt` 的 `Window(icon=…)` 提供：`Icon.kt` 的 `loadWindowIcon()`
  从 classpath `kb-icon.png`（= icon-192 副本）经 `loadImageBitmap` 建 `Painter` 自绘，失败回退默认
- 编辑器/阅读区内嵌：`io.github.kdroidfilter:composewebview:1.0.0-beta-02`（已接入，阅读视图用），
  桌面端是 wry 绑定（Tauri 系），Windows 走系统自带 WebView2 运行时，不需要分发 CEF；
  注意桌面端 `loadHtml` 会忽略 baseUrl 参数，相对路径要靠 HTML 里的 `<base>` 标签

## 约定

- 默认服务器地址见 `ConfigStore.kt`；本地/换环境用 `KB_DESKTOP_SERVER` 环境变量或 config.json 的 server 覆盖
- 版本号独立于 Web 端（`package.json`/`changelog.js` 是 Web 的，别混）

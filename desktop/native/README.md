# 知识库桌面端（原生 Compose）

`knowledge-base/desktop/native/` — 用 **Kotlin + Compose Multiplatform Desktop** 写的原生 Windows 客户端（非 WebView 套壳），交付形态为单文件安装程序。`desktop/` 上一层的 Electron 版保留不动。

## 已实现（1.0.8）

- 登录窗：账号密码 + 滑块验证码，与 Web 端同一套接口
  （`GET /api/auth/captcha` → `POST /api/auth/captcha/slide` → `POST /api/auth/login`）
- 主窗：左侧原生侧栏（首页/文章/AI 问答/我的文件/设置）+ WPS 风白底卡内容区
- 窗口：登录初始 960×640（下限 640×540）/ 主界面 1280×800（下限 960×620）；窗口 <880dp 时侧栏收成图标栏
- **登录页**：左品牌渐变栏（黛青 + 月白 + 金点缀，产品名 / 卖点）+ 右表单；品牌瓦片 →「欢迎回来」→ 无标签大输入框
  （44 高/10 圆角/聚焦品牌描边）→ 墨色实心按钮；autofocus、Enter 提交、用户名预填上次账号；
  验证码为「安全验证」卡浮层（标题 + 关闭×，舞台右上角换一张圆钮，药丸滑条）
- **文章页**：左侧原生列表（`GET /api/docs`，字段与 Web 端一致）+ 右侧**原生阅读页**
  （2026-11-13 起不再走 WebView，布局对齐 Web 端 PostDetail 三栏）：
  左操作栏（点赞/收藏/评论计数/返回顶部，`POST /api/likes|favorites/{id}` 切换、乐观更新失败回滚）；
  中栏面包屑（`GET /api/docs/{id}/breadcrumb`，点击跳转）→ 大标题 → 元信息（分类/标签/作者/更新时间/浏览/
  非 PUBLISHED 状态徽标）→ 编辑按钮（仅管理员/作者，`GET /api/auth/me` 判定）→ 正文 →
  评论区（`GET/POST /api/docs/{id}/comments` 列表与发表、`POST /api/comments/{id}/like` 赞、
  自己的评论可两击删除、**可回复**：点「回复」显示「回复 @xxx」提示条并聚焦输入框，
  回复统一挂到顶层评论最多两层，与 Web 端一致；Ctrl/Enter 发送）；
  右侧目录（真实 h1~h6 + 编号段落推断，点击平滑定位，窄窗口自动收起）；
  正文渲染在 `DocHtml.kt`：JDK 自带 ParserDelegator 解析为块（标题/段落/列表/代码块/引用/表格/图片/分隔线），
  行内粗体/斜体/删除线/行内码/链接（站内与 http(s) 白名单，点击开系统浏览器），站内图片走 `/api/thumb`
  缩略图接口（宽度吸附 800 档）+ 内存缓存；新建/编辑仍用 Web 端 SPA 编辑器（WebView2，仅编辑时检测运行时）
- **我的文件**：原生列表（`GET /api/files/mine`），点击在系统浏览器打开
- **AI 问答**：左侧会话列表（`GET/POST/DELETE /api/ai/conversations`）+ 右侧流式对话
  （`POST /api/ai/ask/stream`，SSE 逐段 `{"delta"}/{"reasoning"}`，`done` 事件带 qaId/sources/conversationId；
  「停止」断开连接、已生成内容保留，口径与 Web 端 ai.js 一致）；
  知识库/通用/Agent 三模式、模型下拉（`GET /api/ai/models`，scope=user 的走 `userModelId`）；
  回答用轻量 markdown 渲染（标题/列表/代码块/粗体/行内码/链接文本），「引用来源」点击跳文章页打开对应文章；
  思考过程折叠展示；各模式空状态带示例问题（点击直接发问）；会话列表显示更新时间；当前无会话时先建会话（标题取问题前 24 字）再提问；
  **Agent 模式**（ReAct，第三个模式）：最多 6 步；每步走 `POST /api/ai/agent/step`（不落库，
  避免像 /ask 那样每步新建会话），工具在本地执行：`search_kb`（`GET /api/search?q=`）、
  `read_doc`（`GET /api/docs/{id}`）、`list_my_docs`（`GET /api/docs/mine`）、`now`、`navigate`、
  `create_doc`（`POST /api/docs`，Markdown 转 HTML，草稿/提交审核）、
  `create_flowchart`（模型给 nodes/edges，本地规范化去重并自动分层排版，生成 TipTap
  `flowchart-block` 图块写进共享文档，完成后在系统浏览器打开 Web 端画布）；
  步骤写进独立的「⚙ 已执行步骤」折叠区（执行中自动展开跟随、完成后收起可回看，不再混进思考过程），
  最终答案写正文并汇总引用来源；结束后 `POST /api/ai/agent/save` 落一轮问答。
  纯函数回归测试：`gradle runAgentSmoke`（校验 mdToHtml / 图谱去重过滤 / 图块 HTML 格式）
- **图片提问（vision）**：输入框 📎 选图（AWT FileDialog 多选，Windows 不认扩展名过滤器，
  按可解码性丢弃非图片）→ 本地压缩对齐 Web 端 compressImage（最长边 1280 / JPEG 0.82 / 白底防透明变黑）
  → dataURL 随 `POST /api/ai/ask/stream` 的 `images` 参数发送（后端硬限 ≤4 张、单张解码 ≤2MB）；
  纯图无文字自动补「请识别并说明这些图片的内容」；附件条可缩略图预览/✕ 移除；用户气泡内渲染缩略图；
  与 Web 一致：图片只在当次可见（历史消息不落盘），识文字/OCR 与 PDF/Office 解析未做（Web 端专用）
- **AI 问答界面（2026-11-13 美化）**：空状态按模式给不同标题/副标题 + 4 个示例问题 chips（点击即发）；
  模式药丸带图标（📚 知识库 / ✨ 通用 / 🤖 Agent）；📎 收进输入框尾部图标，占位文案随模式变化
  （Agent 模式提示「会调用工具完成」）；会话行标题下显示时间（同年省年份、跨年带年份）
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

## 待发版（代码已同步，安装包待下次发版）

### 1.0.9 加固

- **失败原因透出**：模型列表 / 会话列表 / 历史消息加载失败时保留真实原因（登录已过期、网络错误不再被统一替换成「响应异常」）。
- **发送失败兜底**：新建会话等在 AI 气泡出现之前就失败时，补一条错误气泡并还原输入与已选图片，问题不再凭空消失、界面不再毫无提示。
- **会话列表**：删除会话改为二次点击确认（首次点击按钮变「确认?」），避免误触连带删光历史；增删改的瞬时失败只在列表顶部显示小提示，不再把已有会话整列替换成错误页。
- **Agent**：工具执行不再吞掉协程取消异常，点「停止」能真正中断循环；打开 Web 画布前只放行 http/https 地址。

### 本轮：文章原生阅读 + AI 问答 UI 美化（未升版本号，发版时改 AppInfo → 1.0.10/code 11）

- **阅读视图去 WebView**：`readerHtml`/`BLANK_READER` 阅读链路删除，正文由 `DocHtml.kt` 原生解析渲染
  （JDK ParserDelegator → 块模型 → Compose），新增 `DocReader.kt`（ArticleView 三栏页：操作栏/正文/目录 + 评论区）；
  Api.kt 新增面包屑、点赞、收藏、评论（列表/发表/赞/删/我的赞集）等端点，DocDetail 补 likes/status/tags/ownerId/createdAt；
  WebView2 只剩编辑视图一个用途（缺运行时的提示也只在编辑时出现）。
- **踩坑记录**：ParserDelegator 回调是 **3 参签名**（JDK21 已无 2 参版）；`AttributeSet` 没有按取属性的方法，
  要用 `attributeNames` 枚举 + `getAttribute(name)`；`html/head/body` 不在块级标签集合时会把整篇文档
  拍平成一段（未知标签必须按容器下钻、head 整块跳过）；KDoc 注释里写 `/uploads/*` 会因 Kotlin 嵌套块注释
  报「未闭合注释」。
- **验证方式**：本地 mock（`%TEMP%\opencode\mock-ai.cjs`，18080）补齐文章全家桶与 `POST /api/ai/agent/step`
  脚本响应；临时桩 `KB_SMOKE_MAIN`（直进主界面）/ `KB_PANEL=chat|docs` / `KB_AUTODOC` / `KB_AUTOAGENT`
  截图验证后已全部还原；`%TEMP%\opencode\shot-win.ps1` 用 PrintWindow 抓自身窗口（不抢前台，
  用户机器上多实例并存时 KB_SHOT 区域截图会抓错窗口）。
- **未完成**：Agent 步骤「执行中展开」状态未截到图（mock 收尾延时窗口期没卡准，逻辑为
  `remember(streaming, steps.size)` 跑时展开、完成收起）；本包未打包未部署；评论楼中楼回复（发言带 parentId）
  桌面端只展示不发回复；文章页「订阅/相关文档/作者卡片/Word 导出」Web 端能力未移植。

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
- 编辑器内嵌：`io.github.kdroidfilter:composewebview:1.0.0-beta-02`（**2026-11-13 起仅编辑视图使用**，
  阅读已改原生 DocHtml/DocReader），桌面端是 wry 绑定（Tauri 系），Windows 走系统自带 WebView2 运行时，
  不需要分发 CEF；注意桌面端 `loadHtml` 会忽略 baseUrl 参数，相对路径要靠 HTML 里的 `<base>` 标签

## 约定

- 默认服务器地址见 `ConfigStore.kt`；本地/换环境用 `KB_DESKTOP_SERVER` 环境变量或 config.json 的 server 覆盖
- 版本号独立于 Web 端（`package.json`/`changelog.js` 是 Web 的，别混）

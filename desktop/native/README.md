# 知识库桌面端（原生 Compose）

`knowledge-base/desktop/native/` — 用 **Kotlin + Compose Multiplatform Desktop** 写的原生 Windows 客户端（非 WebView 套壳），交付形态为单文件安装程序。`desktop/` 上一层的 Electron 版保留不动。

## 已实现（1.0.8）

- 登录窗：账号密码 + 滑块验证码，与 Web 端同一套接口
  （`GET /api/auth/captcha` → `POST /api/auth/captcha/slide` → `POST /api/auth/login`）
- 主窗：左侧原生侧栏（首页/文章/共享空间/培训/考试/AI 问答/我的文件/管理/设置）+ WPS 风白底卡内容区
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
  默认值见 `ConfigStore.kt`（正式发版用 `-PKB_DESKTOP_SERVER` 注入，仓库内只留占位）；token 不落盘
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

### 1.1.0：文章原生阅读 + 评论回复 + AI 问答 UI 美化（已发版，versionCode 11）

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

### 1.1.2：首页工作台 / 文章搜索 / 文章页补齐 / 共享空间 / 培训考试 / 我的订阅（已打包安装，2026-11-16）

- **首页从「开发者自述」改为真实工作台**：时段问候 + 概览统计（`GET /api/docs/overview`：
  已发布文章 / 本周新增 / 在册成员，管理员额外显示待审核）+ 公告卡（`GET /api/announcements`，
  置顶用暖黄提醒、普通通知白底，最多两条）+「最近更新」列表（`GET /api/docs?size=6&sort=latest`，
  点击直接打开文章）+ 三个快捷入口（问 AI / 全部文章 / 我的文件）；各块独立降级，
  任一块取不到都不拖垮整页（不弹错、只留空）。
- **文章列表原生搜索**：搜索框输入即搜（350ms 防抖，`GET /api/search?q=`），清空即回服务端分页列表；
  新增「最新 / 最热」排序切换（`sort=latest|hot`）；搜索结果不显示 0 浏览量，
  标题右侧计数在搜索时切换为结果条数。
- **我的订阅列表**：文章列表面板顶部「全部 / 订阅」切换；订阅模式走
  `GET /api/docs/subscriptions`（标题/分类/订阅时间），文档已删除的条目由后端给占位标题并标
  `deleted=true` → 整行置灰、不可点击；订阅模式隐藏搜索与排序（列表短，避免误导）；
  点击条目直接打开对应文章详情（复用文章阅读页）。
- **文章页补齐 Web 端四项能力**：
  - **订阅**：左侧操作栏「🔔 订阅 / 已订阅 + 订阅人数」；`GET /api/docs/{id}/subscribed` 初始化，
    `POST/DELETE /api/docs/{id}/subscribe` 切换，乐观更新失败回滚，提示与 Web 端同文案。
  - **相关阅读**：正文之后、评论区之前最多 4 条（`GET /api/docs/{id}/related?limit=4`，点击打开）；
    渲染在「评论区那个 item」内部，保持 `头部 item + 正文块 + 评论` 的固定索引不被撑坏。
  - **作者卡片**：标题下方紧凑卡（`GET /api/users/{id}/card`）：头像首字、昵称、徽章、入驻年数、
    原创/获赞/粉丝/积分，右侧「+ 关注 / 已关注」（`POST/DELETE /api/users/{id}/follow`）；
    接口失败整卡不显示，不影响正文。
  - **Word 导出**：操作栏「⇩ 导出」，`GET /api/docs/{id}/export?format=word` 取后端 HTML 封装，
    落盘到临时目录同名 `.doc` 后交给系统默认程序（Word/WPS）打开；打不开时提示落盘绝对路径；
    无 `DOC_EXPORT` 权限时如实显示后端 403 文案（不吞成「导出失败」）。
- **共享空间（只读首版）**：侧栏新增「共享空间」，三列结构（我的空间 150dp → 空间内文档 204dp → 阅读区自适应）：
  `GET /api/doc-shares/my` 列空间、`GET /api/doc-shares/{id}/docs` 列文档（标题/作者/更新时间/占用字节）、
  `GET /api/doc-shares/{id}/docs/{docId}` 取正文后交给 `DocHtml` 原生渲染（标题/标签/我的权限/正文）；
  「在 Web 端打开」直达 `/doc-shares/{id}?doc={docId}`。
  **空间内检索与排序**：文档列表按标题/标签本地过滤（复用文章页的自绘搜索框，`SearchBox` 提为 internal），
  排序支持「最近更新 / 标题」，文档数随过滤结果变化；无匹配显示「没有匹配的文档」。
  ⚠️ **协同正文（2026-11-16 起本地解析）**：`GET /api/doc-shares/{id}/docs/{docId}/yjs` 下发
  `yjs_state`（base64；超 8MB 不下发，返回 `state=null` + `tooLarge`），桌面端用
  **`net.carcdr:ycrdt-core` / `ycrdt-jni` 0.3.0**（Yrs 的 Java 绑定，Apache-2.0，jar 内含
  windows/linux/macos 原生库）在本地还原成 HTML 再交给 `DocHtml`：
  - 结构：`Y.XmlFragment("default")` → `doc` → paragraph / heading(level) / bulletList / listItem /
    codeBlock / blockquote / table / image；行内格式在 **`YXmlText.getFormattingChunks()` 的 attrs**
    （bold / italic / strike / underline / code / link）——**不能 `toString()`**，它会把 marks 输出成
    `<bold>` 伪标签（已验证踩坑）。
  - 来源标注：解析成功显示「协同实时」（relay 每 **5s** 落库一次，`COLLAB_SAVE_INTERVAL`），
    否则显示「快照」并回退 `content`。
  - TipTap 自定义块（流程图/导图/表格块）原生端画不了 → 渲染「请在 Web 端查看」提示，不静默丢内容。
  - **生效条件**：后端新接口要部署（jar）+ 桌面端 1.1.3 起；旧后端配新客户端会自动回退快照。
- **管理模块（2026-11-16，日常运维闭环）**：侧栏「管理」进二级导航，7 个页签，权限全部由后端判定
  （ADMIN 或细粒度码），403 文案原样透出；列表统一取第一页 50 条（不做翻页）：
  - **审核队列**（`DOC_REVIEW`）：`GET /api/admin/docs/pending` + 通过（`POST /{id}/approve`）/
    驳回（`POST /{id}/reject`，理由必填且作者可见，行内输入框，二次确认式布局）。
  - **用户**（`USER_MANAGE`）：`GET /api/admin/users?keyword=` + 搜索 + 启用/禁用（`POST /{id}/toggle`）。
    **改角色 / 重置密码 / 调积分不做**（`PUT /{id}` 的字段语义会误清其它字段，风险不值），界面明确提示回 Web。
  - **部门**（`USER_MANAGE`）：`GET /api/admin/departments` 扁平列表 → 前端按 parentId 组树（防环、孤儿兜底），只读。
  - **公告**（`ANNOUNCEMENT_MANAGE`）：列表 + 新建（标题/内容/置顶）+ 置顶切换（`POST /{id}/pin`）+
    启用切换（`POST /{id}/enable`）；正文编辑回 Web（避免 PUT 全量覆盖）。
  - **看板**（`STATS_VIEW`）：`GET /api/admin/stats` 键值平铺，按中文标签映射成卡片。
  - **操作审计**（`AUDIT_VIEW`）/ **登录日志**（`LOGIN_LOG_VIEW`）：只读列表（最近 50 条）。
  - **阅卷**（`EXAM_MANAGE`）：待阅列表（`GET /api/admin/exams/grading/pending`，显示当前分/待阅题数）→
    逐题卡（题干与学生答案剥 HTML 显示，`pending=true` 的主观题填分数+评语，客观题只显示已评）→
    `POST /api/admin/exams/grading/{recordId}` 提交；分数校验 0~满分。
  - **举报**（`CORE_MAINTAIN`）：`GET /api/admin/reports?status=`（全部/待处理/已处理/已驳回）+
    处理（`PUT /{id}` status=HANDLED|REJECTED + 备注）。
  - **违禁词**（`BANNED_WORDS_MANAGE`）：左侧词库（内置标记 + 启用状态，可新建/启停）→ 右侧词条
    （`GET /api/admin/banned-words?libraryId=`）本地搜索、批量添加（逗号/换行分隔）、逐条删除（二次确认）。
  - **孤儿附件**（仅 ADMIN）：`GET /api/admin/files/orphans`（正文与头像都未引用的上传文件）+ 逐个删除
    （`DELETE /api/admin/files/orphan?url=`，二次确认；界面标红提示不可恢复）。
  - **分类标签**（`TAXONOMY_MANAGE`）：分类树（`GET /api/categories` 递归 children，左列缩进展示）+
    加根/加子类/重命名/删除（`POST|PUT|DELETE /api/admin/categories`，有子类时后端拒绝，文案透出）；
    标签（`GET /api/tags` 带文章计数）+ 搜索/新增/删除（`/api/admin/tags`）。
  - **回收站**（`POST_MANAGE`）：`GET /api/admin/docs/recycle` + 恢复（`POST /{id}/restore`）；
    彻底删除与批量操作留 Web（不可逆）。
  - **代码组织**：外壳与小件在 `AdminPanel.kt`（204 行），模块按域拆分——
    `AdminReviewPanel.kt`（审核/阅卷/举报）、`AdminPeoplePanel.kt`（用户/部门）、
    `AdminContentPanel.kt`（公告/违禁词/孤儿附件）、`AdminTaxonomyPanel.kt`（分类标签）、
    `AdminDocPanel.kt`（回收站）、`AdminInsightPanel.kt`（看板/审计/登录日志）。
- **培训 / 考试（浏览与进度）**：
  - 培训：`GET /api/courses`（分类 chips 横向滚动，`/categories` 取分类）→ 课程列表；
    中列显示 `GET /api/courses/{id}` 的章节与 `GET /api/courses/{id}/progress` 的完成状态
    （「已完成 1 / 3」+ 进度条 + 章节勾选）；右列用 `DocHtml` 渲染章节正文，未完成时「标记完成」、
    已完成时「✓ 已完成」+「重置进度」（`POST /api/courses/{id}/chapters/{cid}/finish|reset`）。
  - 考试：`GET /api/exams` 列表（题数 / 限时 / 及格线）+ 详情（描述 + 四个指标卡 + 我的成绩
    `GET /api/exams/mine` 按 paperId 过滤：分数/通过/待阅卷/时间/题数）。
  - ⚠️ **在线作答与练习仍回 Web 端**（限时、服务端会话、判分链路都在 Web + 服务端），
    面板里明确写出这一点，不提供半吊子的作答入口。
- **顺带修**：文章头部元信息由单行改两行（分类/标签一行；作者 / 更新时间 / 浏览 / 状态一行），
  原单行在窄中栏会把浏览数挤出可视区。
- **本轮复核加固**：管理面板的未知 tab 不再静默落到登录日志；状态 chip 超长显示省略号；
  后端 403 原文可点开看全（不再悄悄截断）；禁用按钮有独立灰底；时间列只解析标准 ISO，
  其它格式原样展示；清掉未使用的 import。
- 验证：`compileKotlin` 通过；本地 mock（`%TEMP%\codearts\mock-kb.cjs`，18081）+ 临时桩
  （`KB_SMOKE_MAIN=1` / `KB_PANEL=docs|shares` / `KB_AUTOSEARCH` / `KB_AUTODOC` / `KB_SCROLL_END`）截图确认：
  首页四张统计卡 + 两条公告 + 最近更新；文章页搜索「产品」；文章页操作栏五个按钮、作者卡片、
  相关阅读 4 条与评论区；共享空间三列（空间列表 / 文档列表 / 正文渲染）在 960×640 下无溢出、
  元信息两行完整；培训三列（课程 / 章节 + 进度 / 章节正文）与考试页（试卷 / 指标卡 / 我的成绩）
  布局正常；桩已全部还原，`KB_SMOKE` 冒烟 exit 0。
- 遗留（未做）：在线作答与练习、管理页的重配置项（用户改角色/重置密码/调积分、部门增删改、公告正文编辑、外观/SSO/AI 模型等）均走 Web。协同实时正文代码已完成，待部署后端 + 打 1.1.3 生效。
- **打包记录（2026-11-16）**：`gradle packageInnoSetup -PKB_DESKTOP_SERVER=https://your-domain.example`
  → `C:/chkk/tools/kbbuild/native/inno/kb-desktop-1.1.2-setup.exe`（55.8 MB）；
  app-image 内 `kb-desktop-native-*.jar!/kb-default-server.txt` 已核对为注入地址；
  本机 `/VERYSILENT` 覆盖安装后 exe 与 ARP 均为 1.1.2，`KB_SMOKE=1` 启动 exit 0。
  **未上传服务器、未更新 `app_version_desktop`** → 线上已装 1.1.1 的用户还收不到更新提示。

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

## 近期修复（2026-11-13）

- `ConfigStore` 的 config.json 读写改用 kotlinx.serialization 标准序列化（原手写正则解析在值含
  转义序列时会出错）；`AgentEngine` 的 TOOL: 行改括号配对提取首个完整 JSON（原行内贪婪正则遇到
  同行多余大括号会解析失败），`create_flowchart` 结果消息注明写入的共享空间名
- App 壳 `TokenStore` 改 AndroidKeyStore AES/GCM 加密持久化（enc1: 前缀，旧明文读取沿用、
  下次写入自动升级）；底部导航补 `navigationBarsPadding`，「文档库」Tab 补线面切换
- 主界面去掉外层 20dp + 白卡边框（框套框冗余），文章侧栏补浅底与会话侧栏一致；
  登录页保留书院风配色（用户拍板 2026-11-13）

## 约定

- 默认服务器地址见 `ConfigStore.kt`；本地/换环境用 `KB_DESKTOP_SERVER` 环境变量或 config.json 的 server 覆盖
- **正式发版必须注入地址**：`gradle packageInnoSetup -PKB_DESKTOP_SERVER=https://<部署域名>`，
  该值只存在于发版命令/云端配置，仓库内是示例占位；不注入打出的包会连到示例地址
- 版本号独立于 Web 端（`package.json`/`changelog.js` 是 Web 的，别混）

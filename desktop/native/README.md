# 知识库桌面端（原生 Compose）

`knowledge-base/desktop/native/` — 用 **Kotlin + Compose Multiplatform Desktop** 写的原生 Windows 客户端（非 WebView 套壳），交付形态为单文件安装程序。`desktop/` 上一层的 Electron 版保留不动。

## 已实现（1.0.8）

- 登录窗：账号密码 + 滑块验证码，与 Web 端同一套接口
  （`GET /api/auth/captcha` → `POST /api/auth/captcha/slide` → `POST /api/auth/login`）
- 主窗：左侧原生侧栏（首页/文章/共享空间/培训/考试/AI 问答/我的文件/邮箱/通知/管理/设置）+ WPS 风白底卡内容区
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
- **我的文件**：原生列表（`GET /api/files/mine`），点击在系统浏览器打开；**2026-11-17 补齐上传与删除** ——
  顶栏「上传」走系统文件选择器（`FileDialog`，需 `LocalAwtWindow` + `@OptIn(ExperimentalComposeUiApi)`）→
  `multipart POST /api/files/mine`（后端按 sha256 去重，同内容不会重复落盘）；每行「复制」把直链写系统剪贴板，
  「删除」需点两次（第一次变「确认删除?」）→ `DELETE /api/files/mine/{id}`。
  ⚠️ **上传类型受限（2026-11-18 真后端联调确认）**：`FileService.ALLOWED` 只收
  图片（png/jpg/jpeg/gif/webp/bmp）/ 音频（mp3/wav/ogg/m4a/flac/aac）/ 视频（mp4/webm）/ **PDF**，单文件 ≤ 50MB，
  且做**魔数嗅探**（`Content-Type` 只报 `application/octet-stream` 也会被拒）。
  因此本地先按扩展名拦一道并直接给出 MIME（`UPLOAD_EXT_MIME`），文案提示「仅支持图片 / 音频 / 视频 / PDF」——
  不要再用 `URLConnection.guessContentTypeFromName` 兜底成 octet-stream，那只会换来后端一句类型错误。
  **右侧预览（2026-11-17）**：左列表 320dp + 右预览区；图片（`contentType=image/*` 或常见扩展名）用
  `DocImage` 原生渲染，PDF/文本/其它交给内嵌 WebView 加载直链；点行选中即预览，行尾「打开」仍在系统浏览器打开。
- **邮箱（2026-11-18，跟随后端邮箱模块）**：侧栏「邮箱」→ 三栏（账号 / 收件箱 / 阅读）。
  账号 `email` 由服务端掩码；`GET /api/mail/messages` 返回 **对象** `{items,total,page,size}`（不是数组，别按数组解析）；
  阅读 `textBody` 优先，`htmlBody` 只做标签剥离转纯文本（不渲染远程 HTML，避免追踪像素与外链）；
  点开自动标已读、删除二次确认、`POST /accounts/{id}/sync` 手动同步并提示新增封数；
  **写邮件 / 回复 / 存草稿**（`POST /api/mail/send`、`/api/mail/drafts`；回复带原始 `messageId` 串线程）；
  收件箱 / 草稿箱 / 已发送（`folder=INBOX|DRAFT|SENT`；草稿带「【草稿】」前缀，选中后可再「编辑草稿」预填正文）；
  列表「加载更多」（80 封/页，按 id 去重合并）；**附件列表**（`attachments` 是嵌套 JSON 字符串，点名称走系统浏览器打开站内 `/uploads/…`）；
  写信时**收件人补全**（`/mail/contacts`，输入 2 字以上 300ms 防抖，接口未上线时静默返回空）；
  **账号签名**（左栏「签名」→ `PUT /mail/accounts/{id}/signature`，写信/存草稿时客户端自动追加）；
  后端 `/send` 未升级时把 404/405 说明成「服务端邮箱发信接口可能尚未升级」。
  **绑定只做授权码**（QQ/163 等），OAuth 需管理员先配 `mail.oauth.google.*` / `mail.oauth.ms.*`，回调 `/api/mail/oauth/callback`。
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
- **我的订阅列表**：文章列表面板顶部「全部 / 订阅 / **我的**」三档切换；订阅模式走
  `GET /api/docs/subscriptions`（标题/分类/订阅时间），文档已删除的条目由后端给占位标题并标
  `deleted=true` → 整行置灰、不可点击；订阅模式隐藏搜索与排序（列表短，避免误导）；
  点击条目直接打开对应文章详情（复用文章阅读页）。
- **我的帖子（作者视角，2026-11-17）**：第三档「我的」走 `GET /api/docs/mine`，行内有状态徽标
  （草稿/待审/已发布/已封禁）+ 更新时间 + 阅读量；草稿可「提交审核」（`POST /{id}/submit`，普通用户统一转待审）、
  待审可「撤回」（`POST /{id}/withdraw`）、任意可「删除」（`DELETE /api/docs/{id}`；与后台 admin 删除不是同一路径）；
  只有已发布/已封禁的行点得开阅读页，草稿待审点了不跳（避免看到自己未发布的内容被当成线上文章）。
- **文章页补齐 Web 端四项能力**：
  - **订阅**：左侧操作栏「🔔 订阅 / 已订阅 + 订阅人数」；`GET /api/docs/{id}/subscribed` 初始化，
    `POST/DELETE /api/docs/{id}/subscribe` 切换，乐观更新失败回滚，提示与 Web 端同文案。
  - **相关阅读**：正文之后、评论区之前最多 4 条（`GET /api/docs/{id}/related?limit=4`，点击打开）；
    渲染在「评论区那个 item」内部，保持 `头部 item + 正文块 + 评论` 的固定索引不被撑坏。
  - **评论管理（2026-11-17）**：自己的评论可「删除」（`DELETE /api/comments/{id}`，二次确认）；
    别人的评论可「举报」→ 六项原因（垃圾广告/辱骂攻击/违法违规/侵权内容/虚假信息/其它）+ 说明，
    提交 `POST /api/reports`（`targetType=COMMENT`，后端 10 分钟内同一对象只收一次）。
  - **举报文章**：左侧操作栏 `⚑ 举报` → 同款六项原因弹窗，提交 `POST /api/reports`（`targetType=POST`，
    管理员在「举报处理」里可下架或警告作者）。
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
  **跨空间「全部文档」（2026-11-17）**：左侧列表顶部固定一条「全部文档」，走 `GET /api/doc-shares/docs/all`
  列出所有空间里我可见的文档（后端有总量截断、默认不带全文）；列表项补「所属空间名」以便区分同名文档。
  该视图下详情/版本/「在 Web 端打开」都用文档自带的 `shareId`（`docShareId` 状态），而不是左侧选中的空间 id。
  **成员管理（2026-11-21）**：中间列标题栏「成员」→ 对话框（所有者 / 我的权限 / 已用·容量来自 `/my` 的瞬态字段）：
  `GET /{id}/members` 列表（权限 READ/WRITE/ADMIN）、`PUT …/members/{userId} {permission}` 三档切换、
  `DELETE …/members/{userId}` 移除、`GET /users?keyword=`（250ms 防抖）+ `POST …/members {userId,permission}` 邀请；
  只有 `myPermission` 为 OWNER/ADMIN 时显示增删改（后端仍二次校验），所有者行不可改。
  **外部分享链接（2026-11-21）**：阅读区顶栏「分享」→ `GET|POST|DELETE …/docs/{docId}/public-link`：
  未开启时「生成链接」，已开启时显示 `{server}/s/{token}`（复制 / 打开）+ 状态（是否密码 / 有效期 / 禁止复制 / 浏览数）；
  可设密码（留空=无密码）、有效期永久·7·30·90 天、允许/禁止复制；「更新设置」保持同 token，「撤销链接」后历史链接立即 404。
  查询/生成都要求能管理该文档（后端 `canAdminDoc`），失败时原文透出。
  **文档级权限（2026-11-21）**：阅读区顶栏「权限」→ `GET …/docs/{docId}/members`（空间成员 + 各自
  `spacePermission` / 有效 `permission` / `override`）→ 每行「只读 / 可编辑 / 可管理」三档 + 「继承」：
  `PUT …/members/{userId} {permission}` 传**与空间权限相同**的值即 `cleared=true`（自动回退继承，界面标「本篇单独设置」消失）；
  `DELETE …/members/{userId}` 直接恢复继承。同样仅 `canAdminDoc` 可改。
  **文档整理（2026-11-22）**：阅读区顶栏「整理」→ 改标签（`PUT …/docs/{docId}/tags {tags:[…]}`，逗号分隔最多 20 个）
  + 移动到其他空间（`POST …/docs/{docId}/move {targetShareId}`，只列我有写权限的空间，需空间/文档管理员或文档作者，二次确认）；
  移动后文档离开当前空间、历史版本随行走、归属人不变。
  **双链（2026-11-22）**：阅读区正文末尾自动展示 `GET …/docs/{docId}/links` 的「本文引用（N）」（`[[标题]]` 指向的，悬空的标「未找到该标题」置灰不可点）与「引用本文（N）」；点击用目标文档自己的 `shareId` 跳转，跨空间也能打开。
  **空间用量（2026-11-22）**：中间列标题下显示当前空间「已用 x / 上限 y」（`GET …/{id}/storage` 的 `used/capacity`，capacity=null 显示「不限」，超上限标红）；跨空间视图不显示。
  **扩容申请（2026-11-22）**：用量行旁「扩容」（仅 OWNER/ADMIN）→ 选 4/8/16/32/64GB 或自定义 / 不限 + 理由 → `POST …/{id}/capacity-requests`；下方列出该空间申请记录（待审批/已通过/已驳回 + 驳回意见）。
  **空间设置（2026-11-22）**：中间列「设置」→ 改名/改描述（`PUT …/{id}`，需 OWNER/ADMIN）；所有者额外可「删除这个空间」（`DELETE …/{id}`，二次确认并明示连带删除文档/版本/成员，不可恢复）。
  **新建团队空间（2026-11-22）**：左栏「＋」→ `POST …/doc-shares {name, description, type:TEAM}`（需 `SHARE_CREATE`，默认容量 2GB）；非系统管理员达 2 个上限时后端 403，同一对话框自动切换为「提交创建申请」（`POST …/create-requests`），管理员审批通过后自动建空间。
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
  - TipTap 自定义图块（**2026-11-18 改为静态渲染**）：
    - `sheetBlock`（节点名实际是 camelCase，`YjsHtml` 里先 `lowercase()` 再匹配）→ 按
      `data.rows[].cells[].text` 生成**只读静态表格**（截断 30 行 × 15 列，避免超大表拖死 WebView）；
      无 `data` / 空表给提示框，不再把 `sheetblock` 这种裸节点名丢给用户；
    - `flowchartBlock` → 输出「节点清单 + 连线（A → B（标签））」快照（上限 40 节点 / 60 连线），
      桌面端不做画布；
    - `mindmapBlock` → 中文提示框（导图是交互块，点「编辑」进网页版编辑）。
    - 验证方式：从真后端 `/yjs` 拉 6 份 `yjs_state`（表格/流程图/导图/空表）跑 `YjsHtml.toHtml`，
      全部非空、单元格文本（如 `a`）与连线（`123 → 开始（123）`）正确出现。
  - **2026-11-17 修「文档显示不可解析」**：根因是协同文档的正文只存在 `yjs_state`（relay 写），
    `content` HTML 快照经常为空；旧后端没有 `/yjs` 时桌面端拿不到正文，却只显示一句「正文暂不可解析」。
    现在：
    1. `DocHtml` 新增 `DbNotice` 块，图块 / `iframe` / `video` 等逐块标注「请在 Web 端查看」，
       并尽量带出图内文字——整篇只剩图块时不再误报「不可解析」；
    2. 空正文按真实原因给可执行提示（`404 未部署` / `tooLarge 超 8MB` / `parse 解析失败` /
       `empty 协同状态为空` / 无快照），并直接给「在浏览器中打开这篇文档 →」按钮；
    3. 来源仍标「协同实时 / 快照」两态。
  - **生效条件**：后端 `/yjs` 接口要部署（本地 jar 已含该方法）；桌面端 1.1.2 起就带读取逻辑，
    因此**先部署后端即可让已装客户端读到协同正文**，1.1.3 只是补上述提示与图块标注。
  - **历史版本（2026-11-17）**：阅读区右上「版本」→ 列出 `GET /{id}/docs/{docId}/versions`
    （`#id · 时间 · 快照字符数 · 是否含协同状态`），可「恢复」（`POST /versions/{vid}/restore`，
    恢复后 `detailTick++` 重载）与「保存当前为新版本」（`POST /versions`）。版本列表只在打开面板时拉，
    避免每次进详情都传整份 `content + yjs_state`。
  - **无 `/yjs` 时的正文兜底**：若当前无协同接口且 `content` 快照为空，自动取**最近历史版本**的
    `yjs_state` 本地解析，并在来源处标「版本 #id（时间）」——`versions` 是早已部署的端点，
    不用等后端升级也能看到正文。
- **管理模块（2026-11-16，日常运维闭环）**：侧栏「管理」进二级导航，**14 个页签**，权限全部由后端判定
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
    加根/加子类/重命名/删除（`POST|PUT|DELETE /api/admin/categories`，有子类时后端拒绝，文案透出）+
    **同级上移/下移**（`PUT /{id}` 只传 parentId+sortOrder；老数据 sortOrder 全为 0 时按目标位次 ±1 兜底）；
    标签（`GET /api/tags` 带文章计数）+ 搜索/新增/删除（`/api/admin/tags`）。
  - **帖子**（`POST_MANAGE`）：`GET /api/admin/docs?page&size&keyword=` 全站文章（含已封禁）+
    状态徽标（已发布/待审核/草稿/已封禁）+ 封禁/解封（`POST /{id}/block|unblock`）+
    删除（`DELETE /{id}`，**只移入回收站**，界面明示可恢复）。
  - **回收站**（`POST_MANAGE`）：`GET /api/admin/docs/recycle` + 恢复（`POST /{id}/restore`）；
    彻底删除与批量操作留 Web（不可逆）。
  - **空间申请**（仅 ADMIN）：两个页签——**创建申请**（`GET /api/admin/doc-shares/create-requests`，
    展示拟建空间名/描述/附带成员/理由/申请人/时间）与**扩容申请**
    （`GET /api/admin/doc-shares/capacity-requests`，展示空间名 + 当前容量 → 申请容量）。
    操作：通过（`POST …/create-requests|capacity-requests/{rid}/approve`，服务端自动建空间 / 调容量，
    二次确认并说明后果）与驳回（`POST …/{rid}/reject` body `{comment}`，理由必填）。
    与 App 端同名模块共用接口；两接口在 `AuthFilter` 里**不回退细粒度权限码**，只认 ADMIN 角色。
  - **代码组织**：外壳与小件在 `AdminPanel.kt`（220 行，**15 个页签**），模块按域拆分——
    `AdminReviewPanel.kt`（审核/阅卷/举报）、`AdminPeoplePanel.kt`（用户/部门）、
    `AdminContentPanel.kt`（公告/违禁词/孤儿附件）、`AdminTaxonomyPanel.kt`（分类标签）、
    `AdminDocPanel.kt`（帖子/回收站）、`AdminSpacesPanel.kt`（空间创建/扩容审批）、
    `AdminInsightPanel.kt`（看板/审计/登录日志）。
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
- **通知（2026-11-17，侧栏第 8 项）**：侧栏「通知」带未读角标（`GET /api/notifications/unread`，60s 轮询，不引 SSE）；
  面板未读高亮、点按即已读并跳对应文章（文章类通知 `shareId` 为空才跳）、「全部已读」、「删除」；
  类型文案按后端实际写入的 `COMMENT_MENTION / DOC_MENTION / COMMENT_REPLY / COMMENT / LIKE / DOC_UPDATE / REVIEW / CERTIFICATE / SECURITY_ALERT` 映射。
- **设置页（2026-11-17 补齐）**：三块卡片 ——
  1. **关于**：版本/通道 + 检查更新（原逻辑不动）；
  2. **账号**：服务器、登录账号（仍是「地址不进界面」口径下的域名脱敏展示）、退出登录；
  3. **本地数据**：正文图片缓存条数（`docImageCacheSize()`，内存态 60 张上限）+「清空图片缓存」、
     `ConfigStore.dirPath()` 显示与「打开配置目录」、「清除本地配置并退出」（删 `%APPDATA%\kb-desktop\config.json`
     并回调 `onLogout`）。整页改为可滚动（三块卡片在 640 高窗口会超出）。
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
- **内嵌编辑的登录态（2026-11-23）**：不注入 localStorage、也不走 `/sso` 中间页。桌面把令牌拼进目标 URL
  （`/doc-shares/{sid}?doc={did}#__kb_token=<token>`，令牌放 hash，不进服务端日志与 Referer），前端入口 `main.js` 新增的 `adoptIncomingToken()`
  在路由守卫首次导航之前写入 `localStorage.kb_token`，并立即 `replaceState` 抹掉地址栏参数 —— 一次导航直达，
  无「页面先跑、守卫已跳登录」的竞态。前端版本过旧（不认识该参数）会停在 `/login`，桌面探测到后自动退回
  `/sso?token=` 回调通道；工具条仍留「重新登录并刷新」手动重来

## 近期修复（2026-11-13）

- `ConfigStore` 的 config.json 读写改用 kotlinx.serialization 标准序列化（原手写正则解析在值含
  转义序列时会出错）；`AgentEngine` 的 TOOL: 行改括号配对提取首个完整 JSON（原行内贪婪正则遇到
  同行多余大括号会解析失败），`create_flowchart` 结果消息注明写入的共享空间名
- App 壳 `TokenStore` 改 AndroidKeyStore AES/GCM 加密持久化（enc1: 前缀，旧明文读取沿用、
  下次写入自动升级）；底部导航补 `navigationBarsPadding`，「文档库」Tab 补线面切换
- 主界面去掉外层 20dp + 白卡边框（框套框冗余），文章侧栏补浅底与会话侧栏一致；
  登录页保留书院风配色（用户拍板 2026-11-13）

### 管理界面复核加固

- 帖子搜索加 300ms 防抖，输入时不再整列表闪「加载中」；取消的请求不再被当成失败，也不让旧结果覆盖新结果
- 删除确认态在搜索/刷新后自动清除，避免误触删错行；移入回收站后刷新列表，计数与服务端一致
- 回收站的恢复/刷新同样放行协程取消，避免误报错误

## 约定

- 默认服务器地址见 `ConfigStore.kt`；本地/换环境用 `KB_DESKTOP_SERVER` 环境变量或 config.json 的 server 覆盖
- **正式发版必须注入地址**：`gradle packageInnoSetup -PKB_DESKTOP_SERVER=https://<部署域名>`，
  该值只存在于发版命令/云端配置，仓库内是示例占位；不注入打出的包会连到示例地址
- 版本号独立于 Web 端（`package.json`/`changelog.js` 是 Web 的，别混）

## 可选文本与交互补齐（2026-11-22）

- **正文可选中复制**：文章阅读页与共享文档阅读区的正文块统一包 `SelectionContainer`
  （`androidx.compose.foundation.text.selection`），鼠标拖选 + Ctrl+C 即可复制，不再是「一张图」。
  评论正文同样包了一层。
- **看板热门文章可点击**：`StatsTab` 新增 `onOpenDoc` 回调，`AdminPanel(session, onOpenDoc)` 透传到主窗口
  （`jumpDocId = id; selected = DOCS`）。点击热门文章行 → 用 `searchDocs(标题)` 找 id 原地跳文章；
  找不到精确标题时退回浏览器 `/search?q=标题`。行有 hover 底色。
- **菜单图标线宽统一**：`MenuGlyph` 的 stroke 从误算的 `1.6f*u*1.6f`（约 2.56px）改为 `1.6f*u`，与 Web 端细线图标一致。

## 共享文档内嵌协同编辑（2026-11-22）

阅读区顶栏新增「编辑（协同）」（仅文档权限为 WRITE/ADMIN/OWNER 时显示）：右侧栏切换为**系统 WebView2** 里的
Web 端共享文档编辑器（`ShareWebEditor`），这是真正的实时协同（Yjs + relay + 光标），而只读渲染仍是本地的 `/yjs` 快照。
- 登录态桥接（2026-11-23 定稿）：**不往 localStorage 里硬塞 token**（页面脚本可能先跑，守卫当场把人踢到登录页），
  也不靠轮询等它落。桌面把令牌拼进目标 URL（`/doc-shares/{sid}?doc={did}#__kb_token=<token>`，令牌放 hash，不进服务端日志与 Referer），
  前端入口 `main.js` 的 `adoptIncomingToken()` 在首次导航前写入 `localStorage.kb_token`，随即 `replaceState`
  抹掉地址栏参数 —— 一次导航直达，令牌不进历史记录/截图/日志。旧前端不认识该参数会停在 `/login`，
  桌面探测到后自动退回 `/sso?token=` 回调通道（`SsoCallback.vue` → `applySession` + `fetchMe` + `router.replace('/')`）；
  工具条仍留「重新登录并刷新」手动重来。
- 工具条：「刷新」（重新导航并重新注入）/「浏览器打开」/「返回只读」；退出编辑会 `detailTick++` 重拉协同正文。
- 依赖：`io.github.kdroidfilter:composewebview`（Windows WebView2 运行时，免分发 CEF），与文件预览同一套。

## 应用内更新（2026-11-22，Updater.kt）

原来「下载更新」只是 `openInBrowser` 跳浏览器。现在设置页「下载并安装」在本应用内完成：
1. `KbUpdater.download(url, name)` 用 JDK HttpClient 流式下载到 `%LOCALAPPDATA%\kb-desktop\updates`，界面显示进度（MB + 进度条）；
   相对 `url`（nginx 代理的 `/kb/app-release/…`）会自动补成 `server + url`。
2. `checksum` 非空时用 `KbUpdater.sha256(file)` 与后端 `app_version_desktop.checksum` 比对，不一致直接删包并提示。
3. 写一个 `apply-update.cmd`：`timeout 2s`（等主程序释放文件）→ `start /wait <setup> /VERYSILENT /SUPPRESSMSGBOXES /NORESTART`
   → `start <自身 exe>` → `del %~f0`；用 `cmd /c start /min` 最小化启动。
4. 应用 `exitProcess(0)`，安装器静默**覆盖**同目录（`AppId` 固定、`PrivilegesRequired=lowest`、`DefaultDirName={localappdata}`），
   装完由脚本拉起新版，设置/本地数据不动。开发态（`java.exe`）不自动重启，只提示安装包路径。
保留「手动下载」按钮作为兜底（比如校验失败或环境拦截 cmd）。

## 看板图表（2026-11-22，DashboardCharts.kt）

`/api/admin/stats` 除键值外还带 `trendLabels / docTrend7 / userTrend7 / commentTrend7 / topDocs / topUsers / activities`。
桌面端新增 `ApiClient.adminStatsJson()`（保留数组与数字）与 `DashboardCharts.kt`（**Compose Canvas 自绘，不引图表库**）：
- `StatTile`：渐变图标块 + 大数字（文章/用户/评论/浏览 四卡）；
- `DonutChart`：文档状态环形（已发布/待审核/草稿/已封禁，右侧图例带数值）；
- `BarChart`：互动总量柱状（评论/点赞/收藏/通知，圆角渐变柱 + 柱顶数值）；
- `LineChart`：近 7 日趋势折线（文档/用户/评论三线 + 网格 + 图例 + 轴标签用 Compose 排版）；
- 「热门文档 Top5」「最近动态 Top6」「其余指标」三块卡片。
文字全部用 Compose 排版（不在 Canvas 里画文本，避免 TextMeasurer 跨版本问题）。

## 视觉精修（2026-11-22，UiKit.kt）

新建 `UiKit.kt`（桌面轻量组件套，视觉与 Web 端保持一致，只做视觉）：
- `KbSurface` 白卡（圆角 12 + 细边 + 轻阴影）、`KbSelectCard`（hover 浅底 / 选中浅主色 + 左侧 3dp 竖条）、
  `KbGlyphTile` 渐变图标块、`KbTag(text, tone)`（Primary/Success/Warning/Danger/Neutral）、
  `KbPrimaryAction` / `KbGhostAction`（有边框 + hover）、`KbMiniIconButton`（圆形图标钮，hover 主色浅底）、
  `KbEmptyState`、`KbColumnHeader`。
已套用：
- **共享空间**：左栏空间/「全部文档」与中栏文档项全部卡片化（hover + 选中竖条 + 圆形色块 + 成员数/篇数副标题）；
  栏头改为 `KbColumnHeader` + 图标钮（＋ / ↻ / ◎ / ⚙）；阅读区操作钮换成有边框的 ghost 按钮；
  元信息标签改用 `KbTag`（协同实时=绿、快照=中性、版本兜底=橙，空间名=主色）；三栏列表加 6dp 内边距 + 2dp 间距；
  空态/加载态统一为「圆底图标 + 主文案 + 说明」。
- **管理面板**：15 个页签换成 `KbSelectCard`（hover + 选中竖条），`AdminHeader` 标题升 14sp、副标题 11sp、「刷新」改图标钮。


## 管理列表分页与表格样式（2026-11-23）

- `UiKit.kt` 新增三个组件，统一对齐 Web 端 el-table / el-pagination 观感：
  - `KbTableHead`：浅灰表头（`#F7F8FA`）+ 列权重，替代原来各面板手写的栏头；
  - `KbTableRow`：白底行 + hover 浅底 + 细分隔线，可整行点击；
  - `KbPager`：右对齐「共 N 条 · 每页 20/50/100 条」+ 页码窗口（当前页实心主色）+ 上下页。
- `Api.kt` 新增 `PageBox<T>`（items/total/page/size）与 `pageParts()`：管理端接口既有裸数组又有 Spring Page，
  统一取 `content` / `totalElements`；users / docs / reports / announcements / audit / login-logs 各加 `*Page(...)`
  重载，旧方法保留为「第一页 50 条」以兼容其余调用点。
- 已接服务端分页：**用户、公告、帖子、举报、操作审计、登录日志**；当前页被删空会自动退回最后一页。
- 本地切片分页：**回收站**（后端返回裸数组）、**违禁词词条**（左侧选库、本地筛选后再切页）、**标签**。
- 卡片转表格：**审核队列**（驳回理由输入框在第一列展开）、**阅卷**（得分/待阅/提交时间成列）、
  **空间申请**（创建/扩容两套表头，容量变化单独成列）、**孤儿附件**（路径作副行、大小单列）。
- 至此 `AdminPanel` 的 15 个 tab 全部统一到「表头 + `KbTableRow` + `KbPager`」这一套；分类树仍保留缩进树
  （层级关系用表格表达会丢信息），只统一了行高与 hover。

## 对话框统一 KbDialog（2026-11-23）

- `UiKit.kt` 新增 `KbDialog`：与 Material3 `AlertDialog` **参数兼容**（onDismissRequest / title / text /
  confirmButton / dismissButton），因此存量调用只需把 `AlertDialog(` 换成 `KbDialog(`，逻辑一行不改、外观即统一：
  白卡 + 14dp 圆角 + 标题栏右侧固定「✕」（Material 只能点遮罩/ESC，桌面用户常找不到出口）+ 内容限高 560dp。
- 宽度跟随内容（`widthIn(min = 320.dp, max = 520.dp)`）：存量对话框多是 460dp 的固定宽 Column，不会被撑出白边。
- 已替换 18 处：`SharesPanel.kt` 10（成员/分享链接/文档权限/整理/空间设置/新建空间/扩容申请等）、
  `MailPanel.kt` 4、`DocReader.kt` 2、`AdminSpacesPanel.kt` 2。
- 验证：`compileKotlin` BUILD SUCCESSFUL、`KB_SMOKE=1 run` EXIT=0。

## 首页图表 + 骨架屏 + 面板统一（2026-11-23）

- **首页工作台加图表**：指标卡下方新增「近 7 天更新」折线（复用 `DashboardCharts.LineChart`）与「分类分布」环形
  （`DonutChart`）。数据来自 `GET /api/docs?size=80&sort=latest`，在客户端按 `updatedAt` 分桶与按 `category` 分组，
  **普通成员也能看到**（不依赖只对管理员开放的 `/api/admin/stats`），失败则整块隐藏、不影响其他区块。
- **骨架屏**：`UiKit.KbSkeletonList(rows)`——灰条 + 呼吸动画，替换「加载中…」文字。已替换 26 处：
  管理端 16（用户/公告/帖子/举报/审计/登录/回收站/违禁词/分类/空间申请等）+ 通知/文件/文档面板 6 + 邮件 2 + 培训 6。
- **切换淡入**：`AdminPanel` 的 tab 切换包 `Crossfade(tween(160))`，不再整块瞬移。
- **空态与标签**：通知面板空态改 `KbEmptyState`（图标 + 标题 + 说明），未读标记改 `KbTag`；文件面板空态同步。
- 仍未动的面板：设置（表单型，列表少）、AI 助手（对话流，行样式不适用）——按「结构与表格不同」原则保留原样。

## 设置页统一 KbSettingGroup（2026-11-23）

- `UiKit.kt` 新增 `KbSettingGroup(title) { … }`（白卡 + 小节标题）与 `KbSettingRow(label, value)`
  （左标签固定 92dp、右值右对齐，长路径自动换行到右侧，不会顶到标签上）。
- 设置页三张卡（关于 / 账号 / 本地数据）从手写 `clip + background + border` 改为 `KbSettingGroup`，
  原来各写一遍的圆角边框不再漂移；「退出登录 / 清空图片缓存 / 打开配置目录」三个浅灰 `Button` 换成 `KbGhostAction`。
- 「检查更新 / 下载并安装」仍用 Material `Button`：它们的 onClick 里是一整段协程 + 进度回调，
  搬进 `KbPrimaryAction` 只会增加出错面，且颜色形状已经是项目主色与 9dp 圆角，观感一致。

## 本地适配：单实例锁 + 系统通知（2026-11-24）

- **`Instance.kt` 单实例锁**：启动时在 `%APPDATA%\kb-desktop\.instance.lock` 上做 OS 级 `FileLock`。
  为什么必须做：`%APPDATA%\kb-desktop` **同时是 WebView2 的 user-data 目录**（Cache / Local Storage 都在里面），
  第二个进程会和第一个并发写同一份档案（表现为「编辑页登录态时有时无」），还会各自读改写 `config.json`
  （表现为「设置自己变回去」）。双开时第二个进程弹原生提示「知识库已经在运行」并退出。
  锁在进程崩溃时由系统自动释放，不会留下假死锁；目录不可写时放行（宁可双开也不让用户打不开）。
- **`Notify.kt` 系统通知**：AWT `SystemTray + TrayIcon.displayMessage`（Windows 10/11 进通知中心）。
  触发条件：未读数**变多**且主窗口**不在前台**（在前台有侧栏角标，再弹就是打扰）；同标题 20 秒去重
  （60s 轮询会连续返回同一个数字）。系统不支持托盘时静默降级，不影响主流程。
- 验证：`compileKotlin` BUILD SUCCESSFUL；`KB_SMOKE=1 run` EXIT=0；锁文件已生成并写入 pid。

## 补回三处「必须开浏览器」的管理功能（2026-11-24）

后端接口早已就绪，缺口在客户端 UI。本轮补三处：

- **用户管理 `UserActionDialog`**（`AdminPeoplePanel.kt`）：行内「管理」按钮 → 改角色 / 调积分 / 重置密码。
  - 改角色 `PUT /api/admin/users/{id}` 只传 `{"role":…}`；`AdminService.updateUser` 对 null 字段跳过，
    不会清掉昵称/邮箱（这是只传单字段安全的前提，已在注释里写明）。
  - 调积分 `POST /{id}/points {delta,reason}`：可正可负，后端写一条 ADJUST 明细并进审计。
  - 重置密码 `POST /{id}/reset-password`：返回后端生成的随机明文密码，**只回显一次**，界面标注「立即转告用户」。
  - 三项都不弹二次确认：可撤销 / 会写审计，多一层确认反而拖慢日常运维。
- **回收站彻底删除**（`AdminDocPanel.kt`）：`DELETE /api/admin/docs/{id}/purge`，行内二次确认（「彻底删除」→「确认彻底删除」/「取消」）。
- **课程章节正文编辑**（`LearnPanel.kt`）：章节页「编辑正文」→ 多行编辑器 → `PUT /api/admin/courses/chapters/{id} {"content":…}`；
  保存后刷新详情。非管理员会拿到后端 403 文案（前端不另做权限判断）。
- 验证：`compileKotlin` BUILD SUCCESSFUL、`KB_SMOKE=1 run` EXIT=0。
- 未做：**部门管理**（增删改 + 成员分配）——涉及树形编辑与成员选择，单列一轮。

## 部门管理（2026-11-24）

- `DepartmentsTab`：树形列表每行加「编辑 / 成员 / 删除（二次确认）」；顶部「新建部门」。
- `DeptEditDialog`：名称 / 编码 / 排序；新建出来的是**根部门**。层级调整仍走 Web 端拖拽——
  树形父级下拉 + 防成环校验的复杂度不抵收益，后端 `move` 也要求 parentId 与 sortOrder 同时给。
- `DeptMemberDialog`：搜索用户 → 点一下即 `POST /admin/departments/{id}/members {userIds}`。
  **只做加入不做移除**：后端没有按部门查成员的 GET（只有 POST/DELETE），列不出当前成员让用户挑，
  做半套反而误导；移除仍回 Web 端。
- 删除失败时后端文案（「存在子部门/仍有成员」）原样透出到 toast，不做二次包装。
- 验证：`compileKotlin` BUILD SUCCESSFUL、`KB_SMOKE=1 run` EXIT=0。

## 新增「收藏」「排行榜」两个页面（2026-11-24）

- 侧栏在「考试」之后新增两项，菜单由 `MenuKind.entries` 驱动，加枚举即出现（图标 `MenuGlyph` 同步补了书签与柱状图）。
- **收藏**（`FavoritesPanel`）：`GET /api/favorites/mine` 列表 + 行内「打开 / 取消收藏」（`DELETE /api/favorites/{docId}`，幂等）。
  点「打开」跳到文章面板并定位到该篇（复用 `jumpDocId` 通道）。
- **排行榜**（`RankingPanel`）：`GET /api/points/ranking`，前 20 名 + 顶部「我的名次 #n · x 积分 · 共 y 人」；
  前三名用 `KbTag` 警告色标注。
- 两页都用 `KbTableHead / KbTableRow / KbSkeletonList / KbEmptyState`，与管理端视觉一致。
- 验证：`compileKotlin` BUILD SUCCESSFUL、`KB_SMOKE=1 run` EXIT=0。

## 新增「徽章」「积分」两个页面（2026-11-24）

- **徽章**（`BadgesPanel`）：`GET /api/badges` → 卡片墙（图标瓦片 + 名称 + 描述 + 进度条 + `current / threshold`）；
  已获得用主色软底，未获得灰色；已佩戴显示 `KbTag("佩戴中")`。顶部「已获得 x / y」。
- **积分**（`PointsPanel`）：`GET /api/points/log` → 表格（类型 / 原因 / 变动 / 余额 / 时间）；
  变动正数绿色、负数红色，类型映射成中文（ADJUST→调整、DOC_PUBLISH→发文…）。顶部显示当前余额。
- 两个新菜单项各配了自绘图标：徽章=圆章+下摆，积分=硬币。
- 验证：`compileKotlin` BUILD SUCCESSFUL、`KB_SMOKE=1 run` EXIT=0。
- 侧栏现共 15 项：首页/文章/共享空间/培训/考试/收藏/排行榜/徽章/积分/AI 问答/我的文件/邮箱/通知/管理/设置。

## 新增「私信」页面（2026-11-24）

- 左侧会话列表（`GET /api/dm/conversations`：昵称 / 最后一条 / 未读红标 / 「我：」前缀），选中项用 `KbSelectCard` 高亮。
- 右侧消息流（`GET /api/dm/messages?peerId=&size=30`）：自己右对齐主色气泡、对方左对齐浅灰；
  当前会话 **30s 轮询**（只比条数与最后 id，没变化不重绘）。
- 底部输入框 + 发送（`POST /api/dm/messages {toId, content}`），发送后立即重拉并刷新会话列表。
- 未做：附件、撤回、归档、举报 —— 后端有接口，但桌面端私信是「够用」定位，先不做。
- 侧栏 16 项；`MenuGlyph` 为私信画了双气泡图标。
- 验证：`compileKotlin` BUILD SUCCESSFUL、`KB_SMOKE=1 run` EXIT=0。

## 新增「复习卡片」页面（2026-11-24）

- 数据：`GET /api/flashcards/due`（待复习）、`GET /api/flashcards/summary`（total/due/learned/new）、
  `POST /api/flashcards/{id}/review {grade}`（grade = again / hard / good / easy）。
- 交互：大卡片居中，**点卡片翻面**（正面↔背面，显示来源出处）；翻面后出现四档评分：
  忘了 / 有点难 / 记住了 / 很简单（忘了用危险色）。评完自动下一张，本批刷完自动重载统计。
- 排期由后端 SM-2 决定（again 10 分钟后再到期、good 按 ease 递增），桌面端只负责评分，不本地算间隔。
- 空态：「今天没有要复习的卡片」——新增卡片在手机端 / Web 端（桌面不做建卡表单）。
- 侧栏 17 项；`MenuGlyph` 为卡片画了两张叠放的正交卡片。
- 验证：`compileKotlin` BUILD SUCCESSFUL、`KB_SMOKE=1 run` EXIT=0。

## 侧栏 17 项溢出修复（2026-11-24）

- 问题：菜单项每项 40dp，17 项 = 680dp，加上品牌行（约 60dp）与底部用户区（约 60dp）≈ 800dp——
  **正好等于主窗口高度**，任何一点边距就会把「用户区 / 退出登录」挤出可视区；宽度 < 880dp 收成图标栏时
  （每项 44dp）溢出更明显。
- 修法：菜单区改为 `Column(Modifier.weight(1f).verticalScroll(...))`，菜单自己滚动，底部用户区永远可见。
  顺带去掉了原来用来顶开底部的 `Spacer(Modifier.weight(1f))`（现由菜单区占据剩余高度）。
- 为什么不是缩小项高：40dp 已是 24dp 图标 + 上下留白的下限，再压会让点击目标变小、hover 区域难瞄。
- 验证：`compileKotlin` BUILD SUCCESSFUL、`KB_SMOKE=1 run` EXIT=0。

## 管理台左栏加滚动（2026-11-24）

- 管理台 15 个 tab × 约 34dp ≈ 510dp，窗口最小高 620dp 时加头部已接近撑满；
  系统字体放大或窗口更矮时，最后几个 tab（操作审计 / 登录日志 / 孤儿附件）会被裁掉且无法到达。
- 修法同侧栏：tab 列 `weight(1f) + verticalScroll`，头部「管理」标题固定在顶部。
- 至此桌面端所有「固定数量条目」的侧栏（主侧栏 17 项、管理台 15 项）都可滚动；
  其余长列表（会话 / 邮件 / 文档 / 共享空间）本来就是 LazyColumn。
- 验证：`compileKotlin` BUILD SUCCESSFUL、`KB_SMOKE=1 run` EXIT=0。

## 首页第二行快捷入口（2026-11-24）

- 侧栏从 11 项涨到 17 项后，收藏 / 复习卡片 / 私信变成「要翻侧栏」才能到。首页快捷区补了第二行三个入口，
  与原有「问 AI / 全部文章 / 我的文件」并列。
- 首页整页是 `verticalScroll`，多一行不影响 620dp 最小高度下的可达性。
- 验证：`compileKotlin` BUILD SUCCESSFUL、`KB_SMOKE=1 run` EXIT=0。

## 帖子置顶/取消置顶（2026-11-24）

- 帖子管理行内新增「置顶 / 取消置顶」：`POST /api/admin/docs/{id}/pin|unpin`（需 POST_MANAGE）。
- `AdminDocItem` 增加 `pinned` 字段并解析后端 `Document.pinned`，按钮文案随状态切换；成功后行内乐观更新。
- 后端 `Document.pinned` 存在，列表优先展示置顶文章，桌面端只负责设置状态，不本地排序。
- 验证：`compileKotlin` BUILD SUCCESSFUL、`KB_SMOKE=1 run` EXIT=0。

## 侧栏图标整套重绘（2026-11-25）

- 原因：旧图标各画各的（铃铛 7 段折线、盾牌+勾、书本 4 段），比例不统一、线宽偏细，小尺寸下显得碎。
- 新规范：24 网格、**1.8u 线宽**（旧 1.6u）、端点/拐角圆角，每个图标 ≤ 5 个元素，视觉中心固定 (12,12)。
- 全部 17 个图标重写：房子 / 文档 / 双页 / 打开的书 / 试卷勾 / 书签 / 三柱 / 圆章 / 硬币 / 双气泡 / 叠卡 /
  对话气泡 / 文件夹 / 信封 / 铃铛（简化为 4 段）/ **齿轮（中心圆 + 八齿，用 cos/sin 计算）** / 滑块。
- 新加的六个（收藏 / 排行榜 / 徽章 / 积分 / 私信 / 卡片）与原有 11 个共用同一套规范。
- 验证：`compileKotlin` BUILD SUCCESSFUL、`KB_SMOKE=1 run` EXIT=0。

## 帖子 / 回收站批量操作（2026-11-25）

- 两处表格都加了多选（行首 16dp 自绘方块，不用 Material Checkbox——后者强制 48dp 触摸区会把行撑高）。
- **帖子**：批量封禁 / 批量移入回收站。
- **回收站**：批量恢复 / 批量彻底删除；彻底删除需要「批量彻底删除 → 确认全部彻底删除」两步，避免误清。
- 后端没有批量端点，逐个调用后汇总「成功 x 篇，失败 y 篇（首个错误原因）」；单条失败不中断整批。
- 选择会在换页 / 搜索 / 操作完成后自动清空，避免「看不见的行也被批量处理」。
- 验证：`compileKotlin` BUILD SUCCESSFUL、`KB_SMOKE=1 run` EXIT=0。

## 用户管理批量启用 / 停用（2026-11-25）

- 用户表加多选（同款 16dp 自绘方块），批量栏：全选本页 / 批量停用 / 批量启用 / 取消选择。
- 后端只有单个 `POST /admin/users/{id}/toggle`，逐个调用并汇总「成功 x 人，失败 y 人（首个原因）」；
  单条失败不中断整批（例如目标是自己或管理员时会 403）。
- 换页 / 搜索 / 刷新会清空选择，避免对看不见的行执行批量。
- 至此桌面端管理台有三处批量：用户（启用/停用）、帖子（封禁/回收站）、回收站（恢复/彻底删除），
  行为一致（汇总结果、失败不中断、选择自动清空）。
- 验证：`compileKotlin` BUILD SUCCESSFUL、`KB_SMOKE=1 run` EXIT=0。

## 私信消息流与撤回窗口（2026-11-28）

- 消息流此前没有 `LazyListState`：打开会话停在顶部，自己发完消息也看不到最新一条。现在给消息流绑定
  `rememberLazyListState`，打开会话与发送消息后滚到底；轮询到新消息时只在用户本就靠近底部时跟随，
  不打断向上翻阅。
- 撤回按钮的可见性依赖「当前时间 - 发送时间 < 2 分钟」，但轮询拉到的消息内容没变时列表不重组，
  到点后按钮一直亮着。现在每轮轮询推进一个分钟级 tick 作为重组依赖，超过窗口自动隐藏。
- 侧栏与首页快捷卡片的色块改为按功能语义分组：浏览=蓝、学习=绿、社交=琥珀、数据=紫、管理=石板，
  两处共用同一套色板；菜单图标的次要笔画改用半透明色，主次更清楚。

## 侧栏图标细节补齐（2026-11-28）

- 徽章（BADGES）：圆章内补一枚小星，与 Web 端奖章图标用的是同一家族细节，小尺寸下也一眼能认出「奖章」。
- 积分（POINTS）：硬币由「外圆 + 一根横线」改成「外圆 + 内圈 + 短线」，多一圈厚度感，
  和通知这类纯圆形图标不再撞脸。
- 只动这两个图形，其余 15 个图标的线宽、比例和色板都不变。

## 本地服务与更新链路收紧（2026-11-28）

- Electron 的本地 HTTP 服务和反代此前没指定监听地址，Node 默认可能绑到 `0.0.0.0`，同网段机器能直接
  访问静态站、`/__login` 和反代；现在只绑 `127.0.0.1`。反代只接受 origin-form 路径（`/xxx`），
  绝对形式请求行直接 400，目标用 `BACKEND` 的 scheme+host 与 pathname 重建，不会被当成开放代理；
  静态文件的目录边界改成按路径分隔符比较，`/web` 与 `/web2` 不再互相误判。
- 更新包的下载文件名来自服务端，此前直接 `resolve` 到更新目录，带 `../` 或绝对路径就能写到目录外。
  现在 `sanitizeFileName()` 只保留纯文件名并校验规范化后的路径仍在更新目录内；重装脚本不再把路径拼进
  `.bat` 正文，改由环境变量传入、脚本内加引号引用，安装路径里出现 `& | < > ^` 也不会被 cmd 当成命令。
- 协同正文导出 HTML 时，图片 `src` 与链接 `href` 只做了 HTML 转义、没限制协议，协作内容里写
  `javascript:` 会在 WebView 里执行；现在按白名单放行 http/https、站内相对路径，链接额外允许
  `mailto:` 与锚点，其余丢弃该属性。

# 知识库 Native（Kotlin + Jetpack Compose 原生壳）

> **架构定案（2026-10-25）：UI 直接采用套壳 Web 页面（/m/ 路径族），像素级一致、与 Web 端永远同步。**
> 原生壳负责能力层：应用锁、系统分享、应用内更新、令牌桥接、冷启动速度。
> 早期手绘的 Compose 原生页（AI/搜索/卡片/考试/管理等）保留在代码库中且路由可达（`ui/` 各目录），
> 但主体验不再使用——手绘复刻无法跟随 Web 端演进，这是两轮真机对比后的结论。
> 原有 `mobile/`（Capacitor 工程）保留不动。

## 架构

```
MainActivity（应用锁判定 / 分享 intent 接收）
└── AppRoot（令牌驱动：未登录→原生登录页；已登录→ShellScreen）
    ├── ShellScreen：全屏 WebView 承载 /m/home
    │   ├── 令牌桥接：拦截 HTML 注入 window.__KB_NATIVE_TOKEN → main-mobile.js 写入登录态
    │   ├── 文件选择/拍照桥、JS 弹窗、进度条（WebSupport 共用层）
    │   ├── 返回键：WebView 可退则退，否则退后台（回切走应用锁）
    │   └── 分享到达：跳原生 ShareImportScreen（原生落盘，不依赖 Capacitor 插件）
    ├── ShareImportScreen（原生）：选空间导入 / 收待整理箱
    └── 其余原生页（AI/搜索/卡片/考试/阅读/管理）：已实现、路由保留、未挂主体验
```

Web 页面在 WebView 中以标准 PWA 形态运行（无 Capacitor 对象，main-mobile.js 有 isNative 守卫），
接口走同源相对路径 `/api`，与浏览器访问完全同构；`viewport-fit=cover` + 边到边布局下
`env(safe-area-inset-*)` 正常生效，头部件不顶状态栏。

## 现状（v0.2 · P1/P2 已落）

已原生实现：

| 模块 | 说明 | 对接接口 |
|---|---|---|
| 登录页 | 滑动拼图验证码（Compose 手绘，协议与 Web 端 SlideCaptcha.vue 同源：320 基准坐标换算、轨迹 ≥3 点 ≥120ms） | `/api/auth/captcha` `/api/auth/captcha/slide` `/api/auth/login` |
| 主框架 | 底部四 Tab：首页 / 文档库 / 消息 / 我的；消息 Tab 未读角标（SSE 实时）；**主题令牌与套壳 mobile.css --m-* 家族对齐（#1677FF）** | `/api/notifications/stream` (SSE) |
| 首页 | 数据概览 + 公告 + 快捷入口（AI/搜索/卡片/模拟练习/考试中心直达原生页）+ 分享横幅 | `/api/docs/overview` `/api/announcements` |
| 文档库 | 空间列表 → 文档列表（**空间内标题检索 + 最近更新/标题/创建时间排序，纯本地**）→ **原生阅读器**（离线可读、顶部「刷新」+ 更新于时间；编辑进 WebView；**外部分享链接**：免登录只读 `/s/{token}`，可设密码 / 7·30·90 天 / 禁止复制，生成·更新·撤销·复制·打开；**文档权限**：单篇覆盖空间权限，只读·可编辑·可管理三档 + 恢复继承，仅能管理该文档者可改）；空间顶栏「成员」→ **成员管理**（列表 / 邀请搜索 / 只读·可编辑·可管理三档改权限 / 移除，仅 OWNER·ADMIN 可改，显示所有者与容量占用） | `/api/doc-shares/*` |
| AI 问答（原生） | **对标套壳 MobileAiChat**：历史会话抽屉（续接/删除）、知识库/通用模式、模型选择、Markdown 渲染、引用来源与模型元信息 | `/api/ai/*` |
| 后台管理（原生） | **12 个原生模块，全部按权限码显隐**：运营看板（统计 / 审计日志 / 登录日志三 Tab）、文章审核（通过/驳回）、帖子管理（搜索 / 封禁解封 / 移入回收站）、举报处理（状态筛选 + 处理备注）、空间申请（创建/扩容审批，仅 ADMIN）、违禁词库（词库启停 / 批量加词 / 删词）、文章回收站（恢复 / 彻底删除）、分类与标签（分类树增删、标签增删带篇数）、用户管理（搜索/启停/改角色）、AI 模型管理（启停/默认/测试）、公告管理（发布/置顶/启停）、角色管理 | `/api/admin/*` |
| 文章页（原生） | 正文 + **点赞 / 收藏 / 订阅**（乐观更新 + 失败回滚并透出后端文案）+ **相关阅读**（`/docs/{id}/related`，点击续读）+ **系统分享**（分享文案 + `/m/post/{id}` 链接）+ **楼中楼评论**（回复挂顶层、子回复缩进、评论点赞、自己的可删、别人的可举报）+ **文章/用户举报**（顶栏「举报」，六项原因；`KbReportDialog` 公共组件）+ 点作者名进用户主页（关注 / 私信 / 举报 / TA 的文章） | `/api/docs/*` `/api/likes/*` `/api/favorites/*` `/api/comments/*` `/api/reports` |
| 消息 | 通知列表、点按已读、SSE 到达自动刷新 + 角标 | `/api/notifications` `/api/notifications/unread` |
| 私信（原生） | 会话列表（收件箱/归档、未读角标、删除）+ 单会话聊天（游标翻更早、2 分钟内撤回、已读回执、未互关剩余条数提示）；入口在消息页与用户主页 | `/api/dm/*` |
| 我的帖子（原生） | 作者视角：草稿「提交审核」、待审「撤回」、任意「删除」（二次确认，已发布会提示删除后读者不可访问）；显示状态徽标/阅读量/驳回理由 | `/api/docs/mine` `/api/docs/{id}/submit` `/api/docs/{id}/withdraw` `/api/docs/{id}` |
| 草稿箱（本机） | 断网/后台暂存的图片与编辑草稿（`DraftStore`）：「全部上传」批量补传、单条「立即上传」、查看详情、删除；入口在「我的」页（带待上传条数副标题），首页横幅同步计数 | 上传走统一文件/文档链路 |
| 文件中心（原生） | 我的文件列表 + **统一上传**（`OpenDocument` 按白名单过滤 → 复制到 cache → `/api/files/mine`）+ 复制链接；**只支持图片 / 音频 / 视频 / PDF，单文件 ≤ 50MB**（后端 `FileService.ALLOWED` + 魔数嗅探，故按扩展名兜底推断 MIME，见 `guessUploadMime`）；扫描/分享导入复用同一条上传链路 | `/api/files/mine` |
| 搜索（原生） | 我的空间全文档标题/正文检索 + 搜索历史 | `/api/doc-shares/*` |
| 复习卡片（原生） | SM-2 到期队列：翻面 + 四档评分 + 本轮小结 | `/api/flashcards/due` `/api/flashcards/{id}/review` |
| 模拟练习（原生） | 随机抽题、逐题判分看解析、答题卡跳题、错题回顾 | `/api/exams/practice/*` |
| 考试中心（原生） | 试卷列表 + 我的成绩；倒计时作答（到时自动交卷）、成绩面板 | `/api/exams/*` |
| 分享导入（原生） | 微信/文件管理器分享 → 选空间导入或收待整理箱 | `/api/files` `/api/doc-shares/*` |
| 离线缓存 | 列表 + 打开过的正文（100 篇上限），按账号隔离落盘 | — |
| 应用锁 | 4-6 位 PIN（salt+SHA-256），冷启动必锁、后台超 60s 回切加锁 | — |
| 应用内更新 | 版本检查 → 下载（断点续传 + **域名白名单**）→ 系统安装器 | `/api/public/app-version` |
| 个人成长（原生） | 积分明细（`delta` 正负着色 + `total` 分页）、排行榜（`top` + 「我：第 N 名」，人数用 `total`）、徽章成就（`badges[]` 按 `earned`/`worn` 渲染，佩戴/再点卸下，`id` 是字符串如 `first_post`）；课程中心（课程列表 + 章节阅读 + 完成打卡） | `/api/points/*` `/api/badges` `/api/badges/wear` `/api/courses/*` |
| 我的订阅（原生） | 订阅列表（`docId`/`title`/`deleted`/`subscribedAt`，已删除置灰不可点）+ 点行进文章 + 「退订」 | `/api/docs/subscriptions` `/api/docs/{id}/subscribe` |
| 回收站（原生） | 我删除的文章（`title`/`deletedAt`）+ 恢复 / 彻底删除（二次确认） | `/api/docs/recycle` `/api/docs/{id}/restore` `/api/docs/{id}/purge` |
| 邮箱（原生） | 账号切换（`全部` / 单账号，`email` 服务端已掩码）+ 收件箱列表（未读点、发件人、主题、摘要、附件标记、时间）+ 阅读（`textBody` 优先，`htmlBody` 只转纯文本，不渲染远程 HTML）+ 标已读/删除 + 手动同步 + **授权码绑定**（QQ/163 等）+ **写邮件 / 回复 / 存草稿**（`/mail/send`、`/mail/drafts`，后端未升级时提示「服务端发信可能尚未升级」）+ **收件箱 / 草稿箱 / 已发送**（`folder=INBOX|DRAFT|SENT`，草稿可再「编辑草稿」预填回收件人/主题/正文）+ **分页「加载更多」**（30 封/页，按 id 去重合并）+ **附件列表**（后端 `attachments` 是嵌套 JSON 字符串，点名称用系统浏览器打开站内 `/uploads/…`）+ **收件人补全**（`/mail/contacts`，输入 2 字以上 300ms 防抖给候选）+ **账号签名**（`PUT /mail/accounts/{id}/signature`，写信/存草稿时客户端自动追加，已含则不重复）；入口在「我的 → 邮箱」和「消息」页 | `/api/mail/accounts` `/api/mail/messages` `/api/mail/{id}/read` `/api/mail/send` `/api/mail/accounts/code` |
| 我的 | 资料、积分、服务器地址设置、退出登录 | `/api/auth/me` |
| WebView 承载页 | 任意 /m/ 页面；令牌桥接自动登录；**文件选择/拍照桥**（input file + capture 相机）、JS alert/confirm、进度条、返回栈 | — |

**版本线**：Capacitor 版停更于 versionCode 29；原生壳从 30+ 起步（现 32）——同一 app-version 通道下旧套壳用户会被提示升级到原生 APK。

令牌桥接机制（配对代码：`frontend/src/main-mobile.js` 顶部的 bootstrap 段）：

1. 原生端拦截 `/m/` 路径族的 **HTML 文档请求**（静态资源与 XHR 放行），OkHttp 取回后在 `<head>` 后注入
   `window.__KB_NATIVE_TOKEN="<token>"`
2. `main-mobile.js` 入口最先执行：发现该变量即写入 `localStorage.kb_token`——发生在 Vue 路由守卫
   读令牌之前，无竞态；未登录时注入 `null`，Web 侧同步清除
3. Capacitor 套壳与纯 Web 不注入该变量（`'in'` 判定跳过），行为零变化

P1 桥接细节：

- **文件选择**：`onShowFileChooser` → 系统选择器；`capture`（拍照页）→ `ACTION_IMAGE_CAPTURE` +
  FileProvider 落盘 cache（manifest 已注册 provider，未声明 CAMERA 权限，intent 拍照无需运行时授权）
- **SSE**：okhttp-sse 长连接，断线 10s 自动重连；未读数以服务端为准（进消息页校准）

## 构建

前置：Android Studio（或 JDK 17+ 命令行 Gradle）；Android SDK（本机在 `%LOCALAPPDATA%\Android\Sdk`，
`local.properties` 已写好，**不入库**，换机器按实际路径改）。

```bash
cd mobile-native
gradlew.bat assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

- Gradle 8.14.3（腾讯镜像）/ AGP 8.13.0 / Kotlin 2.0.21 / Compose BOM 2024.09.03，
  依赖走阿里云 Maven 镜像，与 `mobile/android` 同一套镜像策略
- **applicationId 已转正为 `com.kb.kbplatform`（v0.4 起）**：直接覆盖安装升级旧 Capacitor 版；
  ⚠️ 覆盖安装要求签名一致——正式分发请用旧版同一 `kb-release.jks` 出 release 包，
  debug 签名与旧 release 版互斥（会安装失败，需先卸载）
- 首次构建会拉取大量依赖，耐心等；IDE 打开请用 Android Studio 选 `mobile-native` 目录

## 后端地址

- 默认 `http://10.0.2.2:8080`（模拟器访问宿主机）
- 真机联调：App 内「我的 → 服务器地址」改成电脑局域网 IP，**无需重新打包**
- 生产：改成 `https://你的域名`；与 Web 端一样，仓库内不存任何公网地址

## 已知边界（v0.4 暂不做）

- 离线草稿队列**已迁移**（拍照/编辑断网暂存 →「我的 → 草稿箱」批量/单条补传）；离线正文中的外链图片不可见（文字完整）
- WebView 内下载类动作（a[download]）未接系统下载
- 交互一致性：标记完成、归档、恢复版本、封禁/删除、退订等只在接口成功后才改本地列表并提示成功；失败保留原状并给出原因。列表 key 一律带位置兜底，id 缺失或重复不再触发崩溃；上传临时文件名去掉路径分隔符并加随机前缀。AI 流式回答改为取消即中断连接，事件类型在每个事件边界重置。

- 错题本 / 学习计划等长尾页面仍走 WebView（课程中心已原生化：列表 / 章节 / 完成打卡）；正式考试防泄题细节（如切后台策略）以 Web 版为准
- 生物识别解锁未做（仅 PIN）

## 迁移路线图

1. ~~**P0 体验验证**：竖切（登录/文档库/阅读）~~ ✅
2. ~~**P1 桥接补全**：文件选择 + 相机桥接；SSE 通知~~ ✅
3. ~~**P2 高频页原生化**：搜索、AI 问答、复习卡片、考试练习~~ ✅
4. ~~**P3 原生能力**：系统分享导入、离线缓存、应用内更新~~ ✅
5. ~~**P4 转正**：applicationId 替换、断点续传、正文级离线、应用锁、考试中心~~ ✅

**后续候选**（无排期）：离线草稿队列、生物识别、推送、AI Agent 模式原生化

## 加载三态（Loading / Empty / Error）

- 公共组件：`KbLoading` / `KbEmpty` / **`KbError(text, onRetry)`**（`ui/common/Kb.kt`，错误态与空态同构但多一个「重试」）。
- 约定：请求失败**不再伪装成「暂无数据」**，而是错误态 + 重试（走各屏自己的 `load()` / `tick++`）。
- 已覆盖：待整理箱、我的考试、积分明细、排行榜、我的订阅、回收站、我的摘录、全部文档、徽章成就、文件中心、课程中心。
  其余（课程详情 / 版本 / 部分管理页）仍复用空态，后续统一。
- 排行榜改用 `itemsIndexed` 取名次（原来 `items.indexOf(r)` 在 LazyColumn 里是 O(n²)）。

## 管理界面复核加固

- 所有管理操作（改角色、审核通过/驳回、公告增删改/置顶/启停、举报处置、违禁词删除）只在接口成功后更新本地列表，失败给明确提示，不再出现「界面成功、服务端没变」
- 列表 key 加位置兜底，id 缺失或重复时不会再抛 Compose 重复 key 崩溃；驳回原因必填
- 错误态在每次加载前清空，偶发网络失败不再让整页卡在错误提示；操作提示与列表错误分开，保存失败不会再吞掉整个列表
- 运营看板按权限显隐，无统计权限时不再请求并展示聚合数据；违禁词库切库不再重复请求，旧库响应不会覆盖新库列表

## 黑盒探测工具（tools/）

- `probe_api.py`：全链路 API 合同探测——**含滑动验证码自动求解**（拼块 alpha 模板 ×
  背景 ×0.765 压暗的乘性匹配，形状无关，对本地与云端各 23/24 PASS）。
  用法：`python tools/probe_api.py [base_url] [user] [pass]`
- `FixAiModel.java`：修复 `kb_ai_model` 旧数据 NULL primitive 列导致 AI 全线 400 的问题
  （`contextWindow`/`vision` 为 NULL 的历史行；本地库已修，云库如遇到同样报错执行同款 SQL）

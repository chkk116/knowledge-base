# 知识库平台（Vue 3 + Spring Boot + 实时协同 + Electron 桌面端 + Capacitor 移动端）

<div align="center">

![License](https://img.shields.io/badge/License-MulanPSL--2-blue)
![Java](https://img.shields.io/badge/Java-21%2B-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.x-green)
![Vue](https://img.shields.io/badge/Vue-3-brightgreen)
![MySQL](https://img.shields.io/badge/MySQL-8-blue)
![Gitee Stars](https://img.shields.io/gitee/stars/beixun/knowledge-base)
![Last Commit](https://img.shields.io/gitee/last-commit/beixun/knowledge-base/master)

</div>

一站式企业内网知识库：**文章/文档、评论互动、实时协同编辑、文档级细粒度权限、考试培训、课程学习、积分徽章排行、通知公告、AI 问答（RAG）**，配 **桌面客户端（Electron）** 与 **移动端（Capacitor / Android APK）**。

技术栈：

| 层 | 技术 |
|---|---|
| 前端 | Vue 3（`<script setup>`）+ Vite 8 + Pinia + Vue Router + Element Plus（按需引入）+ wangEditor / Tiptap |
| 后端 | Spring Boot（Java 21）多包模块化单体 + Spring Data JPA + MySQL 8 |
| 实时协同 | Tiptap + Yjs + Hocuspocus，中继服务 `collab-relay/`（Node + mysql2，与后端共用 MySQL） |
| AI | 智谱 GLM（`glm-4-flash`，OpenAI 兼容端点，国内直连），RAG 检索增强问答 |
| 桌面端 | Electron 33，纯 Node 内置模块本地托管 + 反代后端，零框架改动套壳 |
| 移动端 | Capacitor 8（Android），远端模式直接加载内网后端 |
| 部署 | `deploy/`：env.prod / env.test + nginx 模板，HTTPS + 静态预压缩（.gz/.br） |

## 目录结构
```
knowledge-base/
├── backend/                  # Spring Boot 后端（com.kb.kbplatform）
│   └── src/main/java/com/kb/kbplatform/
│       ├── bootstrap/        # DataInitializer：启动种子（测试账号 + 示例文档）
│       ├── collab/           # 协同编辑相关
│       ├── config/           # 应用配置
│       ├── domain/           # JPA 实体（Document / User / 考试 / 课程 / 徽章…）
│       ├── repository/       # Spring Data JPA 仓储
│       ├── security/         # PasswordUtil(salt+SHA256) / TokenUtil(HMAC-SHA256) / AuthFilter
│       ├── service/          # 业务服务（文档/搜索/鉴权/AI RAG/评论/敏感词/验证码…）
│       └── web/              # REST 控制器
├── frontend/                 # Vue 3 前端（30+ 页面，1024/640 双断点响应式）
│   └── src/
│       ├── views/            # Home/Login/AiChat/DocShare*/Exam*/Course*/admin/* 等
│       ├── components/       # SlideCaptcha(滑动验证码) / RichEditor / CommentSection / EmojiPanel…
│       ├── responsive.css    # 响应式基础设施（表格卡片化 v-stack-table、浮层限宽、表单标签置顶）
│       └── store/ utils/ api/
├── collab-relay/             # Hocuspocus 协同中继（Node）：verifyToken 校验 + Yjs 持久化到 MySQL
├── desktop/                  # Electron 桌面客户端（build-web.js 复用前端构建；原生登录框+安全验证弹窗）
├── mobile/                   # Capacitor 8 Android 工程（sync-web.js 同步前端产物）
├── deploy/                   # 生产/测试部署：env.prod、env.test、nginx-example.conf
├── db/                       # init.sql + v113~v118 增量 SQL（回收站/版本/禁词库等）
├── docker-compose.yml        # 可选基础设施（默认不启动）
└── pom.xml                   # Maven 聚合工程
```

## 核心功能

- **文档与知识空间**：文章 CRUD、分类、全文搜索、附件上传、回收站、版本历史、导出
- **实时协同编辑**：多人文档同编（Tiptap + Yjs），文档级细粒度权限（可读/可编/可管理）
- **社区互动**：评论（敏感词过滤 + 词边界匹配）、点赞收藏订阅、积分、徽章、排行榜、签到历史
- **培训考核**：题库与在线考试（Exam*）、课程学习（Course*）、禁词库管理
- **运营**：公告栏、通知中心、用户/角色/权限管理（权限码四处同步）、审计日志、功能开关
- **安全**：滑动拼图验证码（服务端 320×160 基准 + 容差校验 + 轨迹检测 + IP 限流）、HMAC-SHA256 令牌、salt+SHA-256 密码、安全响应头、路径穿越防护
- **AI 问答**：智谱 GLM-4-Flash（免费模型）+ RAG；API Key 只经环境变量注入，仓库零密钥

## 快速开始（本地开发）

### 1. 数据库（本地 MySQL 8，唯一前置依赖）
执行 `db/init.sql` 建库 `kb`（utf8mb4），连接串 `jdbc:mysql://localhost:3306/kb`（账号密码见 `backend/src/main/resources/application.yml`）。
业务表由 JPA（`ddl-auto: update`）自动创建；`db/v113~v118_*.sql` 为功能增量，按需执行。

### 2. 启动后端
IDEA 打开根目录（自动识别 Maven 聚合工程），运行预置配置 `Backend (Maven spring-boot:run)`（:8080），或：

```bash
cd backend && mvn spring-boot:run
```

**启用 AI 问答**（可选）：启动前注入环境变量即可，无需改代码：
```bash
set KB_ZHIPU_API_KEY=你的智谱Key        # Windows
export KB_ZHIPU_API_KEY=你的智谱Key     # Linux/macOS
```
未注入时 AI 模块自动降级不可用，其余功能不受影响。仓库内**不存任何真实 Key**。

### 3. 启动前端
```bash
cd frontend && npm install && npm run dev   # http://localhost:5173（/api 代理到 :8080）
```

### 4.（可选）启动协同中继
```bash
cd collab-relay && npm install && npm start
```
ws 地址跟随页面协议：https 同源走 `/collab/`，http 走 `ws://主机:1234`。

### 内置测试账号

| 用户名 | 密码 | 角色 |
|--------|------|------|
| `admin` | `Admin@123` | ADMIN |
| `zhangsan` | `Zhang@123` | USER |
| `lisi` | `Li@123` | USER |

## 桌面客户端（Electron）

```bash
cd desktop
npm install
npm run pack        # 先复用前端构建到 desktop/web，再出安装包 + 免安装版
```

- 产物：`desktop/dist-electron/知识库 Setup 1.0.0.exe`（安装版）/ `win-unpacked/知识库.exe`（免安装）
- 特性：自绘深蓝紫标题栏、原生登录框（点击登录后弹出安全验证弹窗，与 Web 端一致）、单实例锁（二次启动唤起已有窗口）、端口占用中文提示、系统托盘常驻
- 后端地址默认 `http://localhost:8080`，环境变量 `KB_BACKEND_URL` / `KB_BACKEND_WS` 可覆盖

## 移动端（Capacitor / Android）

```bash
cd mobile
npm install
node sync-web.js            # 从 desktop/web 同步前端产物到 web-app/（过滤 .gz/.br）
npx cap copy android
cd android && gradlew.bat assembleDebug
```

- 产物：`mobile/android/app/build/outputs/apk/debug/app-debug.apk`
- 远端模式：App 直接加载内网后端（`capacitor.config.json` 的 `server.url`，默认 `http://YOUR_BACKEND_IP:8080`，改 IP 后重打包）
- 已放行明文 HTTP（`usesCleartextTraffic` + cleartext:true），适配纯内网环境
- 构建镜像：gradle 发行版走腾讯镜像、依赖走阿里云 maven（`dl.google.com` 内网不可达）；详见 `mobile/android/build.gradle` 与 `gradle/wrapper/gradle-wrapper.properties`

## 生产部署

`deploy/` 提供生产/测试两套环境文件（`env.prod` / `env.test`）与 nginx 模板（`nginx-example.conf`，HTTPS + 静态预压缩）。
前端构建产物带 `.gz/.br` 预压缩；后端启动需 `ZHIPU_API_KEY` 等环境变量（见 env 文件）。协同 ws 生产走 https 同源 `/collab/`。

## 鉴权与安全要点

- **密码**：随机 salt(16B hex) + SHA-256，存储 `salt:sha256hex`；安全边界在 TLS + 服务端哈希 + 验证码限流
- **令牌**：HMAC-SHA256 自签（零第三方依赖），`Authorization: Bearer <token>`，默认 24h
- **验证码**：滑动拼图，correctX/Y 仅存服务端；容差 ±6px，轨迹 ≥3 点且 ≥120ms；环回地址免 IP 锁定
- **敏感词**：评论过滤，≤4 位纯 ASCII 词要求词边界匹配，命中返回 400 + 中文 error
- **公开资源**：`/uploads`、`/api/thumb` 在 AuthFilter 白名单放行（ThumbController 内做穿越防护）

## License

见 [LICENSE](LICENSE)。

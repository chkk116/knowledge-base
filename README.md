# 知识库平台

<div align="center">

**一站式企业内网知识库 · 文档 · 实时协同 · 考核培训 · AI 问答**

[![License](https://img.shields.io/badge/License-AGPL--3.0-blue)](#-开源许可与商业授权)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.x-green)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3-brightgreen)](https://vuejs.org)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18-blue)](https://www.postgresql.org)
[![Electron](https://img.shields.io/badge/Electron-33-47848F)](https://www.electronjs.org)
[![Gitee Stars](https://img.shields.io/gitee/stars/beixun/knowledge-base)](https://gitee.com/beixun/knowledge-base/stargazers)
[![Last Commit](https://img.shields.io/gitee/last-commit/beixun/knowledge-base/master)](https://gitee.com/beixun/knowledge-base/commits/master)

</div>

---

## ✨ 功能一览

### 📝 文档与知识
- **文章 / 文档**：CRUD、分类标签、附件、回收站、版本历史、DOCX 导入、导出
- **全文搜索**：Meilisearch 毫秒级检索 + MySQL LIKE 自动降级，索引与数据库可见性双向核对
- **实时协同编辑**：Tiptap + Yjs + Hocuspocus，多人同编、离线恢复
- **共享文档**：文档级细粒度权限（可读 / 可编 / 可管理）、团队空间、扩容审批

### 👥 社区与互动
- 评论（敏感词过滤 + 词边界匹配）、点赞收藏订阅、积分、徽章穿戴、排行榜、签到
- 个人主页 / 用户主页（自定义背景横幅）、通知中心（SSE 实时推送）、公告栏

### 🎓 培训考核
- 题库 + 在线考试（防泄题、并发提交约束）、课程学习、章节评论、证书

### 🛠️ 管理后台
- 用户 / 角色 / 权限码 / 部门管理，细粒度权限码集中管理（`Perm` 白名单）
- 审核中心、资产管理（借用归还 / Excel 导出）、数据看板、审计与登录日志、公告管理
- **系统外观**：站点 Logo / 资料页背景图自定义，登录页二维码、全局水印、功能开关（维护页 / 隐藏双模式）

### 🤖 AI 问答
- 智谱 GLM（OpenAI 兼容端点，国内直连）+ RAG 检索增强：Meili 命中 → 数据库可见性核对 → 注入模型上下文
- API Key 仅经环境变量注入，**仓库零密钥**；未配置时 AI 模块自动降级，其余功能不受影响

### 🔐 安全
- 滑动拼图验证码（服务端基准 + 容差 + 轨迹检测 + IP 限流）、HMAC-SHA256 自签令牌、salt+SHA-256 密码
- 管理员脚本登录实时告警（SSE 通知）、细粒度管理接口鉴权（过滤器 + 控制器双层）、安全响应头、路径穿越防护

---

## 🧰 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3（`<script setup>`）· Vite · Pinia · Vue Router · Element Plus（按需引入）· wangEditor / Tiptap |
| 后端 | Spring Boot 4（Java 21）模块化单体 · Spring Data JPA · **PostgreSQL 18**（兼容 MySQL 8，双驱动） |
| 实时协同 | Tiptap + Yjs · Hocuspocus 中继（`collab-relay/`，Node + pg，与后端共用 PostgreSQL） |
| 搜索 | Meilisearch（可选，`docker-compose.yml`）· MySQL LIKE 降级兜底 |
| AI | 智谱 GLM（OpenAI 兼容 `/chat/completions`），RAG 检索增强 |
| 桌面端 | Electron 33：纯 Node 本地托管 + 反代后端，零框架改动套壳 |
| 移动端 | Capacitor 8（Android），远端模式直连内网后端 |
| 部署 | `deploy/`：env.prod / env.test + nginx 模板，HTTPS + 静态预压缩（.gz / .br） |

## 📂 目录结构

```
knowledge-base/
├── backend/                  # Spring Boot 后端（com.kb.kbplatform）
│   └── src/main/java/com/kb/kbplatform/
│       ├── bootstrap/        # DataInitializer：启动种子（测试账号 + 示例文档）
│       ├── collab/           # 协同编辑后端支持
│       ├── config/           # 应用配置
│       ├── domain/           # JPA 实体（Document / User / Exam / Asset / Badge…）
│       ├── repository/       # Spring Data JPA 仓储
│       ├── security/         # AuthFilter / TokenClaims / TokenUtil / Perm 权限码集中管理
│       ├── service/          # 业务服务（文档/搜索/AI RAG/资产/考试/评论/水印/外观…）
│       └── web/              # REST 控制器（含 admin/* 管理接口）
├── frontend/                 # Vue 3 前端（30+ 页面，1024/640 双断点响应式）
│   └── src/
│       ├── views/            # Home / Login / AiChat / DocShare* / Exam* / Course* / admin/*
│       ├── components/       # SlideCaptcha / RichEditor / CommentSection / WornBadgeStrip…
│       ├── utils/            # time / text / appearance(外观共享缓存) / theme / level…
│       ├── responsive.css    # 响应式基础设施（表格卡片化、浮层限宽、表单标签置顶）
│       └── store/ api/
├── collab-relay/             # Hocuspocus 协同中继（Node）：verifyToken 校验 + Yjs 快照持久化到 PostgreSQL
├── desktop/                  # Electron 桌面客户端（build-web.js 复用前端构建）
├── mobile/                   # Capacitor 8 Android 工程（sync-web.js 同步前端产物）
├── deploy/                   # 生产/测试部署：env.prod、env.test、nginx-example.conf
├── db/                       # init.sql + v113~v123 增量 SQL（并发约束/回收站/版本/禁词库等）
├── docker-compose.yml        # 可选基础设施（Meilisearch 等，默认不启动）
└── pom.xml                   # Maven 聚合工程
```

## 🚀 快速开始（本地开发）

### 1. 数据库（唯一前置依赖）

PostgreSQL 18（推荐，MIT 协议可自由分发），默认连接 `jdbc:postgresql://localhost:5432/kb`（账号 `kb/kb`，见 `application.yml`）。
业务表由 JPA（`ddl-auto: update`）自动创建；`db/*.sql` 为结构约束与数据迁移增量脚本。
切回 MySQL：`application.yml` 数据源换回 `jdbc:mysql://localhost:3306/kb` 即可（双驱动均已引入）。

### 2. 启动后端

IDEA 打开根目录（自动识别 Maven 聚合工程），运行预置配置 `Backend (Maven spring-boot:run)`（`:8080`），或：

```bash
cd backend && mvn spring-boot:run
```

**启用 AI 问答**（可选）：启动前注入环境变量，无需改代码：

```bash
set KB_ZHIPU_API_KEY=你的智谱Key        # Windows
export KB_ZHIPU_API_KEY=你的智谱Key     # Linux / macOS
```

### 3. 启动前端

```bash
cd frontend && npm install && npm run dev   # http://localhost:5173（/api 代理到 :8080）
```

### 4. 协同中继（随后端自动启动，无需手动操作）

后端启动时通过 `CollabRelayLauncher` 自动拉起 collab-relay（Node/Hocuspocus）进程，停服时一并销毁——**无需手动 `npm start`**（前提：部署机已装 Node；依赖缺失会自动 `npm install`）。

相关配置（`application.yml` 的 `kb.collab.*`）：`enabled`（默认 true，false 关闭内嵌中继）、`port`（ws 端口 1234）、`relay-dir`、`auto-install`、快照落库间隔。
仅当**独立部署中继**（与后端不同机）时才需要手动运行：

```bash
cd collab-relay && npm install && npm start
```

ws 地址跟随页面协议：HTTPS 同源走 `/collab/`，HTTP 走 `ws://主机:1234`。

### 内置测试账号

| 用户名 | 密码 | 角色 |
|---------|-----------|-------|
| `admin` | `Admin@123` | ADMIN |
| `zhangsan` | `Zhang@123` | USER |
| `lisi` | `Li@123` | USER |

## 🖥️ 桌面客户端（Electron）

```bash
cd desktop
npm install
npm run pack        # 先复用前端构建到 desktop/web，再出安装包 + 免安装版
```

- 产物：`desktop/dist-electron/知识库 Setup 1.0.0.exe`（安装版）/ `win-unpacked/知识库.exe`（免安装）
- 特性：自绘深蓝紫标题栏、原生登录框 + 安全验证弹窗、单实例锁、端口占用中文提示、系统托盘常驻
- 后端地址默认 `http://localhost:8080`，环境变量 `KB_BACKEND_URL` / `KB_BACKEND_WS` 可覆盖

## 📱 移动端（Capacitor / Android）

```bash
cd mobile
npm install
node sync-web.js            # 从 desktop/web 同步前端产物到 web-app/（过滤 .gz/.br）
npx cap copy android
cd android && gradlew.bat assembleDebug
```

- 产物：`mobile/android/app/build/outputs/apk/debug/app-debug.apk`
- 远端模式：App 直接加载内网后端（`capacitor.config.json` 的 `server.url`，改 IP 后重打包）
- 已放行明文 HTTP（`usesCleartextTraffic`），适配纯内网环境
- 国内镜像：Gradle 走腾讯镜像、依赖走阿里云 Maven（`dl.google.com` 内网不可达）

## 📦 生产部署

`deploy/` 提供生产 / 测试两套环境文件（`env.prod` / `env.test`）与 nginx 模板（`nginx-example.conf`：HTTPS + 静态预压缩）。
前端构建产物带 `.gz/.br` 预压缩；后端启动需 `KB_ZHIPU_API_KEY` 等环境变量（见 env 文件）。协同 ws 生产走 HTTPS 同源 `/collab/`。

## 🔏 鉴权与安全要点

- **密码**：随机 salt(16B hex) + SHA-256，存储 `salt:sha256hex`；安全边界在 TLS + 服务端哈希 + 验证码限流
- **令牌**：HMAC-SHA256 自签（零第三方依赖），`Authorization: Bearer <token>`，默认 24h，支持令牌版本号批量失效
- **验证码**：滑动拼图，correctX/Y 仅存服务端；容差 ±6px，轨迹 ≥3 点且 ≥120ms；环回地址免 IP 锁定
- **权限模型**：细粒度权限码（`Perm` 集中白名单）+ 角色聚合 + 全权限视同管理员；资源审批类接口刻意仅认真实 ADMIN
- **敏感词**：评论过滤，≤4 位纯 ASCII 词要求词边界匹配，命中返回 400 + 中文 error
- **公开资源**：`/uploads`、`/api/thumb` 在 AuthFilter 白名单放行（ThumbController 内做穿越防护）

## 📄 开源许可与商业授权

本仓库**全部代码**基于 **GNU AGPL-3.0** 开源（完整文本见 [LICENSE](LICENSE)）：

- ✅ **个人 / 学习 / 研究使用**：自由复制、部署、修改，无需任何授权
- ✅ **纯部署不改代码**：企业内网直接部署使用官方未修改版本，无额外义务
- ⚠️ **修改后对外提供服务**（含企业内网部署修改版）：须依 AGPL-3.0 向服务使用者**开源全部修改**

### 需要商业授权的场景

以下诉求与 AGPL 开源义务冲突时，可联系作者获取**商业授权**（双许可模式，同一份代码两种许可任选）：

- 将本系统或其修改版整合进**不愿开源的闭源商业产品**
- 希望在内网部署**修改版**但不履行 AGPL 开源义务
- 定制开发、私有化交付与技术支持

商业授权与 AGPL 社区版功能完全一致，购买即获得对应版本的开源义务豁免。

## 📄 License

本仓库源码：[GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）。商业授权为双许可模式下的替代许可，详见上文。

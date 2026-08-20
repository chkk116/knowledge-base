# 知识库平台（Vue 3 + Spring Boot 4.1，本地 MySQL，AI/RAG 预留）

技术栈：**Vue 3 + Vite** 前端，**Spring Boot 4.1 (Java 21)** 后端；数据用 **本地 MySQL 8**，
检索默认走 **本地 MySQL（LIKE，开箱即用，零中间件）**。**Meilisearch / MinIO 等中间件当前未纳入编译**，
作为后续「docker 服务」阶段的可选增强（见文末「可选增强：Meilisearch / MinIO」）。
**AI/RAG 问答为预留模块**——当前未引入 Spring AI 依赖（Spring AI 2.0 尚无稳定正式版，会导致依赖解析失败），待后续接入。

> **零中间件即可运行**：默认配置下只需要一个本地 MySQL，搜索框、文档 CRUD、分类过滤全部可用，**不需要 Docker**。

## 目录结构
```
knowledge-base/
├── docker-compose.yml        # 可选基础设施（meilisearch / minio / ollama，默认都不启动）
├── db/init.sql               # 本地 MySQL 建库脚本
├── backend/                  # Spring Boot 4.1 多包模块化单体
│   └── src/main/java/com/kb/kbplatform/
│       ├── config/           # 应用配置（当前仅数据库相关；Meili/MinIO 配置已移出默认编译）
│       ├── security/         # 登录鉴权：PasswordUtil(salt+SHA256) / TokenUtil(HMAC-SHA256) / AuthFilter
│       ├── bootstrap/         # DataInitializer：启动种子（测试账号 + 示例文档）
│       ├── domain/           # Document / User 实体
│       ├── repository/       # JPA 仓储（含 MySQL 兜底检索）
│       ├── service/          # Document / Search / Auth / Rag(预留) / File
│       └── web/              # REST 控制器
└── frontend/                 # Vue 3 + Vite + Pinia + Element Plus
```

## 在 IntelliJ IDEA 中导入

推荐**直接 Open 整个 `knowledge-base` 文件夹**：

1. IntelliJ IDEA → `File` → `Open` → 选择 `knowledge-base/` 目录。
2. IDEA 会自动识别根 `pom.xml`（聚合工程，含 `backend` 模块）作为 **Maven 项目**，并识别 `frontend/package.json` 作为 **Node/JS 项目**。
3. 等待 Maven 依赖解析完成（首次会下载 Spring Boot / JPA / MySQL 驱动等；**Meilisearch、MinIO 已不纳入默认依赖**，编译不再需要它们）。
4. 运行后端：用预置的 `Backend (KbPlatformApplication)` 或 `Backend (Maven spring-boot:run)` 配置（推荐用 Maven 那个，自动管依赖与编译）。
5. 运行前端：用 `Frontend (npm run dev)` 配置，或 `frontend/` 终端 `npm install && npm run dev`。
6. 浏览器访问 `http://localhost:5173`。

> JDK 需 21（Spring Boot 4.1 支持 17/21，建议 21）。若本机没装 Maven，IDEA 可用内置 Maven 构建（无需 Maven Wrapper）。
> `Frontend` 配置若报「Node interpreter not configured」：在 `Settings → Languages & Frameworks → Node.js` 指定本机 Node（建议 20+）。

### 已内置运行配置（Run/Debug Configurations）

本项目已在 `.idea/runConfigurations/` 预置 3 个配置，打开工程后 IDEA 顶栏配置下拉即可直接选用：

| 配置名 | 类型 | 说明 |
|--------|------|------|
| `Backend (KbPlatformApplication)` | Application | 启动 `com.kb.kbplatform.KbPlatformApplication`（:8080），模块 `kb-platform` |
| `Backend (Maven spring-boot:run)` | Maven | `mvn -pl backend spring-boot:run`，自动处理 classpath 与编译（首选）|
| `Frontend (npm run dev)` | npm | 在 `frontend/` 执行 `npm run dev`（:5173）|
| `Database (docker-compose)` | Docker Compose | **可选**：启用 Meilisearch/MinIO 时才需要，见文末「可选增强」|

**推荐启动顺序**：`Backend` → `Frontend`（不需要先起 Database，因为数据库用的是你本机 MySQL）。

## 数据库（本地 MySQL 8）

本工程默认使用**本地 MySQL**，无需 Docker：

1. 本机安装 MySQL 8（未装可用：MySQL Installer、XAMPP、phpStudy、或 Docker 临时起一个 MySQL 容器均行）。
2. 用有权限的账号执行 `db/init.sql` 建库 `kb`（utf8mb4）：
   ```sql
   CREATE DATABASE IF NOT EXISTS kb CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
   -- 应用账号（与 application.yml 对应）：
   -- CREATE USER IF NOT EXISTS 'kb'@'localhost' IDENTIFIED BY 'kb';
   -- GRANT ALL PRIVILEGES ON kb.* TO 'kb'@'localhost';
   -- FLUSH PRIVILEGES;
   ```
3. 连接串：`jdbc:mysql://localhost:3306/kb`，账号/密码 `kb/kb`。
   若你本机 MySQL 账号不同，改 `backend/src/main/resources/application.yml` 的 `spring.datasource.username/password` 即可。
4. 业务表 `kb_document` / `kb_user` 由 JPA（`ddl-auto: update`）**自动创建**，无需手工建表。
5. 应用启动时会自动写入 5 条示例文档（受 `kb.seed.enabled` 控制），搜索框开箱即可演示。

## 快速开始

### 1. 准备数据库（只需这一步）
```bash
# 在本地 MySQL 客户端执行 db/init.sql 建库（见上）
```

### 2.（可选）启用检索增强 / 对象存储 —— 留到「docker 阶段」再做
当前默认编译**不含** Meilisearch / MinIO，仅本地 MySQL 即可跑通搜索框与文档 CRUD。
待你准备引入这些中间件时（docker 阶段），需在 `pom.xml` 恢复对应依赖，并把
`MeiliConfig` / `MinioConfig` 以及 `SearchService` / `FileService` 的对应实现加回
（工程已保留接口与说明，详见文末「可选增强：Meilisearch / MinIO」）。

### 3. 启动后端
```bash
cd backend && mvn spring-boot:run        # 或 IDEA 内跑 Backend 配置
```

### 4. 启动前端
```bash
cd frontend && npm install && npm run dev   # http://localhost:5173 （/api 已代理到 :8080）
```

打开 `http://localhost:5173`，搜索框直接能搜到 5 条示例文档；点结果看详情；「后台管理」面板可添加文档。

## 核心接口
| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/docs` | 文档列表 |
| GET  | `/api/docs/categories` | 分类列表（前端下拉筛选联动）|
| POST | `/api/docs` | 新建文档（写入本地 MySQL）|
| GET  | `/api/docs/{id}` | 文档详情 |
| GET  | `/api/search?q=` | 关键词检索（基于本地 MySQL LIKE）|
| POST | `/api/auth/login` | 登录，`{ "username", "password" }` → 返回 `token` / `username` / `role` / `displayName` |
| GET  | `/api/auth/me` | 当前登录用户信息（需令牌；令牌失效返回 401）|
| POST | `/api/auth/register` | 注册账号，`{ "username","password","role?","displayName?" }`（公开，密码≥6 位）|
| POST | `/api/ai/ask` | RAG 问答，`{ "question": "..." }`；模型未启用时返回提示文案 |
| POST | `/api/files` | 附件上传（当前为占位提示；启用 MinIO 见文末可选增强）|

> **鉴权规则**：`POST /api/docs`（新建文档）等写入类接口需携带 `Authorization: Bearer <token>`；
> 搜索、文档查看为公开接口。令牌由 `kb.security.token-secret` 签发，默认有效期 24h（`kb.security.token-ttl`）。

## 登录与鉴权（已内置）

账号体系与鉴权**已可用**，前端首页右上角有「登录 / 头像」，后台管理需登录后可见。

**密码加密**：采用 **随机 salt + SHA-256（256 位）**，存储格式 `salt(hex):sha256(hex)`，见 `security/PasswordUtil.java`。
**令牌**：用原生 `HMAC-SHA256` 自签令牌（不引入第三方 JWT 依赖，零额外 jar），格式 `base64url(payload).sig`，payload 含 `username|role|exp`，见 `security/TokenUtil.java`。
**鉴权**：`security/AuthFilter` 拦截受保护接口校验 `Authorization: Bearer <token>`，公开接口（搜索、文档查看、登录、注册）放行。

**启动后会自动写入测试账号**（见 `bootstrap/DataInitializer.java`，受 `kb.seed.enabled` 控制）：

| 用户名 | 密码 | 角色 |
|--------|------|------|
| `admin` | `Admin@123` | ADMIN |
| `zhangsan` | `Zhang@123` | USER |
| `lisi` | `Li@123` | USER |

> 生产环境请修改 `application.yml` 的 `kb.security.token-secret` 为足够长的随机串，并关闭公开注册（或改为仅管理员可建账号）。

## 启用 AI 能力（接模型）

> 前置说明：当前 `backend/pom.xml` **未包含 Spring AI 依赖**（Spring AI 2.0.0 在 Maven Central 上仅有 -Mx 里程碑版，正式版需自行确认版本号）。
> 启用前请先在 Maven Central 确认一个 **RELEASE 正式版** 的 `spring-ai-bom` 版本号，再按下述步骤引入。

**1. 在 `backend/pom.xml` 引入 Spring AI 依赖**
取消文件内「AI / RAG 预留」注释块，并把版本换成你确认过的正式版，例如：
```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>1.0.0</version>   <!-- 以 Maven Central 实际 RELEASE 为准 -->
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-ollama</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
  </dependency>
</dependencies>
```
（向量库可选 pgvector 或 MySQL + 向量扩展；按正式版 API 将 `RagService` / `SearchService` 的预留实现替换为真实逻辑。）

**2. 启动模型服务**（本地 Ollama 示例）
```bash
docker compose --profile ai up -d ollama
ollama pull nomic-embed-text     # 向量化（embedding）
ollama pull qwen2.5:7b           # 中文对话（可按显存换更小模型）
```

**3. 打开 `application.yml` 的开关**
```yaml
kb:
  ai:
    enabled: true                # 总开关
```

**4. 重启后端**，并将 `RagService` 替换为真实 RAG 实现后，`/api/ai/ask` 即可进行问答。
> 语义检索（Meilisearch / 向量库）为独立的可选增强，启用方式见文末「可选增强：Meilisearch / MinIO」。

> 想用云端模型：引入 `spring-ai-starter-model-openai`，配置 `spring.ai.openai.api-key` 即可。

## 可选增强：Meilisearch / MinIO（docker 阶段再加）

本阶段为「本地 MySQL 即可运行」，以下两个中间件**未纳入默认编译依赖**，避免拉取不到对应 jar 时编译失败（曾因 `meilisearch-java:0.17.0` 拉取不到报“程序包 com.meilisearch.sdk 不存在”）。
待你准备用 docker 起这些服务时，按如下步骤恢复：

**Meilisearch（中文分词 / 容错检索）**
1. `backend/pom.xml` 取消注释并加入：
   ```xml
   <dependency>
       <groupId>com.meilisearch</groupId>
       <artifactId>meilisearch-java</artifactId>
       <version>0.17.0</version>   <!-- 以 Maven Central 实际可解析版本为准 -->
   </dependency>
   ```
2. 恢复 `config/MeiliConfig.java`（创建 `com.meilisearch.sdk.Client` bean）与 `SearchService` 中的 Meili 检索路径（与 MySQL 结果做 RRF 融合）。
3. `application.yml` 加回 `kb.meili.enabled/host/api-key/index`，`docker compose --profile optional up -d meilisearch` 或本机安装 Meilisearch。

**MinIO（附件对象存储）**
1. `backend/pom.xml` 加入：
   ```xml
   <dependency>
       <groupId>io.minio</groupId>
       <artifactId>minio</artifactId>
       <version>8.5.7</version>
   </dependency>
   ```
2. 恢复 `config/MinioConfig.java` 与 `FileService` 中的 `io.minio.MinioClient` 上传实现。
3. `application.yml` 加回 `kb.minio.*`，`docker compose --profile optional up -d minio`。

> 注意：恢复时请先确认对应依赖在 Maven Central 上的**可解析版本号**（本工程曾因 `0.17.0` 拉取不到导致编译报“程序包不存在”）。

## 演进建议
- 当前为「模块化单体」，模块边界清晰；需要独立扩容时，将 `service/web` 按 `content / search / ai / file` 拆为 Spring Cloud 微服务（Gateway + Nacos）。
- 鉴权已内置（salt+SHA256 密码 + HMAC-SHA256 令牌，自研 Filter，无 Spring Security 依赖）；如需更标准方案可替换为 Spring Security + JWT。
- 版本管理、评论协作、重排（Reranker）可作为下一步增强。
- 语义检索如需更强效果，可引入 **重排模型**（bge-reranker）做两阶段精排。

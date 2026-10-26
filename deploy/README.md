# deploy · 依赖服务

Redis / Meilisearch 的编排与配置。**先看本机现状再决定用哪条路**。

## 一、当前服务器实际情况（2026-10-26 核对）

| 项 | 状态 |
|---|---|
| 宝塔 Redis | **已安装并运行**：`127.0.0.1:6379`，**无密码**，`/www/server/redis` |
| Docker | **未安装**（`which docker` 为空） |
| 磁盘 | 40G 用了 30G（**80%**），剩 7.6G |
| 内存 | 3.7G 总，可用约 980M |

结论：**Redis 不用再装，直接用宝塔那个**；Meilisearch 想上就得先装 Docker，
而它常驻约 300MB 内存 + 镜像 500MB 左右，当前磁盘/内存都比较紧，建议等扩容或清理后再上。

## 二、Redis：两条路二选一

### A. 用宝塔已有的 Redis（推荐，零安装）

1. 后端加依赖 `spring-boot-starter-data-redis`（尚未加，见下方「待做」）
2. 在 `/opt/kb/env.prod` 里加：

```bash
KB_REDIS_HOST=127.0.0.1
KB_REDIS_PORT=6379
KB_REDIS_PASSWORD=
```

3. 重启后端即可。

⚠️ 宝塔 Redis 默认 `bind 127.0.0.1` 且无密码，**不要**为了「方便」把它改成 `0.0.0.0` ——
无密码 + 公网 = 几小时内就会被挖矿脚本扫到。

### B. 用容器版 Redis

```bash
# 1) 先停掉宝塔 Redis，否则 6379 冲突
systemctl stop redis   # 或在宝塔面板里停

# 2) 起容器
cd deploy && cp .env.example .env && docker compose up -d

# 3) 验证
docker compose ps
redis-cli -h 127.0.0.1 -p 6379 ping   # 应返回 PONG
```

## 三、Meilisearch（可选，需要先装 Docker）

```bash
cd deploy && cp .env.example .env
# 改 .env 里的 MEILI_MASTER_KEY（≥16 字符，否则 production 模式拒绝启动）
docker compose --profile search up -d

# 验证
curl -s http://127.0.0.1:7700/health
# → {"status":"available"}
```

⚠️ **只起容器还不能搜索**。现有检索走 PostgreSQL（`LIKE`/JPQL + 前端 `utils/docSearch.js` 匹配），
接 Meilisearch 还需要写：

1. 索引同步（文档新增/修改/删除时推送到 `documents` 索引）
2. 搜索接口切到 Meili（`/api/doc-shares/docs/all?withContent=true` 那条路替换或并存）
3. 中文分词：Meilisearch 自带的分词对中文一般，需要配 `chinese` 分词器或前置 jieba

也就是说：容器是**第一步**，功能接入是另一件事。

## 四、待做（需要改代码，不是纯配置）

- [ ] 后端加 `spring-boot-starter-data-redis`，把这三处内存态换到 Redis：
  - `RateLimitService`（登录/LDAP/练习判分限流）—— 重启即清零，多实例不共享
  - `OidcAuthService` / `WeChatAuthService` / `QqAuthService` / `GithubAuthService` 的 `states`
    —— 代码里已注明「多实例需换共享存储」
  - `ExamService.SESSIONS`（练习判分会话，TTL 2h）—— **后端一重启，正在做的练习就判不了分**
- [ ] 若上 Meilisearch：索引同步 + 搜索接口切换

> Redis 不可用时必须**自动降级回内存**（现有行为不变），不能因为 Redis 挂了整站登录失败。

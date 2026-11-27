# API 调用说明

知识库平台对外提供 REST API，支持两种调用方式。**推荐用第二种（个人密钥）**——不用共享账号密码，可随时吊销。

- Base URL：你的部署地址（下文示例统一用 `https://your-host.example`），所有接口前缀 `/api`
- 请求与响应均为 JSON（`Content-Type: application/json; charset=UTF-8`）
- 时间字段为 ISO-8601 本地时间（`2026-11-27T16:04:48.123456`），末尾 6 位是微秒，部分语言需要用正则截断到秒再解析

---

## 一、认证

### 方式 A：个人 API 密钥（推荐）

在**个人资料 → API 密钥**里自助创建（起名字、选有效期、选作用域、可选 IP 白名单），明文只显示一次。

```bash
curl -H "X-API-Key: kb_9f3a2c7d5e1b…" https://your-host.example/api/docs/163
```

| 项 | 说明 |
|---|---|
| 权限 | 等于创建者的账号权限；勾了**作用域**则取交集，且不再继承管理员身份 |
| IP 白名单 | 填了就只允许这些来源（支持 `203.0.113.7`、`10.0.0.0/8`），其他来源直接 403 |
| 有效期 | 7 / 30 / 90 / 365 天，过期自动失效 |
| 吊销 | 列表里点「吊销」，下一个请求立即 401 |
| 数量 | 每账号最多 20 把（含已吊销的，吊销不释放名额） |

> 密钥**只认请求头**，不支持 `?key=` 写进 URL——URL 会进 nginx access log、浏览器历史与 Referer。
> 服务端只存 SHA-256，丢了只能重建。

注意两点边界：

- **密钥不能管理密钥**：带 `X-API-Key` 调用 `POST/DELETE /api/me/api-keys` 会 403（否则一把受限密钥可以再签发一把全权密钥，作用域形同虚设）。管理密钥请用登录令牌或网页端。
- **IP 白名单只认受信代理覆写的 `X-Real-IP`**，不回退 `X-Forwarded-For`（后者首段可被客户端伪造）。自建反代请确保 `proxy_set_header X-Real-IP $remote_addr;`。

### 方式 B：登录令牌

```bash
curl -X POST https://your-host.example/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"zhangsan","password":"…","captchaToken":"…"}'
# → {"token":"eyJ1c2Vy…","id":3,"username":"zhangsan",...}

curl -H "Authorization: Bearer <token>" https://your-host.example/api/auth/me
```

令牌默认 24 小时有效，改密 / 重置密码 / 注销会立即作废。登录需要滑动验证码（`GET /api/auth/captcha` 取图、`POST /api/auth/captcha/slide` 提交轨迹换票据），脚本化登录比较麻烦，**所以脚本请用方式 A**。

---

## 二、通用约定

**分页**：返回 Spring `Page` 结构。

```json
{ "content": [ … ], "totalElements": 151, "totalPages": 11, "number": 0, "size": 15 }
```

参数 `page`（0 基）、`size`（最大 100），不传默认 `page=0&size=15`。

**业务失败**仍是 HTTP 200，但响应体是：

```json
{ "ok": false, "error": "评论内容不能为空" }
```

**鉴权/权限失败**是 HTTP 状态码：`401` 未认证或令牌失效，`403` 无权限。

**限流**：登录 10 次 / 10 分钟 / IP；评论 10 条 / 分钟 / 账号。触发时返回 `{ "ok": false, "error": "评论过于频繁，请稍后再试" }`。

---

## 三、接口清单

### 身份

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/auth/me` | 当前用户（含 role、permissions、points） |
| GET | `/api/me/api-keys` | 我的密钥列表（只回前缀） |
| POST | `/api/me/api-keys` | 新建密钥 → 返回明文一次 |
| DELETE | `/api/me/api-keys/{id}` | 吊销 |

新建密钥请求体：

```json
{
  "name": "每日数据同步",
  "scopes": ["DOC_VIEW"],
  "allowedIps": ["203.0.113.7", "10.0.0.0/8"],
  "expiresInDays": 30
}
```

`scopes` 只能是调用者自己已有的权限码；留空表示与账号权限相同。
`allowedIps` 留空表示不限制来源。

### 文档

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/docs` | 列表，参数 `page` `size` `sort` `tag` `category` `ownerId` |
| GET | `/api/docs/{id}` | 详情（已发布内容免登录可读） |
| GET | `/api/docs/mine` | 我的文档（含草稿） |
| POST | `/api/docs` | 新建；普通用户默认进 `PENDING_REVIEW`，管理员为空时置 `PUBLISHED` |
| PUT | `/api/docs/{id}` | 修改（自己的文档；管理员可改任意） |
| POST | `/api/docs/{id}/submit` | 提交审核 |
| POST | `/api/docs/{id}/subscribe` | 订阅更新（`DELETE` 取消） |

新建文档请求体（`id` 必须省略）：

```json
{ "title": "接口说明草稿", "content": "<p>正文 HTML</p>", "category": "技术", "tags": "api,文档" }
```

`content` 会被服务端做 HTML 净化，脚本标签与事件属性会被剥掉。

### 搜索

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/search` | 参数 `q`（必填）、`category` `author` `sort` `days` `tags` `page` `size`；返回数组（非 Page） |

`q` 为空串时返回空数组（不是全量）。`sort` 取 `relevance`（默认）/ `updated` / `hot`。

### 评论

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/docs/{id}/comments` | 某篇文档的评论 |
| POST | `/api/docs/{id}/comments` | 发表，体 `{ "content": "…", "parentId": null }`，限 10 条/分钟 |
| GET | `/api/comments` | 跨对象通用列表（公开） |

只有 `PUBLISHED` 状态的内容可以评论。

### 收藏

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/favorites/mine` | 我收藏的文档 |
| POST | `/api/favorites/{docId}` | 收藏 |
| DELETE | `/api/favorites/{docId}` | 取消（幂等） |

### 文件

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/files/mine` | 我的文件（含大小、存储用量） |
| POST | `/api/files/mine` | 上传（`multipart/form-data`，字段名 `file`） |

### 私信

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/dm/conversations` | 会话列表（含未读数） |
| GET | `/api/dm/messages?peerId=&size=30` | 与某人的消息（升序） |
| POST | `/api/dm/messages` | 发送 `{ "toId": 3, "content": "…" }` |
| POST | `/api/dm/read` | 标记已读 `{ "peerId": 3 }` |
| GET | `/api/dm/unread` | 总未读数 |

未互相关注且对方未回复时，一个会话最多先发 3 条。

---

## 四、完整示例

### curl

```bash
# 1. 建一把只读密钥（用登录令牌或已存在的密钥调用）
curl -X POST https://your-host.example/api/me/api-keys \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"爬取脚本","scopes":["DOC_VIEW"],"expiresInDays":30}'
# → {"ok":true,"key":"kb_9f3a…"}

# 2. 用它读文档
curl -H "X-API-Key: kb_9f3a…" https://your-host.example/api/docs/163

# 3. 分页拉列表
curl -H "X-API-Key: kb_9f3a…" "https://your-host.example/api/docs?page=0&size=20&sort=updated"
```

### Python

```python
import requests

BASE = "https://your-host.example/api"
H = {"X-API-Key": "kb_9f3a…"}

# 拉一页文档
r = requests.get(f"{BASE}/docs", headers=H, params={"page": 0, "size": 20}, timeout=15)
r.raise_for_status()
data = r.json()
for d in data["content"]:
    print(d["id"], d["title"])

# 建草稿
r = requests.post(f"{BASE}/docs", headers=H, timeout=15, json={
    "title": "接口抓取草稿",
    "content": "<p>正文</p>",
})
if r.status_code == 200 and r.json().get("ok") is False:
    print("业务失败：", r.json()["error"])
else:
    print("新文档 id =", r.json()["id"])
```

### Node.js

```js
const BASE = 'https://your-host.example/api'
const H = { 'X-API-Key': 'kb_9f3a…' }

const res = await fetch(`${BASE}/search?q=${encodeURIComponent('接口')}&size=10`, { headers: H })
const list = await res.json()
console.log(list.map((x) => x.title))
```

---

## 五、错误排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `401 未登录或登录已过期` | 密钥错误 / 已吊销 / 已过期 | 重新建一把 |
| `403 {"error":"来源 IP 不在该密钥的允许范围内"}` | 密钥设了 IP 白名单 | 把出口 IP 加进白名单，或去掉白名单 |
| `403` 且无 body | 账号没有该接口的权限码 | 检查密钥作用域与账号权限 |
| `200 {"ok":false,"error":"…"}` | 业务校验失败（标题为空、内容敏感词等） | 看 `error` 原文 |
| `405 该接口不支持此请求方法` | 方法用错（如 PUT 打 GET 接口） | 对照本文档 |
| `429 / 评论过于频繁` | 触发限流 | 降频或换账号维度 |

---

## 六、注意事项

- **只读公开面**：`/api/docs/{id}`、`/api/search`、`/api/comments` 对已发布内容免登录可读，草稿返回 404。
- **不要在 URL 里放凭据**，包括 `?key=` `?token=`。
- 脚本请用「个人密钥 + `X-API-Key`」，不要复用登录令牌：令牌会随改密失效，且没有作用域与 IP 限制。
- 生产环境 `ddl-auto=validate`，升级版本时新增的表/字段需要先手工执行 SQL（见 `db/` 目录）。

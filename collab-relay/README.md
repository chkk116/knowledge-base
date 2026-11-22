# 共享文档实时协同中继（collab-relay）

自托管 Node 进程，为「共享文档」提供 Yjs 实时协同能力。基于 [Hocuspocus](https://tiptap.dev/docs/hocuspocus/introduction)，直连 PostgreSQL 读写 Yjs 状态，并复刻后端的 HMAC 令牌鉴权。

## 工作原理
- 浏览器通过 `@hocuspocus/provider` 连接 `ws://<host>:1234`，`documentName` 约定为 `<shareId>:<docId>`；回滚过历史版本的文档用 `<shareId>:<docId>:<yjs_rev>`，房间名带代次，恢复后旧连接无法再连上，前端必然以新代次重新加载（否则「恢复成功但内容没变」）。
- 连接时用 `KB_TOKEN_SECRET` 校验令牌、解析 userId，再查库判定权限：**完全管理员 / 空间所有者 / 文档所有者 / 空间成员**四者之一才放行，空间成员是网关——被移出空间后，残留的文档级授权行不能继续放行。权限只是读、写两个档位（OWNER/ADMIN/WRITE 可写，READ 只读）。
- 完全管理员的判定与后端 `TokenClaims.isAdmin()` 同语义：`role=ADMIN`，或权限码覆盖管理员全集（清单见 `index.js` 里的 `ADMIN_PERM_SET`，对应后端 `Perm.WHITELIST` 去掉 `DEMO_MODE`），演示账号永不提升。清单漏更新只会让这类账号退回普通用户，不会越权。
- Yjs 状态持久化到 `kb_shared_document.yjs_state`：定时落库（默认 5 秒）+ 房间清空时 + 进程退出前。落库带代次条件（`WHERE yjs_rev = <连接时的代次>`），代次已经被回滚顶掉就丢弃这次写入，避免「回滚提示成功、几秒后又把旧内容写回去」。
- 多人光标通过 Yjs Awareness 在房间内广播，前端用 `@tiptap/extension-collaboration-cursor` 渲染。

## 与后端的控制通道
后端通过 HTTP 调两个控制端点，POST JSON，`X-KB-Relay-Sign` 放 HMAC-SHA256 签名：

| 端点 | 用途 |
| --- | --- |
| `/revoke` | 权限变更后立即踢掉在线连接（不等下一次复检） |
| `/flush` | **让中继立刻把某文档的当前状态落库**——后端保存版本快照 / 回滚前先催一次，否则读到的是几秒前的旧状态，快照与回滚都会错位 |

两个端点都要求签名正确，签名错返回 403；未启用中继时后端按「库里的状态就是最新的」继续，不阻塞主流程。

## 运行
```bash
cd knowledge-base/collab-relay
npm install
# 默认连本机 kb 库（用户 kb/密码 kb），密钥取 KB_TOKEN_SECRET（与后端一致）
KB_TOKEN_SECRET=你的密钥 npm start
```
可选环境变量：`COLLAB_PORT`(1234)、`COLLAB_HOST`(默认 `::` 双栈)、`COLLAB_DB_HOST/PORT/USER/PASS/NAME`(localhost/5432/kb/kb/kb)、`COLLAB_SAVE_INTERVAL`(5000ms)、`COLLAB_PERM_RECHECK_MS`(15000ms，在线连接的权限复检周期)、`COLLAB_OP_LOG_ENABLED`(true)。

## 与后端的关系
- `kb_shared_document.yjs_state` 列仅供协同使用；「恢复历史版本」会同时写回 `content` 与 `yjs_state`（该版本没有协同快照时只写 `content`，宁可与协同态不同步也不把文档清空）。
- 后端在「保存快照」时把 HTML 写入 `content` 列，供非协同预览 / 搜索 / 公开链接兼容；实时协同状态以 `yjs_state` 为准。
- 后端保存正文时带 `baseUpdatedAt` / `baseRev` 乐观锁，与本服务无关但同属「别静默覆盖别人改动」这条线，改动保存接口时别把这两个字段去掉。

## 生产部署
建议用进程管理器（pm2 / systemd）保活，或用 nginx 反向代理把 `/collab` 转发到本服务，使 WebSocket 与前端同源（https 页面下必须走 `wss`，否则被浏览器按混合内容拦掉，表现为协同「静默连不上」）。

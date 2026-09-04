# 共享文档实时协同中继（collab-relay）

自托管 Node 进程，为「共享文档」提供 Yjs 实时协同能力。基于 [Hocuspocus](https://tiptap.dev/docs/hocuspocus/introduction)，直连 MySQL 读写 Yjs 状态，并复刻后端的 HMAC 令牌鉴权。

## 工作原理
- 浏览器通过 `@hocuspocus/provider` 连接 `ws://<host>:1234`，`documentName` 约定为 `<shareId>:<docId>`。
- 连接时中继用 `KB_TOKEN_SECRET` 校验令牌、解析 userId，再查 `kb_doc_share_permission` 判定权限（OWNER/ADMIN/WRITE/READ），无权限直接拒绝。
- Yjs 文档二进制状态持久化到 `kb_shared_document.yjs_state`（定时落库 + 房间清空时落库 + 进程退出前落库）。
- 多人光标通过 Yjs Awareness 在房间内广播，前端用 `@tiptap/extension-collaboration-cursor` 渲染。

## 运行
```bash
cd knowledge-base/collab-relay
npm install
# 默认连本机 kb 库（用户 kb/密码 kb），密钥取 KB_TOKEN_SECRET（与后端一致）
KB_TOKEN_SECRET=你的密钥 npm start
```
可选环境变量：`COLLAB_PORT`(默认1234)、`COLLAB_DB_HOST/PORT/USER/PASS/NAME`、`COLLAB_SAVE_INTERVAL`(ms)。

## 与后端的关系
- 后端新增了 `kb_shared_document.yjs_state` 列（JPA `ddl-auto: update` 自动建列），仅用于中继落库，业务接口不读它。
- 前端编辑器仍会在「保存快照」时把 HTML 写入 `content` 列，供非协同预览/搜索兼容；真正的协同状态以 `yjs_state` 为准。

## 生产部署
建议用进程管理器（pm2 / systemd）保活，或用 nginx 反向代理将 `/collab` 转发到本服务，使 WebSocket 与前端同源。

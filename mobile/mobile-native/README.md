# 知识库 Native（Kotlin + Jetpack Compose 原生壳）

> 方向二落地工程：**原生壳 + 原生页面 + WebView 承载编辑器**，渐进替换 Capacitor 套壳。
> 原有 `mobile/`（Capacitor 工程）保持不动，两版可并存安装、真机对照。

## 现状（竖切 v0.1）

已原生实现：

| 模块 | 说明 | 对接接口 |
|---|---|---|
| 登录页 | 滑动拼图验证码（Compose 手绘，协议与 Web 端 SlideCaptcha.vue 同源：320 基准坐标换算、轨迹 ≥3 点 ≥120ms） | `/api/auth/captcha` `/api/auth/captcha/slide` `/api/auth/login` |
| 主框架 | 底部四 Tab：首页 / 文档库 / 消息 / 我的（与 Capacitor 版信息架构一致） | — |
| 首页 | 数据概览 + 公告 + 快捷入口（快捷入口进 WebView） | `/api/docs/overview` `/api/announcements` |
| 文档库 | 空间列表 → 文档列表，点开进 WebView 阅读/编辑 | `/api/doc-shares/my` `/api/doc-shares/{id}/docs` |
| 消息 | 通知列表、点按已读（乐观更新） | `/api/notifications` |
| 我的 | 资料、积分、服务器地址设置、退出登录 | `/api/auth/me` |
| WebView 承载页 | 打开任意 `/m/*` 页面；令牌桥接自动登录；顶部进度条、返回键栈管理 | — |

令牌桥接机制（配对代码：`frontend/src/main-mobile.js` 顶部的 bootstrap 段）：

1. 原生端拦截 `/m/*` 的 **HTML 文档请求**（静态资源与 XHR 放行），OkHttp 取回后在 `<head>` 后注入
   `window.__KB_NATIVE_TOKEN="<token>"`
2. `main-mobile.js` 入口最先执行：发现该变量即写入 `localStorage.kb_token`——发生在 Vue 路由守卫
   读令牌之前，无竞态；未登录时注入 `null`，Web 侧同步清除
3. Capacitor 套壳与纯 Web 不注入该变量（`'in'` 判定跳过），行为零变化

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
- `applicationId` 为 `com.kb.kbplatform.shell`，可与 Capacitor 版（`com.kb.kbplatform`）**并存安装**；
  转正替换旧 App 时改回即可
- 首次构建会拉取大量依赖，耐心等；IDE 打开请用 Android Studio 选 `mobile-native` 目录

## 后端地址

- 默认 `http://10.0.2.2:8080`（模拟器访问宿主机）
- 真机联调：App 内「我的 → 服务器地址」改成电脑局域网 IP，**无需重新打包**
- 生产：改成 `https://你的域名`；与 Web 端一样，仓库内不存任何公网地址

## 已知边界（竖切暂不做）

- WebView 内页面暂无文件选择/相机桥接：`/m/capture` 等拍照类页快捷入口未放首页
  （需要 WebChromeClient.onShowFileChooser + 运行时权限，属下一步）
- SSE 实时通知未接（消息页手动刷新）；应用锁、离线队列、分享导入、应用内更新未迁移
- 原生登录走 OkHttp，UA 已带 Mozilla 前缀，不会触发后端的管理员脚本登录告警

## 迁移路线图（建议顺序）

1. **P0 体验验证**：真机安装，与 Capacitor 版对照登录/文档库/阅读手感 → 决定是否铺开
2. **P1 桥接补全**：文件选择 + 相机权限桥（让 WebView 内 OCR/拍照可用）；SSE 通知
3. **P2 高频页原生化**（每页独立可发布，先后台刷新不影响线上）：搜索、AI 问答（SSE 流式）、复习卡片、考试练习
4. **P3 原生能力**：系统分享导入（替代 ShareReceiverPlugin）、离线缓存（Room）、应用内更新（替代 ApkInstallerPlugin）、推送
5. **P4 转正**：applicationId 改回 `com.kb.kbplatform`，签名对齐，Capacitor 工程归档

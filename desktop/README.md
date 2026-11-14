# 知识库桌面端（Electron 套壳 · 归档 / 兼容模式）

> 客户端路线已收敛为 **Web + Kotlin 原生壳**：本目录保留供已装用户过渡，**不再同步新 UI、不再发新版**。
> 主推的原生桌面端见 `desktop/native/`。

把现有的 Vue3 Web 前端直接封装成 Windows 桌面应用（`.exe` 安装包），**前端代码零改动**。

## 原理
- 生产模式：主进程起一个本地 HTTP 服务托管前端构建产物（`desktop/web/`），并把以下请求反向代理到后端：
  - `/api`、`/uploads`、`/thumb` → 后端 HTTP（默认 `http://localhost:8080`）
  - `/collab`（协同 WebSocket 升级）→ 后端中继（默认 `ws://localhost:1234`）
- 开发模式：`--mode=dev` 时直接加载 Vite 开发服务器 `http://localhost:5173`。
- **原生桌面外壳（关键，去除「浏览器套壳」观感）**：
  - **独立登录框**：启动先弹出一个 **frameless 原生登录小窗**（`login.html`，QQ 风格紧凑卡片 + 自绘标题栏可拖拽/最小化/关闭），**不加载 Web SPA**，纯 JS 直接复用后端滑动验证码与登录接口；登录成功后主窗口才出现。应用内「退出登录」自动回到登录框。
  - **主窗口自绘标题栏**：主窗口 `frame: false`，注入一条深蓝紫渐变标题栏（拖拽区 + 最小化/最大化/关闭按钮），让 Web 内容呈现原生客户端形态，而非浏览器标签页；**生产环境菜单已移除「开发者工具 / 强制重载」**，不再暴露浏览器能力。
  - **系统托盘**：常驻后台（与微信 / 百度网盘一致），主窗口关闭 = 最小化到托盘而非退出；托盘菜单含「显示 / 隐藏」「退出」。
  - 会话由本地轮询 `localStorage` 的 `kb_token` 在两个窗口间切换。
- 以上改动**全部在桌面壳层**，前端代码零改动。

## 本机前置
- Node.js（Electron 33 需要 Node 18+，建议与项目一致的 21/22）
- 能访问 npm 源（公网，或公司内网镜像）。WorkBuddy 沙箱环境无法 `npm install` 第三方包，因此本工程只交付源码，安装与打包请在你的本机执行。

## 使用步骤

### 0. 生成应用图标（首次打包前需要一次）
桌面端的应用图标、任务栏图标、安装包图标，都由 `build/build-icon.ps1` 程序化生成。脚本只用 Windows 内置 .NET System.Drawing，不引第三方依赖。

设计取向：256×256 圆角矩形、深蓝紫垂直渐变、居中三本抽象书块 + 一条书架细线——**无 KB 字母、无玻璃反光、纯几何纯色**，与项目"内联网格/本地化"的调性一致。

在 `desktop/` 目录下跑（CMD 或 PowerShell 都行）：
```bat
powershell -ExecutionPolicy Bypass -File build\build-icon.ps1
```
产物：
- `build/icon.png`（256×256，开发预览/窗口用）
- `build/icon.ico`（256×256 PNG-in-ICO，安装包/任务栏用）

之后正常 `npm run pack` 即可，`electron-builder` 会自动应用图标。如果跑图脚本失败或跳过这步，`main.js` 会回退到 Electron 默认图标，不阻塞打包。

### 1. 安装依赖（仅一次）
```bat
cd desktop
npm install
```

### 2. 开发预览（联调用）
先确保前端 dev（:5173）与后端（:8080）已在运行，然后：
```bat
npm run start
```
桌面窗口会加载 `http://localhost:5173`。

### 3. 出 Windows 安装包
```bat
npm run pack
```
该命令会：① 先用前端现有 Vite 构建产物到 `desktop/web/`；② 用 `electron-builder` 打包成 NSIS 安装包。
产物在 `desktop/dist-electron/知识库 Setup 1.0.0.exe`（NSIS 安装包），以及 `desktop/dist-electron/win-unpacked/知识库.exe`（免安装绿色版）。

## 环境变量（可选）
| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `KB_DESKTOP_PORT` | `8899` | 桌面端本地服务端口 |
| `KB_BACKEND_URL` | `http://localhost:8080` | 后端 HTTP 基址（部署到别的机器时改这里） |
| `KB_BACKEND_WS` | `ws://localhost:1234` | 协同中继 ws 基址 |

例：后端在 `192.168.1.50`、中继在 `192.168.1.50:1234`：
```bat
set KB_BACKEND_URL=http://192.168.1.50:8080
set KB_BACKEND_WS=ws://192.168.1.50:1234
npm run start:prod
```

## 说明
- 前端构建产物与 Web 端完全一致（同一份 `vite build`），桌面端只是「壳」，不维护第二套 UI。
- 当前仅做桌面端（按需求）；移动端（响应式适配 + Capacitor 打包）暂缓，后续在 `mobile/` 目录另起。

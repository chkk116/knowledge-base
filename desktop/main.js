/**
 * 知识库桌面端主进程（Electron）。
 * 纯 Node 内置模块实现，不依赖任何第三方静态服务器 / 代理库：
 *  - prod 模式：起本地 HTTP 服务托管前端构建产物（desktop/web），并把 /api、/uploads、/thumb
 *    反向代理到后端（默认 http://localhost:8080）；协同 /collab 的 WebSocket 升级隧道到
 *    后端中继（默认 ws://localhost:1234）。前端代码零改动。
 *  - dev  模式：直接加载 Vite 开发服务器 http://localhost:5173（前端 dev 已代理 /api 到 8080）。
 *
 * 环境变量（均可选）：
 *   KB_DESKTOP_PORT  本地服务端口，默认 8899
 *   KB_BACKEND_URL   后端 HTTP 基址，默认 http://localhost:8080
 *   KB_BACKEND_WS    协同中继 ws 基址，默认 ws://localhost:1234
 */
const { app, BrowserWindow, Menu, dialog, nativeImage, Tray, ipcMain } = require('electron')
const http = require('http')
const https = require('https')
const net = require('net')
const fs = require('fs')
const path = require('path')
const { URL } = require('url')

const isDev = process.argv.includes('--mode=dev')
const PORT = Number(process.env.KB_DESKTOP_PORT || 8899)
const BACKEND = process.env.KB_BACKEND_URL || 'http://localhost:8080'
const BACKEND_WS = process.env.KB_BACKEND_WS || 'ws://localhost:1234'
const WEB_ROOT = path.join(__dirname, 'web')
const APP_NAME = '知识库'
let APP_VERSION = '1.0.0'
try { APP_VERSION = require('./package.json').version } catch (_) { /* ignore */ }
const ICON_PNG = path.join(__dirname, 'build', 'icon.png')
// 原生登录页（独立桌面登录框，不加载 Web SPA），构建时由服务端以 /__login 路由返回
const LOGIN_HTML_PATH = path.join(__dirname, 'login.html')
let LOGIN_HTML = ''
try { LOGIN_HTML = fs.readFileSync(LOGIN_HTML_PATH, 'utf8') } catch (_) { /* 缺失则用 Web 登录 */ }
// 需要转发到后端的 HTTP 前缀（协同 /collab 走下面的 WebSocket 隧道，不在这里）
const PROXY_PREFIXES = ['/api', '/uploads', '/thumb']

// 单实例锁：第二次启动不再抢 8899 端口（否则 EADDRINUSE 崩溃），而是唤起已有窗口后静默退出。
// 必须在 app ready 之前申请。
const hasSingleInstanceLock = app.requestSingleInstanceLock()
if (!hasSingleInstanceLock) {
  app.quit()
} else {
  app.on('second-instance', () => {
    const wins = BrowserWindow.getAllWindows().filter((w) => !w.isDestroyed())
    if (!wins.length) return
    const w = wins.find((x) => x.isVisible()) || wins[0]
    if (w.isMinimized()) w.restore()
    w.show()
    w.focus()
  })
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.cjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.webp': 'image/webp',
  '.avif': 'image/avif',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.eot': 'application/vnd.ms-fontobject',
  '.txt': 'text/plain; charset=utf-8',
  '.xml': 'application/xml; charset=utf-8',
  '.mp3': 'audio/mpeg',
  '.flac': 'audio/flac',
  '.wav': 'audio/wav',
  '.m4a': 'audio/mp4',
  '.ogg': 'audio/ogg',
  '.mp4': 'video/mp4',
  '.webm': 'video/webm',
  '.pdf': 'application/pdf'
}

function isProxyPath(url) {
  return PROXY_PREFIXES.some((p) => url === p || url.startsWith(p + '/'))
}

// 反向代理单个 HTTP 请求到后端（流式转发，支持大文件 / 文件上传 / Range）
function proxyToBackend(req, res) {
  // 只接受 origin-form（以单个 / 开头）。绝对形式请求行（如 `GET http://evil/...`）会让
  // new URL(req.url, BACKEND) 忽略 BACKEND 直接变成开放代理，这里一律拒绝。
  const raw = typeof req.url === 'string' ? req.url : ''
  if (!raw.startsWith('/') || raw.startsWith('//')) {
    res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' })
    res.end('Bad Request')
    return
  }
  // 目标只用 BACKEND 的 scheme://host:port，路径与查询串自行拼接
  const base = new URL(BACKEND)
  const q = raw.indexOf('?')
  const target = new URL(base.protocol + '//' + base.host)
  target.pathname = q < 0 ? raw : raw.slice(0, q)
  target.search = q < 0 ? '' : raw.slice(q)
  const headers = { ...req.headers, host: target.host }
  const lib = target.protocol === 'https:' ? https : http
  const preq = lib.request(target, { method: req.method, headers }, (pres) => {
    res.writeHead(pres.statusCode, pres.headers)
    pres.pipe(res)
  })
  preq.on('error', () => {
    if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' })
    res.end('Bad Gateway：无法连接后端 ' + BACKEND)
  })
  req.pipe(preq)
}

// 托管 desktop/web 下的静态资源，并做 SPA history fallback
function serveStatic(req, res) {
  const root = path.resolve(WEB_ROOT)
  let urlPath = decodeURIComponent(req.url.split('?')[0])
  if (urlPath === '/') urlPath = '/index.html'
  // 先 resolve 再按「路径分隔符边界」比较，避免 /web 与 /web2 的裸字符串前缀误判越界
  let file = path.resolve(root, '.' + urlPath)
  if (file !== root && !file.startsWith(root + path.sep)) {
    res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' })
    res.end('Forbidden')
    return
  }
  fs.stat(file, (err, stat) => {
    if (err || !stat.isFile()) file = path.join(root, 'index.html') // SPA fallback
    fs.readFile(file, (e, data) => {
      if (e) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
        res.end('Not Found')
        return
      }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' })
      res.end(data)
    })
  })
}

let server = null

function buildAppMenu() {
  const isMac = process.platform === 'darwin'
  const tpl = [
    ...(isMac ? [{
      label: APP_NAME,
      submenu: [
        { label: `关于 ${APP_NAME}`, click: showAbout },
        { type: 'separator' },
        { role: 'services' },
        { type: 'separator' },
        { role: 'hide' },
        { role: 'hideOthers' },
        { role: 'unhide' },
        { type: 'separator' },
        { role: 'quit' }
      ]
    }] : []),
    {
      label: '文件',
      submenu: [
        { label: '重新加载', accelerator: 'CmdOrCtrl+R', role: 'reload' },
        ...(isDev
          ? [{ label: '强制重载', accelerator: 'CmdOrCtrl+Shift+R', role: 'forceReload' }]
          : []),
        { type: 'separator' },
        isMac ? { role: 'close' } : { label: '退出', accelerator: 'CmdOrCtrl+Q', role: 'quit' }
      ]
    },
    {
      label: '编辑',
      submenu: [
        { label: '撤销', accelerator: 'CmdOrCtrl+Z', role: 'undo' },
        { label: '重做', accelerator: 'CmdOrCtrl+Y', role: 'redo' },
        { type: 'separator' },
        { label: '剪切', accelerator: 'CmdOrCtrl+X', role: 'cut' },
        { label: '复制', accelerator: 'CmdOrCtrl+C', role: 'copy' },
        { label: '粘贴', accelerator: 'CmdOrCtrl+V', role: 'paste' },
        { type: 'separator' },
        { label: '全选', accelerator: 'CmdOrCtrl+A', role: 'selectAll' }
      ]
    },
    {
      label: '视图',
      submenu: [
        { label: '放大', accelerator: 'CmdOrCtrl+Plus', role: 'zoomIn' },
        { label: '缩小', accelerator: 'CmdOrCtrl+-', role: 'zoomOut' },
        { label: '实际大小', accelerator: 'CmdOrCtrl+0', role: 'resetZoom' },
        { type: 'separator' },
        { label: '全屏', accelerator: 'F11', role: 'togglefullscreen' },
        ...(isDev
          ? [{ type: 'separator' }, { label: '开发者工具', accelerator: 'F12', role: 'toggleDevTools' }]
          : [])
      ]
    },
    {
      label: '窗口',
      submenu: [
        { label: '最小化', accelerator: 'CmdOrCtrl+M', role: 'minimize' },
        { label: '最大化', role: 'maximize' },
        { label: '关闭', accelerator: 'CmdOrCtrl+W', role: 'close' }
      ]
    },
    {
      label: '帮助',
      submenu: [
        { label: `关于 ${APP_NAME}`, click: showAbout }
      ]
    }
  ]
  return Menu.buildFromTemplate(tpl)
}

function showAbout() {
  const win = BrowserWindow.getFocusedWindow()
  dialog.showMessageBox(win, {
    type: 'info',
    title: `关于 ${APP_NAME}`,
    message: APP_NAME,
    detail: `版本 ${APP_VERSION}\n内部知识库平台桌面客户端\n\nElectron ${process.versions.electron}  ·  Chromium ${process.versions.chrome}  ·  Node ${process.versions.node}`,
    buttons: ['确定'],
    noLink: true
  })
}

function getWindowIcon() {
  try {
    if (fs.existsSync(ICON_PNG)) return nativeImage.createFromPath(ICON_PNG)
  } catch (_) { /* ignore */ }
  return undefined
}

function createServer() {
  server = http.createServer((req, res) => {
    const pathOnly = req.url.split('?')[0]
    if (pathOnly === '/__login') {
      if (LOGIN_HTML) {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
        res.end(LOGIN_HTML)
      } else {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
        res.end('Not Found')
      }
      return
    }
    if (isProxyPath(req.url)) proxyToBackend(req, res)
    else serveStatic(req, res)
  })

  // 协同 WebSocket 隧道：把 /collab 的 Upgrade 请求透传到后端中继（默认 1234）
  server.on('upgrade', (req, socket, head) => {
    if (!req.url.startsWith('/collab')) {
      socket.destroy()
      return
    }
    const t = new URL(BACKEND_WS)
    const client = net.connect(Number(t.port), t.hostname, () => {
      let line = `GET ${req.url} HTTP/1.1\r\n`
      const h = { ...req.headers, host: `${t.hostname}:${t.port}` }
      for (const k in h) line += `${k}: ${h[k]}\r\n`
      line += '\r\n'
      client.write(line)
      if (head && head.length) client.write(head)
    })
    client.on('data', (d) => socket.write(d))
    socket.on('data', (d) => client.write(d))
    client.on('close', () => socket.end())
    client.on('error', () => socket.destroy())
    socket.on('close', () => client.end())
    socket.on('error', () => client.destroy())
  })

  // 端口被占（多为残留的旧实例）时，弹中文提示后退出，而不是抛未捕获的 EADDRINUSE 崩溃
  server.on('error', (err) => {
    if (err && err.code === 'EADDRINUSE') {
      try {
        dialog.showErrorBox(APP_NAME, `本地服务端口 ${PORT} 已被占用。\n\n请先在系统托盘右键「退出」关闭已运行的知识库客户端，然后重新启动。`)
      } catch (_) { /* ignore */ }
      app.quit()
      return
    }
    console.error('[kb-desktop] 本地服务异常：', err)
  })

  // 只绑定本机回环地址，避免同网段机器访问本地服务
  server.listen(PORT, '127.0.0.1', () => {
    console.log(`[kb-desktop] 本地服务已启动 http://localhost:${PORT}（后端 ${BACKEND}，协同 ${BACKEND_WS}）`)
  })
}

// 给开发模式的 Web 登录页（SPA /login）做极轻量样式注入：标题栏可拖拽 + 关闭按钮。
function injectLoginChrome(win) {
  win.webContents.on('did-finish-load', () => {
    win.webContents.insertCSS(`
      .kb-desktop-close{position:fixed;top:14px;right:16px;z-index:99999;width:30px;height:30px;
        border-radius:50%;background:rgba(255,255,255,.14);color:#fff;border:none;cursor:pointer;
        font-size:18px;line-height:1;display:flex;align-items:center;justify-content:center;}
      .kb-desktop-close:hover{background:rgba(255,255,255,.28);}
      .login-page .title{-webkit-app-region:drag;cursor:move;}
    `).catch(() => {})
    win.webContents.executeJavaScript(`
      if(!document.querySelector('.kb-desktop-close')){
        var b=document.createElement('button');
        b.className='kb-desktop-close';b.textContent='×';b.title='关闭';
        b.onclick=function(){ if(window.electronAPI) window.electronAPI.closeCurrent(); else window.close(); };
        document.body.appendChild(b);
      }
    `).catch(() => {})
  })
}

// 给主窗口注入「原生桌面标题栏」：自绘 drag 区 + 最小化/最大化/关闭按钮，
// 使 Web SPA 看起来像原生客户端（无浏览器地址栏 / 菜单条），而非浏览器标签页。
function injectAppChrome(win) {
  win.webContents.on('did-finish-load', () => {
    win.webContents.insertCSS(`
      #app{ padding-top:38px !important; box-sizing:border-box; }
      #kb-titlebar{position:fixed;top:0;left:0;right:0;height:38px;z-index:2147483647;
        display:flex;align-items:center;padding:0 6px 0 12px;gap:8px;
        background:linear-gradient(90deg,#312e81,#4338ca);color:#fff;
        -webkit-app-region:drag;-webkit-user-select:none;font-size:13px;}
      #kb-titlebar .kb-logo{width:16px;height:16px;border-radius:4px;
        background:linear-gradient(135deg,#818cf8,#c7d2fe);flex:none;}
      #kb-titlebar .kb-title{font-weight:600;letter-spacing:.5px;}
      #kb-titlebar .kb-spacer{flex:1;}
      #kb-titlebar .kb-btn{-webkit-app-region:no-drag;width:36px;height:26px;border:none;
        background:transparent;color:#fff;cursor:pointer;font-size:14px;border-radius:6px;
        display:flex;align-items:center;justify-content:center;}
      #kb-titlebar .kb-btn:hover{background:rgba(255,255,255,.18);}
      #kb-titlebar .kb-btn.close:hover{background:#ef4444;}
    `).catch(() => {})
    win.webContents.executeJavaScript(`
      (function(){
        if(document.getElementById('kb-titlebar')) return;
        var tb=document.createElement('div'); tb.id='kb-titlebar';
        tb.innerHTML='<span class="kb-logo"></span><span class="kb-title">知识库</span>'+
          '<span class="kb-spacer"></span>'+
          '<button class="kb-btn min" title="最小化">—</button>'+
          '<button class="kb-btn max" title="最大化">▢</button>'+
          '<button class="kb-btn close" title="关闭">✕</button>';
        document.body.appendChild(tb);
        tb.querySelector('.min').onclick=function(){ if(window.electronAPI) window.electronAPI.minimizeCurrent(); };
        tb.querySelector('.max').onclick=function(){ if(window.electronAPI) window.electronAPI.toggleMaximizeCurrent(); };
        tb.querySelector('.close').onclick=function(){ if(window.electronAPI) window.electronAPI.closeCurrent(); };
      })();
    `).catch(() => {})
  })
}

// 独立登录窗：frameless 小窗，居中。
//  - 生产：加载原生 login.html（/__login 路由），QQ 风格紧凑登录框，纯桌面客户端观感。
//  - 开发：仍加载前端 /login（SPA，便于调试），注入轻量拖拽/关闭。
function createLoginWindow() {
  const icon = getWindowIcon()
  const win = new BrowserWindow({
    // 尺寸对齐 PC QQ 登录框（约 430x330，本窗口宽 380 保持输入框留白）：
    // 输入框/按钮 36px、标题栏 32px、各段间距压缩后，内容区实测 ~304px，340 留少量呼吸空间。
    width: 380,
    height: 340,
    minWidth: 380,
    minHeight: 340,
    center: true,
    frame: false,
    resizable: false,
    title: APP_NAME,
    icon: icon,
    backgroundColor: '#f3f4f8',
    roundedCorners: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  })
  if (isDev) {
    injectLoginChrome(win)
    win.loadURL('http://localhost:5173/login')
  } else {
    win.loadURL(`http://localhost:${PORT}/__login`)
  }
  return win
}

// 主窗口（已登录后的应用本体）：frameless + 自绘标题栏，呈现原生桌面客户端形态。
// 关闭时不退出，而是最小化到系统托盘（与微信 / 百度网盘一致）。
function createMainWindow() {
  const icon = getWindowIcon()
  const win = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 1024,
    minHeight: 680,
    show: false,
    frame: false,
    title: APP_NAME,
    icon: icon,
    backgroundColor: '#1e1b4b',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  })
  injectAppChrome(win)
  win.on('close', (e) => {
    if (!quitting) {
      e.preventDefault()
      win.hide()
    }
  })
  if (isDev) win.loadURL('http://localhost:5173/')
  else win.loadURL(`http://localhost:${PORT}/`)
  win.once('ready-to-show', () => win.show())
  return win
}

// 从指定窗口读取登录令牌（与前端 store/auth.js 的 localStorage key 保持一致）
async function getToken(win) {
  try {
    return await win.webContents.executeJavaScript("localStorage.getItem('kb_token')||''")
  } catch (e) {
    return ''
  }
}

let loginWin = null
let mainWin = null
let switching = false

async function openMainWindow() {
  if (mainWin && !mainWin.isDestroyed()) {
    if (!mainWin.isVisible()) mainWin.show()
    mainWin.focus()
    return
  }
  mainWin = createMainWindow()
}

function showLoginWindow() {
  if (loginWin && !loginWin.isDestroyed()) {
    if (!loginWin.isVisible()) loginWin.show()
    loginWin.focus()
    return
  }
  loginWin = createLoginWindow()
}

function closeLoginWindow() {
  if (loginWin && !loginWin.isDestroyed()) {
    const w = loginWin
    loginWin = null
    w.destroy()
  }
}

function closeMainWindow() {
  if (mainWin && !mainWin.isDestroyed()) {
    const w = mainWin
    mainWin = null
    w.destroy()
  }
}

// 会话监视：登录窗出现令牌 → 打开主窗口并关闭登录窗；主窗口令牌消失（登出）→ 回到登录窗。
function startAuthMonitor() {
  setInterval(async () => {
    if (switching) return
    try {
      if (loginWin && !loginWin.isDestroyed()) {
        const t = await getToken(loginWin)
        if (t) {
          switching = true
          await openMainWindow()
          closeLoginWindow()
          switching = false
        }
      } else if (mainWin && !mainWin.isDestroyed()) {
        const t = await getToken(mainWin)
        if (!t) {
          switching = true
          closeMainWindow()
          showLoginWindow()
          switching = false
        }
      }
    } catch (e) {
      switching = false
    }
  }, 600)
}

ipcMain.on('electron:close-current', (e) => {
  const w = BrowserWindow.fromWebContents(e.sender)
  if (!w) return
  if (w === loginWin) {
    // 登录窗关闭 = 退出整个应用（此时尚无主界面）
    app.quit()
  } else {
    // 主窗关闭 = 最小化到系统托盘（与微信 / 百度网盘一致），不退出进程
    w.hide()
  }
})
ipcMain.on('electron:minimize-current', (e) => {
  const w = BrowserWindow.fromWebContents(e.sender)
  if (w) w.minimize()
})
ipcMain.on('electron:toggle-maximize-current', (e) => {
  const w = BrowserWindow.fromWebContents(e.sender)
  if (w) {
    if (w.isMaximized()) w.unmaximize()
    else w.maximize()
  }
})

// 系统托盘：常驻后台，提供「显示 / 隐藏」「退出」。无图标文件时不启用。
let tray = null
function toggleMainWindow() {
  if (mainWin && !mainWin.isDestroyed()) {
    if (mainWin.isVisible()) mainWin.hide()
    else { mainWin.show(); mainWin.focus() }
  } else {
    showLoginWindow()
  }
}
function createTray() {
  try {
    const icon = getWindowIcon()
    if (!icon) return
    tray = new Tray(icon)
    tray.setToolTip(APP_NAME)
    tray.setContextMenu(Menu.buildFromTemplate([
      { label: '显示 / 隐藏主界面', click: toggleMainWindow },
      { type: 'separator' },
      { label: '退出', click: () => app.quit() }
    ]))
    tray.on('click', toggleMainWindow)
  } catch (_) {
    /* 图标缺失则不加托盘 */
  }
}

// 退出标记：before-quit 置位后，主窗 close 拦截失效，允许真正退出。
let quitting = false
app.on('before-quit', () => { quitting = true })

if (hasSingleInstanceLock) {
  app.whenReady().then(() => {
    app.setName(APP_NAME)
    Menu.setApplicationMenu(buildAppMenu())
    if (!isDev) createServer()
    createTray()
    showLoginWindow()
    startAuthMonitor()
    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) showLoginWindow()
    })
  })
}

app.on('window-all-closed', () => {
  if (server) {
    try {
      server.close()
    } catch (e) {
      /* ignore */
    }
  }
  // 有系统托盘时保持进程常驻（最小化到托盘），不随窗口全关而退出；
  // 仅当托盘未启用时才走默认退出逻辑。
  if (!tray && process.platform !== 'darwin') app.quit()
})

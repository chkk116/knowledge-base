/**
 * 知识库桌面端 preload（安全边界）。
 * contextIsolation 已开启、nodeIntegration 关闭；仅向渲染进程暴露极少量桌面端能力，
 * 且不涉及任何文件系统 / 进程操作，避免把 Node 能力泄露给 Web 内容。
 */
const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('electronAPI', {
  isElectron: true,
  platform: process.platform,
  // 关闭「当前」窗口（由主进程按事件来源定位窗口）
  closeCurrent: () => ipcRenderer.send('electron:close-current'),
  // 最小化「当前」窗口
  minimizeCurrent: () => ipcRenderer.send('electron:minimize-current'),
  // 最大化 / 还原「当前」窗口
  toggleMaximizeCurrent: () => ipcRenderer.send('electron:toggle-maximize-current')
})

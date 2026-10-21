@echo off
rem 一键启动前端 dev：依赖没装就先装，装了直接起。
rem 用法：双击本文件，或在终端执行  dev.cmd
setlocal
cd /d "%~dp0"

if not exist "node_modules\vite\bin\vite.js" (
  echo [1/2] 未检测到依赖，开始安装（首次较慢，走 npmmirror 会快很多）...
  call npm ci
  if errorlevel 1 (
    echo [1/2] npm ci 失败，改用 npm install 重试...
    call npm install
  )
)

if not exist "node_modules\vite\bin\vite.js" (
  echo [ERROR] 依赖安装后仍找不到 vite，请检查安全软件是否拦截了 node_modules。
  pause
  exit /b 1
)

echo [2/2] 启动 dev ... 首次启动需预构建依赖，等待 2-3 分钟属正常
node node_modules\vite\bin\vite.js %*

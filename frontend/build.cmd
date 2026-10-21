@echo off
rem 一键出包：依赖没装先装，然后构建并注入 Service Worker。
rem 用法：build.cmd            输出到 dist
rem       build.cmd v201       输出到 dist-v201（不覆盖旧 dist，避免目录被占用导致 EPERM）
setlocal
cd /d "%~dp0"

if not exist "node_modules\vite\bin\vite.js" (
  echo [1/3] 未检测到依赖，开始安装...
  call npm ci
  if errorlevel 1 call npm install
)

set OUT=dist
if not "%~1"=="" set OUT=dist-%~1

echo [2/3] 构建到 %OUT% ...
node node_modules\vite\bin\vite.js build --outDir %OUT% --emptyOutDir false
if errorlevel 1 (
  echo [ERROR] 构建失败
  pause
  exit /b 1
)

echo [3/3] 注入 Service Worker ...
node build-inject-sw.cjs %OUT%
if errorlevel 1 (
  echo [ERROR] SW 注入失败
  pause
  exit /b 1
)

echo.
echo 完成：%cd%\%OUT%

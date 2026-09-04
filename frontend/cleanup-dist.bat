@echo off
REM ============================================================
REM Cleanup old dist-* build folders. Keeps the newest N.
REM
REM Usage:  cleanup-dist.bat [keep] [go]
REM           keep = how many newest folders to keep (default 5)
REM           go   = actually delete; omit for dry-run
REM
REM Examples:
REM   cleanup-dist.bat            - dry run, keep newest 5
REM   cleanup-dist.bat 5 go       - really delete, keep newest 5
REM   cleanup-dist.bat 10 go      - keep newest 10
REM
REM NOTE: two legacy folders are NOT matched by the dist-* pattern
REM and are never touched by this script:
REM   dist       (old default output dir)
REM   dist_bak   (old backup)
REM Remove them manually if you no longer need them.
REM ============================================================
setlocal EnableDelayedExpansion

set "KEEP=%~1"
if "%KEEP%"=="" set "KEEP=5"
set "GO=%~2"

REM sanity: KEEP must be a positive number
for /f "delims=0123456789" %%c in ("%KEEP%") do (
  echo ERROR: keep count must be a number. Got: %KEEP%
  goto :eof
)
if %KEEP% LSS 1 (
  echo ERROR: keep count must be >= 1
  goto :eof
)

cd /d "%~dp0" || goto :eof

if /i "%GO%"=="go" (
  echo === DELETING, keeping newest %KEEP% ===
) else (
  echo === DRY RUN, keeping newest %KEEP% ===
)
echo.

set "COUNT=0"
set "DELCOUNT=0"
for /f "delims=" %%d in ('dir /b /ad /o-d dist-* 2^>nul') do (
  set /a COUNT+=1
  if !COUNT! GTR %KEEP% (
    set /a DELCOUNT+=1
    if /i "%GO%"=="go" (
      echo [DELETE] %%d
      rd /s /q "%%d"
      if errorlevel 1 echo   ^^! FAILED: %%d
    ) else (
      echo [WOULD DELETE] %%d
    )
  ) else (
    echo [KEEP] %%d
  )
)

echo.
echo Total dist-* folders: !COUNT!  Kept: %KEEP%  Removed: !DELCOUNT!
if /i not "%GO%"=="go" (
  echo.
  echo Nothing was deleted. To really delete, run:
  echo   cleanup-dist.bat %KEEP% go
)
endlocal

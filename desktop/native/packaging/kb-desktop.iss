; 知识库桌面端 Windows 安装程序脚本（Inno Setup 6.7.4）
;
; 编译入口：gradle packageInnoSetup（自动先 createDistributable，再 ISCC /DAppVer=<版本>）
; 手动编译：C:\chkk\tools\inno6\ISCC.exe /DAppVer=1.0.3 packaging\kb-desktop.iss
; 输入：createDistributable 产出的 app-image（构建目录已重定向到纯 ASCII 路径，见 build.gradle.kts）
; 中文向导依赖 packaging\ChineseSimplified.isl，需复制到 C:\chkk\tools\inno6\Languages\（编译时经 compiler: 前缀读取）
;
; 产品标识：AppId 固定，升级/重装都落到同一位置，卸载入口在「设置-应用」与开始菜单
; 本文件为 UTF-8（带 BOM），Inno Setup 6 可直接识别中文

#ifndef AppVer
  ; 手动编译未传 /DAppVer 时的兜底（与 AppInfo.kt 的 versionName 保持一致）
  #define AppVer "1.0.3"
#endif

#define MyAppName "知识库"
#define MyAppExe "kb-desktop"
#define MyAppPublisher "kb"

[Setup]
AppId={{B919BD52-4E07-45B2-AE2A-6BE675D2429F}
; ↑ 写成 {{ 是为了输出字面 {：最终 AppId 为 {B919BD52-4E07-45B2-AE2A-6BE675D2429F}
AppName={#MyAppName}
AppVersion={#AppVer}
AppVerName={#MyAppName} {#AppVer}
AppPublisher={#MyAppPublisher}
; VS Code 式用户级安装位置：免 UAC，默认 {localappdata}\Programs\kb-desktop，向导里可改
DefaultDirName={localappdata}\Programs\kb-desktop
DirExistsWarning=no
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
CloseApplications=yes
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
OutputDir=C:\chkk\tools\kbbuild\native\inno
OutputBaseFilename=kb-desktop-{#AppVer}-setup
; 占位图标（插件默认）；换成正式 .ico 后同时改 UninstallDisplayIcon
SetupIconFile=C:\chkk\tools\kbbuild\native\compose\default-resources\1.13.0-beta02\default-icon-windows.ico
UninstallDisplayIcon={app}\{#MyAppExe}.exe
UninstallDisplayName={#MyAppName}

[Languages]
Name: "chs"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: checkedonce

[Files]
; app-image 整目录打包（kb-desktop.exe + runtime）
Source: "C:\chkk\tools\kbbuild\native\compose\binaries\main\app\kb-desktop\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
; 开始菜单平铺两个入口：启动 + 卸载（卸载快捷方式让卸载入口一眼可见）
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExe}.exe"
Name: "{autoprograms}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExe}.exe"; Tasks: desktopicon

[Run]
; 完成页「立即运行」；静默安装不自动启动
Filename: "{app}\{#MyAppExe}.exe"; Description: "{cm:LaunchProgram,{#MyAppName}}"; Flags: nowait postinstall skipifsilent

; 卸载刻意保留 %APPDATA%\kb-desktop（config.json：服务器地址/用户名），与 VS Code 留用户配置同一策略

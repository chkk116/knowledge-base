-- 桌面端版本通道（channel=desktop）：1.0.3 中文单文件安装包（Inno Setup）
INSERT INTO kb_sys_setting (setting_key, setting_value)
VALUES ('app_version_desktop', '{"versionCode":4,"versionName":"1.0.3","url":"https://chkk116.cn/uploads/desktop/kb-desktop-1.0.3-setup.exe","notes":"修复登录/检查更新失败（补 java.net.http 模块）；中文安装向导，登录页黛青配色","checksum":"9411d8a984963e8999414d01887c86f164d3e66d4bc1f62249c24ed71c226009","force":false}')
ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value;

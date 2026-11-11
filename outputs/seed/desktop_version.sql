-- 桌面端版本通道（channel=desktop）。发新版时改 versionCode/versionName/url/checksum。
INSERT INTO kb_sys_setting (setting_key, setting_value)
VALUES ('app_version_desktop', '{"versionCode":1,"versionName":"1.0.0","url":"https://chkk116.cn/uploads/desktop/kb-desktop-1.0.0.exe","notes":"首个桌面版：登录、文章、我的文件、设置与更新检查","checksum":"a9a6e0387298f2f3987bc0634f575c1be415b3c98483672be73ea837c9b3802b","force":false}')
ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value;

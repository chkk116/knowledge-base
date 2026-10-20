#!/usr/bin/env bash
# 发布 App 整包（应用内升级用）
#
# 用法：
#   bash outputs/publish-app-release.sh <apk路径> <versionCode> <versionName> <更新说明>
# 例：
#   bash outputs/publish-app-release.sh outputs/apk/知识库-v1.2-20261010-0900.apk 3 1.2 "修复xxx"
#
# 做什么：
#   1) 算 sha256
#   2) 上传到 /opt/kb/app-release/kb-app-<versionName>.apk（nginx 暴露为 /app-release/，免鉴权）
#   3) 把 SysSetting.app_version 写成 App 读的 JSON（versionCode/versionName/url/notes/force）
#   4) 校验公网可下载 + sha 一致 + 接口返回
#
# 前提：APK 的 versionCode 必须已 bump（改 mobile/android/app/build.gradle），且必须 > 用户已装的版本。
set -euo pipefail

APK="${1:?用法: publish-app-release.sh <apk路径> <versionCode> <versionName> <notes>}"
CODE="${2:?缺 versionCode}"
NAME="${3:?缺 versionName}"
NOTES="${4:-}"
FORCE="${5:-false}"

DOMAIN="https://www.你的域名"
KEY="$(cd "$(dirname "$0")/.." && pwd)/outputs/.deploykey"
[ -f "$KEY" ] || KEY="/c/chkk/知识库/outputs/.deploykey"
: "${KB_DEPLOY_TARGET:?请先 export KB_DEPLOY_TARGET（如 root@你的服务器IP），并把服务器指纹加入本机 known_hosts}"
SSH="ssh -i $KEY -o StrictHostKeyChecking=yes $KB_DEPLOY_TARGET"

[ -f "$APK" ] || { echo "找不到 APK: $APK"; exit 1; }

SHA=$(sha256sum "$APK" | cut -d' ' -f1)
URL="$DOMAIN/app-release/kb-app-$NAME.apk"
echo "APK    : $APK"
echo "版本   : $CODE / $NAME"
echo "SHA256 : $SHA"
echo "URL    : $URL"
echo ""

echo "== 1. 上传 =="
# 用 ssh + cat 上传（比 scp 少开一个连接，降低被 SSH 限流的概率）
$SSH "cat > /opt/kb/app-release/kb-app-$NAME.apk" < "$APK" 2>&1 | grep -v Warning || true

echo "== 2. 写 SysSetting.app_version =="
$SSH "bash -s" << REMOTE 2>&1 | grep -v Warning
cat > /tmp/kb_appver.sql << 'SQL'
insert into kb_sys_setting(setting_key, setting_value, updated_at)
values ('app_version', '{"versionCode":$CODE,"versionName":"$NAME","url":"$URL","checksum":"$SHA","notes":"$NOTES","force":$FORCE}', now())
on conflict (setting_key) do update set setting_value = excluded.setting_value, updated_at = now();
SQL
/www/server/pgsql/bin/psql -h 127.0.0.1 -p 5433 -U kb -d kb -f /tmp/kb_appver.sql
rm -f /tmp/kb_appver.sql
REMOTE

echo ""
echo "== 3. 校验 =="
sleep 2
echo -n "接口     : "; curl -s -m 15 "$DOMAIN/api/public/app-version"; echo ""
curl -s -m 180 "$URL" -o /tmp/_dl.apk
DL=$(sha256sum /tmp/_dl.apk | cut -d' ' -f1); rm -f /tmp/_dl.apk
echo "下载 sha : $DL"
[ "$DL" = "$SHA" ] && echo "✓ 一致，发布完成" || { echo "❌ sha 不一致"; exit 1; }

#!/usr/bin/env bash
# 发布前端热更包（Capgo 用）
#
# 用法：
#   bash outputs/publish-app-bundle.sh <dist后缀> [更新说明]
# 例：
#   bash outputs/publish-app-bundle.sh v194 "修了个文案"
#
# 做什么：
#   1) 打 zip（archiver，正斜杠路径，根目录直接是 web 根）
#   2) 上传 /opt/kb/app-bundles/（nginx 暴露为 /app-bundles/，免鉴权）
#   3) 把 SysSetting.app_bundle 写成 App 读的 JSON（version/url/checksum/notes）
#   4) 校验公网可下载
#
# 注意：版本号取「时间戳」，与旧版本不同即可触发更新。原生改动（插件/权限/图标）热更覆盖不了，必须发 APK。
set -euo pipefail

VER="${1:?用法: publish-app-bundle.sh <dist后缀> [notes]}"
NOTES="${2:-}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEY="$ROOT/outputs/.deploykey"
[ -f "$KEY" ] || KEY="/c/chkk/知识库/outputs/.deploykey"
: "${KB_DEPLOY_TARGET:?请先 export KB_DEPLOY_TARGET（如 root@你的服务器IP），并把服务器指纹加入本机 known_hosts}"
SSH="ssh -i $KEY -o StrictHostKeyChecking=yes $KB_DEPLOY_TARGET"
DOMAIN="https://www.你的域名"

echo "== 1. 打 zip =="
cd "$ROOT"
NODE_OPTIONS='' node outputs/build-app-bundle.cjs "$VER" 2>&1 | grep -E "打包完成|体积|SHA-256" || true

ZIP=$(ls -t outputs/app-bundles/*.zip | head -1)
NAME=$(basename "$ZIP")
SHA=$(sha256sum "$ZIP" | cut -d' ' -f1)
BVER=$(echo "$NAME" | sed -E 's/kb-app-(.*)\.zip/\1/')
URL="$DOMAIN/app-bundles/$NAME"
echo "包: $NAME  版本: $BVER"

echo "== 2. 上传 =="
$SSH 'rm -f /opt/kb/app-bundles/*.zip' 2>&1 | grep -v Warning || true
# 用 ssh + cat 上传（比 scp 少开一个连接）
$SSH "cat > /opt/kb/app-bundles/$NAME" < "$ZIP" 2>&1 | grep -v Warning || true

echo "== 3. 写 SysSetting.app_bundle =="
$SSH "bash -s" << REMOTE 2>&1 | grep -v Warning
cat > /tmp/kb_bundle.sql << 'SQL'
insert into kb_sys_setting(setting_key, setting_value, updated_at)
values ('app_bundle', '{"version":"$BVER","url":"$URL","checksum":"$SHA","notes":"$NOTES"}', now())
on conflict (setting_key) do update set setting_value = excluded.setting_value, updated_at = now();
SQL
/www/server/pgsql/bin/psql -h 127.0.0.1 -p 5433 -U kb -d kb -f /tmp/kb_bundle.sql
rm -f /tmp/kb_bundle.sql
REMOTE

echo "== 4. 校验 =="
sleep 2
echo -n "接口: "; curl -s -m 15 "$DOMAIN/api/public/app-bundle"; echo ""
curl -s -o /dev/null -w "zip : %{http_code}  %{size_download} bytes\n" -m 120 "$URL"
echo "✓ 发布完成"

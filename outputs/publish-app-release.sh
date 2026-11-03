#!/usr/bin/env bash
# 发布 App 整包（应用内升级用）
#
# 用法：
#   bash outputs/publish-app-release.sh <apk路径> <versionCode> <versionName> <更新说明> [force]
#   发原生壳（com.kb.kbplatform）时务必带通道：
#   KB_CHANNEL=app_version_native bash outputs/publish-app-release.sh <apk> 45 0.9.6-native "说明"
#   （不带 = 写 app_version，那是 Capacitor 套壳读的通道）
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
#
# 2026-10-21 安全加固（与 publish-app-bundle.sh 同款）：
#   - SQL 注入：$NOTES/$NAME 等不再拼进 SQL——本地构造 JSON → base64 → psql 变量绑定（:'val'）。
#   - FORCE 强校验为 true/false（原样拼进 JSON 布尔位，可注入）。
#   - 上传 .part → mv 原子替换；去掉 `|| true` 吞错管道。
set -euo pipefail

APK="${1:?用法: publish-app-release.sh <apk路径> <versionCode> <versionName> <notes> [force]}"
CODE="${2:?缺 versionCode}"
NAME="${3:?缺 versionName}"
NOTES="${4:-}"
FORCE="${5:-false}"

# 版本通道：默认写 app_version（Capacitor 套壳读的）；原生壳读 app_version_native。
# 发原生包必须 KB_CHANNEL=app_version_native，否则原生用户收不到更新（而且会把 Capacitor 通道覆盖掉）。
CHANNEL="${KB_CHANNEL:-app_version}"
case "$CHANNEL" in
  app_version|app_version_native) ;;
  *) echo "❌ KB_CHANNEL 只能是 app_version / app_version_native，实际: $CHANNEL"; exit 1 ;;
esac

# versionCode 必须是纯数字；FORCE 只能是 true/false
[[ "$CODE" =~ ^[0-9]+$ ]] || { echo "❌ versionCode 必须是数字，实际: $CODE"; exit 1; }
[[ "$NAME" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "❌ versionName 只允许字母数字 . _ -，实际: $NAME"; exit 1; }
case "$FORCE" in true|false) ;; *) echo "❌ force 只能是 true/false，实际: $FORCE"; exit 1 ;; esac

# 知识库主域名是 你的域名；www.你的域名 已改作订餐系统，App 里不能再指向它
DOMAIN="${KB_DOMAIN:-https://chkk116.cn}"
KEY="$(cd "$(dirname "$0")/.." && pwd)/outputs/.deploykey"
[ -f "$KEY" ] || KEY="/c/chkk/知识库/outputs/.deploykey"
: "${KB_DEPLOY_TARGET:?请先 export KB_DEPLOY_TARGET（如 root@你的服务器IP），并把服务器指纹加入本机 known_hosts}"
SSH="ssh -i $KEY -o StrictHostKeyChecking=yes $KB_DEPLOY_TARGET"

[ -f "$APK" ] || { echo "找不到 APK: $APK"; exit 1; }

json_str() { printf '%s' "$1" | tr -d '\r\n' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

SHA=$(sha256sum "$APK" | cut -d' ' -f1)
URL="$DOMAIN/app-release/kb-app-$NAME.apk"
echo "APK    : $APK"
echo "版本   : $CODE / $NAME"
echo "SHA256 : $SHA"
echo "URL    : $URL"
echo ""

echo "== 1. 上传（.part → mv 原子替换） =="
$SSH "cat > /opt/kb/app-release/kb-app-$NAME.apk.part" < "$APK"
$SSH "mv -f /opt/kb/app-release/kb-app-$NAME.apk.part /opt/kb/app-release/kb-app-$NAME.apk"

echo "== 2. 写 SysSetting.$CHANNEL =="
JSON="{\"versionCode\":$CODE,\"versionName\":\"$(json_str "$NAME")\",\"url\":\"$(json_str "$URL")\",\"checksum\":\"$(json_str "$SHA")\",\"notes\":\"$(json_str "$NOTES")\",\"force\":$FORCE}"
B64=$(printf '%s' "$JSON" | base64 | tr -d '\n')
$SSH "KB_JSON_B64='$B64' KB_KEY='$CHANNEL' bash -s" << 'REMOTE' 2>&1
set -euo pipefail
JSON=$(printf '%s' "$KB_JSON_B64" | base64 -d)
# ⚠️ 必须用 stdin 喂 SQL，不能用 `psql -c "..."`：变量插值（:'val'）只在
#    脚本文件 / stdin 路径生效，-c 的单条命令不替换，会报 syntax error at or near ":"。
/www/server/pgsql/bin/psql -h 127.0.0.1 -p 5433 -U kb -d kb -v key="$KB_KEY" -v val="$JSON" <<'SQL'
insert into kb_sys_setting(setting_key, setting_value, updated_at)
values (:'key', :'val', now())
on conflict (setting_key) do update set setting_value = excluded.setting_value, updated_at = now();
SQL
REMOTE

echo ""
echo "== 3. 校验 =="
sleep 2
echo -n "接口     : "; if [ "$CHANNEL" = "app_version_native" ]; then
  curl -fsS -m 15 "$DOMAIN/api/public/app-version?channel=native"; echo ""
else
  curl -fsS -m 15 "$DOMAIN/api/public/app-version"; echo ""
fi
curl -fsS -m 180 "$URL" -o /tmp/_dl.apk
DL=$(sha256sum /tmp/_dl.apk | cut -d' ' -f1); rm -f /tmp/_dl.apk
echo "下载 sha : $DL"
[ "$DL" = "$SHA" ] && echo "✓ 一致，发布完成" || { echo "❌ sha 不一致"; exit 1; }

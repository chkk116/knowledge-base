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
#
# 2026-10-21 安全加固：
#   - SQL 注入：旧版把 $NOTES/$URL 等直接拼进远端 SQL（外层 heredoc 未加引号），
#     含引号/换行即可注入任意 SQL。现在本地构造 JSON → base64 → 远端 psql 变量绑定（:'val'）。
#   - 先上传 .part 再 mv 原子替换，旧包在新包就位前不删（上传中断不再断供）。
#   - 去掉 `| grep -v Warning || true`——它把所有步骤的失败都吞成成功。
set -euo pipefail

VER="${1:?用法: publish-app-bundle.sh <dist后缀> [notes]}"
NOTES="${2:-}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEY="$ROOT/outputs/.deploykey"
[ -f "$KEY" ] || KEY="/c/chkk/知识库/outputs/.deploykey"
: "${KB_DEPLOY_TARGET:?请先 export KB_DEPLOY_TARGET（如 root@你的服务器IP），并把服务器指纹加入本机 known_hosts}"
SSH="ssh -i $KEY -o StrictHostKeyChecking=yes $KB_DEPLOY_TARGET"
# 知识库主域名是 chkk116.cn；www.chkk116.cn 已改作订餐系统，App 里不能再指向它
DOMAIN="${KB_DOMAIN:-https://chkk116.cn}"
# 构建器校验 assets 里含线上 host（不带协议），否则 fail-close；这里跟 DOMAIN 同步导出
export KB_NATIVE_HOST="$(printf '%s' "$DOMAIN" | sed -E 's#^https?://##; s#/.*$##')"

# JSON 字符串转义（含换行→删；引号/反斜杠转义），供嵌入 JSON 值
json_str() { printf '%s' "$1" | tr -d '\r\n' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

echo "== 1. 打 zip =="
cd "$ROOT"
if ! NODE_OPTIONS='' node outputs/build-app-bundle.cjs "$VER"; then
  echo "❌ 打包失败（见上方输出），已中止" >&2
  exit 1
fi

ZIP=$(ls -t outputs/app-bundles/*.zip | head -1)
NAME=$(basename "$ZIP")
SHA=$(sha256sum "$ZIP" | cut -d' ' -f1)
BVER=$(echo "$NAME" | sed -E 's/kb-app-(.*)\.zip/\1/')
URL="$DOMAIN/app-bundles/$NAME"
echo "包: $NAME  版本: $BVER"

echo "== 2. 上传（.part → mv 原子替换，成功后才清旧包） =="
$SSH "cat > /opt/kb/app-bundles/${NAME}.part" < "$ZIP"
$SSH "mv -f /opt/kb/app-bundles/${NAME}.part /opt/kb/app-bundles/${NAME}"
$SSH "find /opt/kb/app-bundles -maxdepth 1 -name 'kb-app-*.zip' ! -name '$NAME' -delete"

echo "== 3. 写 SysSetting.app_bundle =="
# 热更包签名：私钥只在本机，服务器只存签名；App 内置公钥验签。
# 签名覆盖 version + url + checksum，改任一字段都会验签失败。
SIGN_KEY="$ROOT/outputs/.bundle-signing-key.pem"
if [ ! -f "$SIGN_KEY" ]; then
  echo "✗ 缺少签名私钥：$SIGN_KEY（热更必须签名，已中止）" >&2
  exit 1
fi
CANON=$(printf '%s\n%s\n%s' "$BVER" "$URL" "$SHA")
SIG=$(printf '%s' "$CANON" | openssl dgst -sha256 -sign "$SIGN_KEY" | base64 | tr -d '\n')
[ -n "$SIG" ] || { echo "✗ 签名失败" >&2; exit 1; }
echo "签名: ${SIG:0:24}… (${#SIG} chars)"

# 本地构造 JSON（全部字段做 JSON 转义）→ base64 → 远端 psql 变量绑定，杜绝 SQL 注入
JSON="{\"version\":\"$(json_str "$BVER")\",\"url\":\"$(json_str "$URL")\",\"checksum\":\"$(json_str "$SHA")\",\"signature\":\"$(json_str "$SIG")\",\"sigAlg\":\"RS256\",\"notes\":\"$(json_str "$NOTES")\"}"
B64=$(printf '%s' "$JSON" | base64 | tr -d '\n')
$SSH "KB_JSON_B64='$B64' bash -s" << 'REMOTE' 2>&1
set -euo pipefail
JSON=$(printf '%s' "$KB_JSON_B64" | base64 -d)
# ⚠️ 必须用 stdin 喂 SQL，不能用 `psql -c "..."`：
#    psql 的变量插值（:'val'）只在「脚本文件 / stdin」路径生效，-c 传的单条命令
#    不会替换，直接报 syntax error at or near ":"（2026-10-21 踩过）。
/www/server/pgsql/bin/psql -h 127.0.0.1 -p 5433 -U kb -d kb -v val="$JSON" <<'SQL'
insert into kb_sys_setting(setting_key, setting_value, updated_at)
values ('app_bundle', :'val', now())
on conflict (setting_key) do update set setting_value = excluded.setting_value, updated_at = now();
SQL
REMOTE

echo "== 4. 校验 =="
sleep 2
echo -n "接口: "; curl -fsS -m 15 "$DOMAIN/api/public/app-bundle"; echo ""
curl -fsS -o /dev/null -w "zip : %{http_code}  %{size_download} bytes\n" -m 120 "$URL"
echo "✓ 发布完成"

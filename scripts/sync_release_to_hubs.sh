#!/usr/bin/env bash
# sync_release_to_hubs.sh — 把一次发版同步到两个公开分发仓：
#   GitHub: github.com/ccbili30-collab/novex   （GitHub 更新道 + 公告镜像）
#   Gitee : gitee.com/ccbili/novex             （Gitee 更新道 + update.json）
#
# 客户端只查这两个公开仓；私有代码仓 noven_bohe 的 release 只是内部产物。
#
# 用法:
#   sync_release_to_hubs.sh <tag> <stable|preview> <notes-file> <asset>...
# 环境:
#   GH_TOKEN/GITHUB_TOKEN  — gh CLI 凭证（GitHub hub 发布）
#   GITEE_TOKEN            — Gitee 私人令牌（缺失则只发 GitHub 道并告警）
set -euo pipefail

TAG="${1:?tag required}"
CHANNEL="${2:?channel required: stable|preview}"
NOTES_FILE="${3:?notes file required}"
shift 3
ASSETS=("$@")
[ "${#ASSETS[@]}" -gt 0 ] || { echo "at least one asset is required" >&2; exit 2; }

GH_HUB="ccbili30-collab/novex"
GITEE_OWNER="ccbili"
GITEE_REPO="novex"
GITEE_API="https://gitee.com/api/v5/repos/${GITEE_OWNER}/${GITEE_REPO}"

VERSION="${TAG#v}"
PRERELEASE_FLAG="--prerelease"
[ "$CHANNEL" = "stable" ] && PRERELEASE_FLAG="--latest"

# ---------- GitHub hub ----------
if gh release view "$TAG" --repo "$GH_HUB" >/dev/null 2>&1; then
  echo "github hub: release $TAG exists, uploading missing assets"
  gh release upload "$TAG" "${ASSETS[@]}" --repo "$GH_HUB" --clobber
else
  gh release create "$TAG" "${ASSETS[@]}" --repo "$GH_HUB" \
    --title "Novex ${VERSION}" --notes-file "$NOTES_FILE" $PRERELEASE_FLAG
fi
echo "github hub: https://github.com/${GH_HUB}/releases/tag/${TAG}"

# ---------- Gitee hub ----------
if [ -z "${GITEE_TOKEN:-}" ]; then
  echo "GITEE_TOKEN 未设置——跳过 Gitee 道（GitHub 道已发布）" >&2
  exit 0
fi

gitee_api() {
  curl -sf --max-time 60 "$@"
}

gitee_json() {
  python3 -c 'import json,sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
print("" if d is None else d.get("id", "") if isinstance(d, dict) else "")'
}

# 已有同名 release 则复用（幂等）；Gitee 未命中时返回 JSON null 而非 404。
release_id="$(gitee_api "${GITEE_API}/releases/tags/${TAG}?access_token=${GITEE_TOKEN}" 2>/dev/null | gitee_json || true)"
if [ -z "$release_id" ]; then
  # Gitee 的 release 字段要吃 urlencoded，不吃 multipart；-f 关掉以便看到错误体。
  create_resp="$(curl -s --max-time 60 -X POST "${GITEE_API}/releases" \
    --data-urlencode "access_token=${GITEE_TOKEN}" --data-urlencode "tag_name=${TAG}" \
    --data-urlencode "name=Novex ${VERSION}" --data-urlencode "target_commitish=main" \
    --data-urlencode "body@${NOTES_FILE}")"
  release_id="$(printf '%s' "$create_resp" | gitee_json)"
  if [ -z "$release_id" ]; then
    echo "gitee release 创建失败：$(printf '%s' "$create_resp" | head -c 300)" >&2
    exit 1
  fi
fi
echo "gitee hub: release id ${release_id}"

# 附件逐个上传；Gitee 对同名附件会 400，先查已有资产名再决定覆盖上传与否。
existing="$(gitee_api "${GITEE_API}/releases/${release_id}?access_token=${GITEE_TOKEN}" \
  | python3 -c 'import json,sys
try:
    d = json.load(sys.stdin)
    print(" ".join(a.get("name","") for a in (d or {}).get("assets",[]) if a.get("name","").endswith((".apk",".novex",".txt"))))
except Exception:
    pass' || true)"
for asset in "${ASSETS[@]}"; do
  name="$(basename "$asset")"
  if printf '%s' "$existing" | grep -qw "$name"; then
    echo "gitee hub: asset $name 已存在，跳过"
    continue
  fi
  # GitHub runner → Gitee 上行经常只有百 KB 级，单资产给足 2400s 并允许重试。
  uploaded=""
  for attempt in 1 2 3; do
    attach_resp="$(curl -s --max-time 2400 -X POST "${GITEE_API}/releases/${release_id}/attach_files" \
      -F "access_token=${GITEE_TOKEN}" -F "file=@${asset}")"
    if printf '%s' "$attach_resp" | grep -q '"id"'; then
      uploaded=1
      break
    fi
    echo "gitee 附件上传第 ${attempt} 次未成功 ${name}：$(printf '%s' "$attach_resp" | head -c 200)" >&2
  done
  if [ -z "$uploaded" ]; then
    echo "gitee 附件上传失败 ${name}（3 次尝试均超时/被拒）" >&2
    exit 1
  fi
  echo "gitee hub: 已上传 ${name}"
done

# update.json：更新本通道的 lane。下载地址指向上传的附件。
first_asset_name="$(basename "${ASSETS[0]}")"
download_url="https://gitee.com/${GITEE_OWNER}/${GITEE_REPO}/releases/download/${TAG}/${first_asset_name}"
notes_oneline="$(head -c 400 "$NOTES_FILE" | tr '\n' ' ')"
current_json="$(gitee_api "${GITEE_API}/contents/update.json?access_token=${GITEE_TOKEN}" \
  | python3 -c 'import base64,json,sys; print(base64.b64decode(json.load(sys.stdin)["content"]).decode())')"
sha="$(gitee_api "${GITEE_API}/contents/update.json?access_token=${GITEE_TOKEN}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["sha"])')"
new_json="$(CHANNEL="$CHANNEL" VERSION="$VERSION" DOWNLOAD_URL="$download_url" NOTES="$notes_oneline" \
  python3 - "$current_json" <<'PY'
import json, os, sys
root = json.loads(sys.argv[1])
root[os.environ["CHANNEL"]] = {
    "version": os.environ["VERSION"],
    "channel": os.environ["CHANNEL"],
    "date": __import__("datetime").date.today().isoformat(),
    "notes": os.environ["NOTES"],
    "download": os.environ["DOWNLOAD_URL"],
}
print(json.dumps(root, ensure_ascii=False, indent=2))
PY
)"
encoded="$(printf '%s' "$new_json" | base64)"
curl -sf --max-time 60 -X PUT "${GITEE_API}/contents/update.json" \
  -F "access_token=${GITEE_TOKEN}" -F "content=${encoded}" \
  -F "sha=${sha}" -F "message=release: ${TAG} ${CHANNEL} lane" >/dev/null
echo "gitee hub: update.json ${CHANNEL} → ${VERSION}"

#!/usr/bin/env bash
# 发布新版本：版本号加一 → 编译（不带密钥）→ 检查 APK 里没有密钥 → 提交、打 tag、推送 → 建 GitHub Release。
# 发布后，平板上的 App 在设置页点「检查更新」就能装上。
#
# 用法（在 Git Bash 里）：
#   ./release.sh "中文更新说明" "English release notes"
# 发布前先把代码改动提交好；这个脚本只提交版本号。
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${QUANWEN_JAVA_HOME:-/c/Program Files/Java/jdk-21}"
export PYTHONIOENCODING=utf-8

NOTES_ZH="${1:?第一个参数：中文更新说明}"
NOTES_EN="${2:?第二个参数：English release notes}"

if [ -n "$(git status --porcelain)" ]; then
  echo "工作区还有没提交的改动，先提交再发布。"; git status --short; exit 1
fi

code=$(grep '^VERSION_CODE=' version.properties | cut -d= -f2)
code=$((code + 1))
name="0.$code"
sed -i "s/^VERSION_CODE=.*/VERSION_CODE=$code/; s/^VERSION_NAME=.*/VERSION_NAME=$name/" version.properties

./gradlew assembleRelease --console=plain -q
apk="app/build/outputs/apk/release/app-release.apk"
python -I tools/no_key_check.py "$apk"
mkdir -p dist
cp "$apk" "dist/quanwen-v$code.apk"

msg="发布 v$code / Release v$code

$NOTES_ZH

$NOTES_EN"
[ -n "${CO_AUTHOR:-}" ] && msg="$msg

Co-Authored-By: $CO_AUTHOR"
git add version.properties
git commit -q -m "$msg"
git tag "v$code"

# 国内网络时通时断：推送和上传失败就重试
retry() { local n=0; until "$@"; do n=$((n + 1)); [ "$n" -ge 6 ] && return 1; echo "网络出错，第 $n 次重试…"; sleep 3; done; }
notes_file="$(mktemp)"
printf '%s\n\n---\n\n%s\n' "$NOTES_ZH" "$NOTES_EN" > "$notes_file"
publish() {
  if gh release view "v$code" >/dev/null 2>&1; then
    gh release upload "v$code" "dist/quanwen-v$code.apk" --clobber
  else
    gh release create "v$code" "dist/quanwen-v$code.apk" --title "$name" --notes-file "$notes_file"
  fi
}
retry git push -q
retry git push -q origin "v$code"
retry publish
rm -f "$notes_file"
echo "已发布 v$code（$name）"

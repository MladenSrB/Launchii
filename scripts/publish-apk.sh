#!/usr/bin/env bash
# Builds the release APK and publishes it as the newest GitHub Release, so the one download link
#   https://github.com/deciocrytek/Launchii/releases/latest/download/Launchii.apk
# always serves the latest build, and every installed copy shows Home's Update pill (AppUpdate).
# The release is tagged v<versionName>; publishing the same version again replaces that release's
# APK. Build locally, not in CI: the release is signed with the upload key in signing/, and a
# different key won't install over the app already on a phone.
#
#   scripts/publish-apk.sh            # build, then publish
#   scripts/publish-apk.sh --no-build # publish the APK already in app/build
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
GH="$(command -v gh || echo "$HOME/.local/bin/gh")"

[[ "${1:-}" == "--no-build" ]] || ./gradlew assembleRelease --console=plain
[[ -f signing/keystore.properties ]] || { echo "signing/keystore.properties missing: the APK would be debug-signed" >&2; exit 1; }

version="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
tag="v$version"
apk="$(mktemp -d)/Launchii.apk"
cp app/build/outputs/apk/release/app-release.apk "$apk"

if "$GH" release view "$tag" >/dev/null 2>&1; then
  "$GH" release upload "$tag" "$apk" --clobber
  "$GH" release edit "$tag" --latest
else
  "$GH" release create "$tag" "$apk" --title "Launchii $version" --latest \
    --notes "Install Launchii.apk on Android (allow installs from your browser or file manager). Installed copies update themselves from Home's Update pill."
fi

repo="$("$GH" repo view --json nameWithOwner -q .nameWithOwner)"
echo "Published $tag: https://github.com/$repo/releases/latest/download/Launchii.apk"

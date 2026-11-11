# Automatic APK updates

Every push to `main` builds the app and publishes a signed `Launchii.apk` in a
GitHub Release. Pull requests build downloadable Actions artifacts without
accessing the publishing key. Only `main` publishes phone updates. To retry or
publish manually, open **Actions → Build → Run workflow** and select `main`.

Download: https://github.com/MladenSrB/Launchii/releases/latest/download/Launchii.apk

Install this APK once to switch the app's update source from the original
author's repository. When Home comes to the foreground, it checks for a release
at most every six hours. A newer release shows an **Update** pill. Tap it to
install; Android may request permission or confirmation. This is a periodic
check, not a push notification.

The publish job requires these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: base64 encoding of the existing signing keystore.
- `ANDROID_KEYSTORE_PASSWORD`: keystore password.
- `ANDROID_KEY_ALIAS`: signing alias.
- `ANDROID_KEY_PASSWORD`: key password.

This installation uses the existing local debug signing certificate so that
updates can install over APKs built on this computer. A different certificate
cannot update those APKs. Keep the same secrets for all subsequent releases.
The keystore must never be committed to the repository.

CI assigns `versionCode = 1000000 + github.run_number` and version name
`0.<versionCode>`, matching the release tag `v0.<versionCode>`. Both Android and
the in-app version comparison therefore recognize each new run as an update.
Keep this workflow's run counter; if it is replaced or reset, choose a higher
base before publishing again. Local builds retain the source version unless
`LAUNCHII_VERSION_CODE` is explicitly set. After installing a CI release, local
builds need a higher version code to update it.

For a local signed update build, set `ANDROID_KEYSTORE_PATH`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, and
`LAUNCHII_VERSION_CODE`, then run `./gradlew :app:assembleRelease`.

The legacy `scripts/publish-apk.sh` publishes source versions using a separate
local upload key; use the Actions workflow for this repository's automatic
update channel instead.

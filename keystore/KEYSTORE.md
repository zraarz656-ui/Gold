# Fixed debug keystore

Path: `keystore/tradequest-debug.keystore` (committed to the repo, outside `local.properties`
and any secret store).

TradeQuest's debug builds are signed with this one stable key so an updated APK installs
over the previously installed build **without uninstalling** first. Android refuses to
update an app whose signing certificate changes, so a stable key is required for sideload
updates.

## Details

| Field | Value |
|-------|-------|
| File | `keystore/tradequest-debug.keystore` |
| Type | PKCS12 |
| Alias | `tradequest-debug` |
| Store password | `android` |
| Key password | `android` |
| Key algorithm | RSA 2048 |
| Validity | 10,950 days (~30 years) from 2026-10-10 |
| Subject | `CN=TradeQuest Debug, O=TradeQuest, L=Local, ST=Local, C=US` |

Wired up in `app/build.gradle.kts` as the `debugFixed` signing config, applied to the
`debug` build type only.

## Why it is safe to commit

This is a **debug-only** key with a widely known password (`android`, the same convention
as the Android tooling's default debug key). It is not a secret and never signs a release
artefact. The `release` build type is unsigned here; sign release builds with your own
keystore when you cut one.

## Regenerating

```sh
keytool -genkeypair -v \
  -keystore keystore/tradequest-debug.keystore \
  -storetype PKCS12 -alias tradequest-debug \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass android -keypass android \
  -dname "CN=TradeQuest Debug,O=TradeQuest,L=Local,ST=Local,C=US"
```

Regenerating creates a **new** key, so the next install would again require an uninstall.
Keep this file stable once you have installed a build signed by it.

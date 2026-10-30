# Building and signing

The source includes all module logic, resources, icon artwork and test code. Android SDK tools, the compile-only Xposed API and the hook runtime are external dependencies.

Use JDK 17, Python 3, Android SDK platform 36 and build-tools 36.0.0. Set `ANDROID_SDK_ROOT`, then run:

```sh
./build.sh --unsigned
python3 tests/run-host-checks.py
python3 tools/audit_release.py --apk build/OneUIHomeHandoff-1.2.0.unsigned.apk
```

The script verifies the Xposed API 82 JAR against SHA-256 `f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25`. That JAR is compile-only and is not packaged in the APK or source release. APK ZIP timestamps and attributes are normalized before alignment/signing. Exact tool versions still matter for byte-for-byte comparisons.

For clean Linux CI, `tools/bootstrap_android_sdk.py` downloads Google's command-line tools 19.0, checks the pinned SHA-256 before extraction, rejects unsafe archive paths/symlinks and installs only platform 36 and build-tools 36.0.0. The bootstrap is a build-time operation; it is not included or executed by the module app. It uses a temporary SDK directory and makes that SDK available to later GitHub Actions steps.

`--debug` creates a local debug key under ignored build output. Debug builds are not signed by the published release key.

For `--release`, keep a private keystore **outside** this checkout and provide the following environment variables through a local secret store:

- `SIGNING_KEYSTORE_PATH`
- `SIGNING_KEY_ALIAS`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_PASSWORD` (optional; defaults to the store password)

Passwords are passed to Android signing tools by environment reference rather than literal password arguments. Release builds reject a keystore inside the checkout. Never commit keys, credentials or populated environment files.

The public application ID is `io.github.encarnian.oneuihomehandoff`. Signing keys must match to update an installed copy with that ID. GitHub CI builds unsigned inspection artifacts only; release signing happens separately, with no key or password uploaded to CI.

Compare the APK's SHA-256 against its release checksum and inspect its signing certificate with Android's `apksigner verify --print-certs`. Signing identifies the release key; it does not prove that the code is secure.

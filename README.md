<p align="center"><img src="assets/icon.png" width="112" alt="One UI Home Handoff icon"></p>

# One UI Home Handoff

An open-source LSPosed module for the **One UI Home handoff to third-party launchers**. It targets the short period after swiping Home when the launcher is visible but taps and drawer swipes are still blocked. It releases the temporary input consumer earlier while retaining the outgoing app's closing motion and Samsung's Recents interface.

> **Vibecoded / AI-assisted software.** Substantial parts of this project were generated or developed with AI assistance. This is experimental code running inside a privileged system application. Inspect the source before enabling it. Passing tests does not prove security or compatibility, and there is no guarantee of zero vulnerabilities.

## Launcher support

The module is **launcher-agnostic**: it uses the system's selected third-party default Home component, rather than a list of launcher brands. Any third-party launcher using the compatible One UI Home gesture path is eligible. The appeared Home target must match the selected launcher package; an unrelated app cannot qualify as Home.

This is a design scope, not a claim that every launcher has been physically tested. Launcher-specific animations and implementations can still affect behavior. This module does not modify third-party launcher processes.

## One UI Home compatibility

The current implementation checks this public software build before installing its gesture hooks:

| Component | Required value |
| --- | --- |
| One UI Home version | `18.0.07.53` |
| One UI Home version code | `1800705053` |
| Module app minimum | Android API 35 |
| Runtime | An LSPosed-compatible runtime supporting Xposed API 82 |
| LSPosed scope | **One UI Home** — `com.sec.android.app.launcher` only |

Other One UI Home builds remain inactive. Phone models, firmware fingerprints and device identifiers are neither needed nor included in this repository. A matching build does not guarantee every animation path behaves identically.

## Install

1. Download the signed APK from [Releases](https://github.com/encarnian/OneUIHomeHandoff/releases).
2. Install **One UI Home Handoff**, then enable it in LSPosed.
3. Select **One UI Home** (`com.sec.android.app.launcher`) as the only scope. Leave System Framework, System UI and your third-party launcher out of its scope.
4. Set your preferred third-party launcher as the default Home app and use Samsung's native gesture navigation.
5. Restart One UI Home once so the hooks load. With an existing working root and adb setup:

   ```sh
   adb shell su -c 'killall com.sec.android.app.launcher'
   ```

6. Open the module settings and keep **Enable handoff** on. **Closing duration** is adjustable from 100–175%; 100% is the native pace. The spring-based duration is approximate.

Do not enable another module that modifies the same handoff at the same time. The public app ID is `io.github.encarnian.oneuihomehandoff`; builds with a different app ID are separate applications. Disable an older experimental handoff module before enabling this one.

To undo the hook behavior, disable the module in LSPosed and restart One UI Home. The settings switch prevents new handoff changes; existing transition cleanup still runs.

## Behavior and limits

- Completes the extra Home fade while retaining the app-closing movement.
- Releases the transition's temporary input consumer once an eligible third-party Home target appears, then restores it during native cleanup or a bounded recovery path.
- Caps only the outgoing app's temporary animation-surface opacity below Android's existing touch-obscuring threshold. It does not change the global threshold or mark windows as trusted overlays.
- Retains a short opening expansion when an app interrupts an active Home handoff. The opening-duration slider is intentionally absent. Ordinary openings after Home settles keep their existing system/launcher animation.
- Preserves Samsung's native swipe-and-hold Recents path.

Lock-screen, native-Home, PiP, desktop, multiple-closing-target and secondary-display transitions are excluded. The added opening expansion is limited to opaque, unrotated portrait full-screen targets. A tap before Home has a usable input window may still miss. The module does not promise icon-morph animations for arbitrary launchers.

## Privacy and security

The app requests **no permissions**, including no Internet permission. It contains no telemetry, advertising, runtime downloads, root command execution, screenshots, touch injection or touch replay. Android backup and device-transfer extraction are disabled for its settings.

The exported settings provider is read-only and checks the Binder caller UID: only the module itself and One UI Home's UID may read it. It rejects writes. Detailed diagnostics are off by default; optional logs contain transient task IDs, timing and cleanup events, without app package names, touch coordinates or exception messages.

The source tree and release files exclude device logs, proprietary application files, private signing keys and personal development paths. GitHub necessarily identifies the public repository owner. See [SECURITY.md](SECURITY.md) for the threat model, review findings, residual risks and private reporting instructions.

## Troubleshooting

Confirm the exact One UI Home build, scope, default Home selection and One UI Home process restart. The settings screen reports software compatibility, not proof of hook activation. A module startup log with `READY` confirms activation; `UNSUPPORTED` means the build guard stopped installation.

For a short diagnostic capture, enable **Diagnostic logging**, reproduce once, then stop the capture and turn logging off:

```sh
adb logcat -b main -b system -v threadtime -s OneUIHomeHandoff:I '*:S' > handoff-log.txt
```

Review logs before posting them. Avoid full-device log dumps or phone serial numbers in public issues.

## Build and inspect

All module code, resources, icon artwork and local test code are included under the MIT license. External Android SDK tools and the compile-only Xposed API are separate dependencies; no vendor implementation or runtime binary is bundled as source.

Requirements: JDK 17, Python 3, Android SDK platform 36 and build-tools 36.0.0. The first build uses `curl` to download Xposed API 82, verified against a pinned SHA-256.

```sh
export ANDROID_SDK_ROOT=/path/to/Android/Sdk
./build.sh --unsigned
python3 tests/run-host-checks.py
python3 tools/audit_release.py --apk build/OneUIHomeHandoff-1.2.0.unsigned.apk
```

`./build.sh --debug` builds a separately signed local debug APK. Release-signing instructions are in [docs/BUILDING.md](docs/BUILDING.md). CI produces unsigned inspection artifacts; install the signed APK from Releases for the published build.

Local checks exercise transition cleanup, ownership guards, cancellation, input recovery, generic launcher eligibility, provider access control and diagnostic privacy using Android/Xposed stand-ins. They do not reproduce Android's actual input dispatcher or render animations on every launcher. See [docs/REVIEW.md](docs/REVIEW.md).

This independent project is not affiliated with Samsung, Android, GitHub or LSPosed.

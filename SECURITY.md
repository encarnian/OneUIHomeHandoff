# Security policy and review limits

**Vibecoded / AI-assisted, experimental privileged software.** No independent professional security audit or formal verification has been performed. Neither the code review nor the host tests justify a claim of zero vulnerabilities. Inspect the source and use only a runtime and launcher you trust.

## Scope and trust model

The module executes inside One UI Home through a separately installed LSPosed-compatible runtime. That host already has privileges unavailable to an ordinary launcher. Root, the hook runtime, the Android system, SDK build tools and the selected default Home app are trusted prerequisites. A malicious root component can bypass application-level protections; this module is not protection against a compromised operating system.

The hook is restricted to One UI Home's main process and a supported public application build. It rejects lock-screen, desktop, other-display, native-Home and certain unsupported animation paths. The system's Home-role component and the appeared Home target must match. No IPC caller can choose the active Home package through the settings provider.

## Controls checked in this release

- **No runtime network or root execution:** no requested app permissions, telemetry, embedded credentials, socket clients, root shell, runtime code downloads or dynamic code loaders.
- **Authenticated, read-only configuration:** Binder UID is checked against the app's own UID and One UI Home's installed UID. Missing One UI Home or an unrelated caller is rejected. Remote writes, raw queries, inserts, updates and deletes are rejected. Caller-supplied package names are not trusted.
- **Private settings:** preferences use the app's private data directory. Android backup and cloud/device-transfer extraction are explicitly excluded.
- **Minimal components:** the launcher settings activity is exported to be user-openable. It has no intent-driven setting setters, file-sharing entry point or sensitive returned data. The authenticated settings provider is the only exported IPC endpoint.
- **Bounded mutation:** input-registration and animation-surface changes are associated with the owning transition/controller. Native cleanup, a new gesture, cancellation and recovery timers remove state. Invalid surface/transition identities stop the optional opening motion. Non-finite alpha requests are not retained for restoration.
- **Diagnostic minimization:** detailed diagnostics default to off. Module logs omit source app package names, touch coordinates, entered text, screenshots and exception-message contents. Task IDs and timing remain sensitive activity metadata if a user opts in and shares a log.
- **Source/dependency hygiene:** the Xposed compile API has a pinned checksum; CI actions use full commit hashes and read-only repository permissions. CI has no signing key and cannot publish a release. Builds normalize APK ZIP metadata for comparison.
- **Release hygiene:** a dedicated project-only signing certificate is used. Signing keys, credentials, development logs, local Git history and personal paths are excluded from publication. Source artifacts contain the module implementation and independently written test doubles, not vendor implementation code.

## Residual risks

Changing transition/input behavior can cause missed or unintended taps, animation glitches, launcher crashes or temporary input problems. The opacity adjustment intentionally makes Home touchable before the outgoing app surface has fully disappeared. It is confined to an eligible user-initiated Home handoff; global obscured-touch protection and trusted-overlay flags are not changed. This timing tradeoff still warrants source review.

The host checks use stand-ins for Android services and rendering. They cannot demonstrate the behavior of every device, launcher, runtime modification or firmware update. Recovery can fail if the host process/runtime is itself malfunctioning; disabling the module and restarting One UI Home removes the hooks. Generalized launcher eligibility in this release has not been physically tested with every launcher.

Do not mistake open source, APK signing, a passing CI run or this document for a security guarantee. Public repository ownership and public commits remain visible on GitHub; this project cannot make a GitHub account anonymous.

## Reporting

Use the repository's **Security → Report a vulnerability** flow for a potential vulnerability. Please do not publish exploits, credentials or unredacted device logs in ordinary issues. Include a minimal reproduction, module version and relevant public One UI Home version. Personal names, phone serials, account identifiers, installed-app inventories and complete device logs are not required.

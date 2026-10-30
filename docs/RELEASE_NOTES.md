# One UI Home Handoff 1.2.0 — experimental public release

An open-source LSPosed module targeting the post-gesture handoff delay between One UI Home and a third-party default launcher. It releases the temporary Home transition input consumer earlier, retains visible app-closing motion and keeps Samsung's native Recents path.

**Vibecoded / AI-assisted.** Substantial implementation was developed with AI assistance. This is experimental privileged hooking software: inspect the source before use. Local checks are not an independent security audit, and zero vulnerabilities or universal launcher compatibility cannot be guaranteed.

## Support

- No launcher-brand allowlist: the hook follows the selected third-party default Home component and requires its appeared target to match.
- Current supported public One UI Home build: `18.0.07.53` / `1800705053`. Other builds remain inactive.
- Scope only **One UI Home** (`com.sec.android.app.launcher`).
- The closing-duration slider is retained; the opening-duration slider is absent.
- Public app ID: `io.github.encarnian.oneuihomehandoff`. Disable any older module modifying the same handoff before enabling this one.

## Review

The release passes 131 local host assertions. Gitleaks v8.30.1 reports no detected secrets in the source tree. Static source/package checks cover scope, permissions, backup exclusions, expected exported components, own-package DEX definitions, common credential/private-path patterns and publication artifacts. The signing certificate has a project-only subject, and the source history starts fresh with a project identity.

All module code, icon resources, tests and build/review scripts are included under MIT. No vendor implementation, personal device logs, signing key or credential is included. See README.md, SECURITY.md and docs/REVIEW.md for precise limits.

## Assets

- Signed APK and SHA-256 checksum.
- Complete source archive.
- Sanitized local review summary and aggregate checksums.

GitHub CI builds unsigned inspection artifacts. It does not hold a release-signing key or publish signed installers.

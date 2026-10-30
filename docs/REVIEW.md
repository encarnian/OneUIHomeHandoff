# Public release review

The review covers this repository's Java implementation, manifest, resources, build script, publication artifacts and workflow configuration. It does not audit the Android platform, vendor implementation, hook runtime or every third-party launcher.

## Changes for the public release

- Replaced launcher-brand allowlisting with matching against the selected third-party default Home package.
- Restricted the exported settings provider to the module and One UI Home UIDs, while retaining read-only operation.
- Removed app package names and exception messages from module diagnostics.
- Disabled backup and transfer extraction, retained private preferences and zero requested permissions.
- Kept the opening-duration control absent and retained the closing-duration control.
- Added original adaptive and monochrome icon resources.
- Added prominent vibecoded / AI-assisted disclosure and source-review guidance.
- Used a new public application/signing identity and a source tree without private development history or device logs.

## Local verification

The host suite exercises the actual compiled hook/configuration classes with independently written Android/Xposed stand-ins. It covers finish deferral, cancellation, transition identity changes, watchdog cleanup, source opacity restoration, configuration bounds, caller UID authentication, rejected writes, generic Home matching, excluded paths and diagnostic privacy.

The release passes **131 host assertions**. A source-tree scan with the official **Gitleaks v8.30.1** tool reports no detected secrets. Both the scanner binary download and the compile-only API dependency were checked against their published checksums. Secret-pattern scanning does not establish the absence of every credential format or vulnerability.

The static/package audit checks component scope, absence of requested permissions, backup exclusions, expected module-only DEX definitions, dependency checksums, prohibited publication artifacts and common credential/private-path patterns. A clean source build is compared with the release build. Signing and APK alignment are verified separately using Android SDK tools.

## Interpretation

These are bounded engineering checks, not an independent security certification. The public launcher-generalization and provider-hardening changes have not been physically tested across all launchers. Supported-build checks remain in place. Animation timings and cancellation paths can still behave differently across launcher and platform combinations.

No private phone logs, device identifiers, hardware profiles, local author email or private signing material are part of the release. Compatibility constants refer to public application software versions, not a device identity.

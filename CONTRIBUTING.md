# Contributing

Read README.md and SECURITY.md first. This is vibecoded / AI-assisted code that needs careful human review, particularly around input handling, surface ownership and recovery paths.

Keep hooks scoped to One UI Home and retain conservative compatibility guards. Explain a concrete problem, the ownership/cleanup rules involved and how the change was checked. Add meaningful regression coverage for security or lifecycle changes; do not treat host stand-ins as proof of on-device behavior.

Do not submit proprietary application code, vendor APKs, personal logs, phone identifiers, credentials or signing material. Public issue reports need only the module version, public One UI Home version, navigation mode and a minimal reproduction. Redact logs before sharing.

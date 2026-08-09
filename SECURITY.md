# Security Policy

## Supported versions

Security fixes are applied to the current `main` branch and the latest source release. Version `0.4.x` is the first public source release line.

This repository does not publish or support an official APK binary. Locally produced debug APKs are development artifacts and are not GitHub Release assets.

## Reporting a vulnerability

Please report vulnerabilities privately through [GitHub Private Vulnerability Reporting](https://github.com/namseokyoo/floating-voice-android/security/advisories/new).

Do **not** open a public issue containing:

- Telegram API ID, API Hash, phone number, login code, or two-step password
- TDLib session files, database keys, recordings, or account identifiers
- a working exploit or a bypass that could send to an unverified recipient

Include the affected commit or version, Android version, reproduction steps, impact, and a minimal proof of concept with all personal data removed. Reports are reviewed on a best-effort basis; no response-time or bounty commitment is offered.

## Security boundaries

High-priority reports include:

- credential or session exposure
- fixed-recipient verification bypass
- message sending without an explicit recording action
- premature recording deletion or unintended upload
- exported-component, overlay, foreground-service, or permission abuse
- supply-chain compromise in the Gradle or TDLib build path

## Public issues

Use public issues for non-sensitive bugs and feature requests only. Replace account names, chat IDs, file paths, screenshots, and logs with safe placeholders before posting.

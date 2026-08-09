# Contributing

Thanks for improving Floating Voice. The repository is intentionally source-only and handles sensitive Telegram account data, so privacy and send-safety take priority over convenience.

## Before opening a pull request

1. Open or reference an issue for behavior changes.
2. Keep real Telegram credentials, phone numbers, login codes, account names, chat IDs, recordings, and TDLib sessions out of commits and screenshots.
3. Do not commit generated TDLib Java/JNI artifacts, APKs, build directories, signing keys, or local configuration.
4. Preserve the fixed-recipient verification and success-only recording deletion rules.
5. Do not add automatic test messages, silent background sends, analytics, advertising, or a developer-operated credential relay.
6. Keep Korean and English resources synchronized.

## Build

Prepare TDLib from the pinned source revision as described in the README, then run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew clean test lintDebug assembleDebug
```

At minimum, source-only changes must pass:

```bash
./gradlew :core:test
bash -n scripts/*.sh
```

## Commit and pull-request notes

Explain:

- what changed and why
- user-visible and migration impact
- security and privacy impact
- exact tests and device/Android versions used
- whether any behavior remains unverified

Use placeholders such as `[REDACTED]`, `@example_bot`, and synthetic phone numbers in all public material.

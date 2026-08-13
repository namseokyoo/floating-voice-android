# Floating Voice Domain Glossary

## App settings

User preferences that do not change Telegram identity or routing: language, floating-button size, and floating-button color.

## Settings navigation

Home exposes one gear icon. It opens a compact three-row popup menu anchored to the gear and routes directly to the App settings, Transfer destination, or Telegram connection page. There is no bottom sheet or settings landing/dashboard page, and unrelated settings must not be stacked into one scrolling screen.

## Transfer destinations

The encrypted catalog of verified private Telegram bot chats available to the authenticated account. v0.7.0 supports multiple saved destinations, one persistent default, a next-one selection, and a current-recording override. Verification binds account, chat, bot user, and canonical username identity without sending a test message.

User-facing Korean term: `전송 대상`.

## Destination lifetime

- **Default:** persistent and used for future recordings.
- **Next one:** consumed when the next recording starts and never silently promoted to default.
- **Current recording:** affects only the active recording.
- **Dispatch snapshot:** frozen before send; retry and recovery retain the same account, destination, chat, bot identity, and recording path.

Invalid, disabled, deleted, wrong-account, or stale destinations never fall back to another destination automatically.

## Telegram connection

The authenticated TDLib account/session plus API ID, API Hash, and account phone number. Its page shows API ID and phone directly, masks all but the final four API Hash characters, and keeps logout/session revoke as a separate destructive action. Changing API/account identity may require logout; changing only the transfer destination does not.

## Current stable baseline

`v0.7.0` is the released stable baseline. The exact A52s-accepted APK is published as the GitHub Release asset. System STT, local OGG archive, Android text/audio Sharesheet output, and configurable primary-button roles are not part of this baseline.

## Canonical roadmap

The tracked source of truth is `docs/plans/floating-voice-release-master.md`. The completed v0.7 contract is in `docs/plans/floating-voice-v0.7.0-multi-destination.md`; the next detailed plan is `docs/plans/floating-voice-v0.8.0-stt-sharing.md`. README store-preparation checklists are not product-roadmap authority.

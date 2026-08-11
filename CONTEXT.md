# Floating Voice Domain Glossary

## App settings

User preferences that do not change Telegram identity or routing: language, floating-button size, and floating-button color.

## Transfer destination

The single verified Telegram bot chat that receives new voice and text sends. In v0.6.2 the destination can be replaced while the Telegram connection remains authenticated. A replacement is committed only after the new bot is verified; failure keeps the previous destination.

User-facing Korean term: `전송 대상`.

## Telegram connection

The authenticated TDLib account/session plus API ID, API Hash, and account phone number. Changing API/account identity may require logout; changing only the transfer destination does not.

## Non-goals for v0.6.2

Multiple saved destinations, a default destination among many, and per-send destination selection are not part of this version.

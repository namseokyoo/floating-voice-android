# Floating Voice

Current app version: **0.2.0** (`versionCode 4`).

Plain-Java Android app (`com.sidequestlab.floatingvoice`) using a **TDLib user account session**. It records mono OGG/Opus from a draggable overlay and sends a TDLib `InputMessageVoiceNote` only to a user-confirmed fixed Telegram bot chat.

## Security and behavior

- Enter `api_id`, `api_hash`, phone number, auth code, 2FA password, and bot username at runtime. No key, phone, code, or password is embedded in source/resources/build files.
- Persisted API credentials, phone, bot username, TDLib database key, and confirmed chat metadata are AES-GCM encrypted with a non-exportable Android Keystore key. Auth codes and 2FA passwords are never persisted and are cleared after submission.
- This is not the Telegram Bot API. The app logs in a regular Telegram user through TDLib.
- Bot confirmation uses `SearchPublicChat`, verifies that the chat is private, then verifies `GetUser(...).type` is `UserTypeBot`. Only then are chat ID/title persisted.
- There is no automatic test message. A message is sent only after the user taps the overlay to start recording and taps it again to stop.
- A recording is mapped to the TDLib temporary message ID and retained until `UpdateMessageSendSucceeded`. `UpdateMessageSendFailed` and immediate send errors retain the `.ogg` file.

## Requirements

- JDK 17
- Android SDK `/opt/homebrew/share/android-commandlinetools`
- Android platform 36 and build-tools 36.1.0
- Gradle wrapper 8.13 / Android Gradle Plugin 8.13.0
- Generated Java TDLib interface and Android JNI libraries
- An `arm64-v8a` Android 10+ phone. This personal build intentionally excludes 32-bit and emulator ABIs.

## Add TDLib artifacts

The `:tdlib` Android library intentionally contains no stubs. Copy output from the official TDLib Java build exactly as follows:

```text
tdlib/src/main/java/org/drinkless/tdlib/Client.java
tdlib/src/main/java/org/drinkless/tdlib/TdApi.java
tdlib/src/main/jniLibs/arm64-v8a/libtdjni.so
```

If TDLib was built with the official `example/android/build-tdlib.sh`, copy `tdlib/java/org/drinkless/tdlib/*.java` and copy each `tdlib/libs/<abi>/*.so` into the matching `jniLibs/<abi>/` directory. Include companion shared libraries (for example `libssl.so`, `libcrypto.so`, or `libc++_shared.so`) when that build produced them.

The implementation was aligned to TDLib source commit `022d60202e446ad1287b9fb68e687c8a0760788b` (2026-07-17). The local build uses NDK `28.2.13676358`, arm64 only, and 16 KB ELF alignment for current Android devices. See **TDLib API compatibility risks** below before using artifacts from another commit/interface mode.

## Build and test

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :core:test
./gradlew :app:assembleDebug
```

`:tdlib:verifyTdlibArtifacts` fails early with an actionable message when generated Java or JNI `.so` artifacts are absent. Pure-Java validation tests can still run with `:core:test`.

## First run

1. Obtain your own Telegram `api_id` and `api_hash` from <https://my.telegram.org>. Enter them with the user-account phone and target bot username.
2. Tap **암호화 설정 저장 / Telegram 연결 시작**.
3. When the status asks for a phone number, tap **전화번호 제출**; enter the Telegram authentication code, 2FA password, or email code only when that specific field appears.
4. When authentication is complete, tap **메스 봇 찾기 및 전송 대상 확정** and check the persisted title/ID shown by the UI.
5. Tap **필수 권한 허용** and grant overlay, microphone, and Android 13+ notification permissions while the activity is visible.
6. Tap **플로팅 버튼 시작** while the setup activity is still visible. Android 14+ checks microphone foreground-service eligibility at this point; do not try to start it from a background automation.
7. Drag the button as needed. Tap **녹음** once to record OGG/Opus mono; tap **전송** to stop and send to the fixed chat.
8. Use **Telegram 로그아웃 / 세션 해제** to call TDLib `LogOut`. Stopping the overlay during a recording retains the partial local recording and does not send it.

Recordings live under app-specific external Music storage in `voice_notes/`. Failed/interrupted files are intentionally retained for manual recovery. Uninstalling or clearing app data can remove app-specific files and the Keystore key.

## Architecture

- `:core`: Android-free `AppConfig` validation and `UsernameNormalizer`, with JUnit 5 tests.
- `:tdlib`: local Android library contract for generated `Client`, `TdApi`, and JNI libraries.
- `FloatingVoiceApp`: application-scoped ownership of secure settings and the one TDLib client.
- `TelegramRepository`: TDLib authorization state machine, verified bot resolution, fixed-chat voice sending, persistent temporary-message/file mapping, success-only deletion, and logout.
- `MainActivity`: explicit configuration/auth/status UI plus permission and overlay controls.
- `FloatingVoiceService`: user-started `microphone|specialUse` foreground service, persistent notification, draggable overlay, and `MediaRecorder` OGG/Opus state machine. It returns `START_NOT_STICKY`; reboot/process death requires reopening the app and starting the overlay again.

## TDLib API compatibility risks

The checked source commit uses these newer generated API shapes:

- `SetTdlibParameters` includes `databaseEncryptionKey` directly and does **not** use the older separate `CheckDatabaseEncryptionKey` flow.
- `SendMessage` contains `topicId: MessageTopic` (not older `messageThreadId`).
- `InputMessageVoiceNote` wraps `InputVoiceNote`, plus `FormattedText caption` and `MessageSelfDestructType`.
- `MessageSendOptions` includes `suggestedPostInfo` and other current fields; the app uses its generated zero-argument constructor/defaults.
- Send completion/failure is correlated with `UpdateMessageSendSucceeded.oldMessageId` and `UpdateMessageSendFailed.oldMessageId`.
- Bot verification assumes current `ChatTypePrivate.userId`, `GetUser.userId`, and `UserTypeBot` generated names.

TDLib generated Java has a zero-argument constructor for concrete classes at the referenced commit; this code mostly assigns named fields to reduce full-constructor churn. If the separately built artifacts use a different commit, compile errors around `topicId`, `InputVoiceNote`, auth parameter fields, update fields, or `Client.create/send` are expected and must be adapted to that exact generated `TdApi.java`. A JSON/JSONJava build is incompatible; build the official **Java** interface.

# Privacy Notice / 개인정보 안내

**Effective date / 시행일:** 2026-08-10

**Applies to / 적용 대상:** Floating Voice source version `0.4.0`

Floating Voice is currently published as source code only. No official APK is distributed through GitHub Releases or an app store.

플로팅 보이스는 현재 소스 코드만 공개합니다. GitHub Release나 앱스토어를 통해 공식 APK를 배포하지 않습니다.

## English

### Data flow

The app has no analytics SDK, advertising SDK, or developer-operated backend. It communicates directly with Telegram through TDLib after the user provides their own Telegram API credentials and signs in.

Telegram processes account authentication, chat lookup, and voice-message delivery under Telegram's own terms and privacy practices. This project does not control Telegram's processing.

### Data stored on the device

The app may store:

- Telegram API ID, API Hash, and account phone number
- verified target-bot username, chat ID, and title
- a TDLib database-encryption key and TDLib session data
- recordings awaiting final send confirmation or retained after failure

Runtime settings are encrypted with AES-GCM using a non-exportable Android Keystore key. Login codes and two-step verification passwords are not persisted. Retained OGG recordings are not separately encrypted.

### Permissions

- Internet: Telegram authentication and messaging through TDLib
- Microphone: recording after an explicit tap
- Display over other apps: user-enabled floating control
- Notifications and foreground service: visible service state and stop action

The service is user-started, does not restart after reboot, and does not send an automatic test message.

### Retention and deletion

A recording is deleted only after TDLib reports final send success. Failed or interrupted recordings remain in app-specific storage for manual recovery. Clearing app data or uninstalling the app removes app-specific settings, sessions, and files according to Android behavior. The current UI does not yet provide a single complete-data deletion action.

### Public contributions

Do not include real credentials, account details, recordings, session files, or unredacted screenshots in issues or pull requests. Use [private vulnerability reporting](SECURITY.md) for sensitive security reports.

## 한국어

### 데이터 흐름

앱에는 분석 SDK, 광고 SDK, 개발자가 운영하는 별도 서버가 없습니다. 사용자가 자신의 Telegram API 정보로 로그인한 뒤 TDLib를 통해 Telegram과 직접 통신합니다.

계정 인증, 대화 검색, 음성 메시지 전달은 Telegram의 약관과 개인정보 처리 기준에 따라 Telegram이 처리합니다. 이 프로젝트는 Telegram의 데이터 처리를 통제하지 않습니다.

### 기기에 저장되는 데이터

앱은 다음 정보를 저장할 수 있습니다.

- Telegram API ID, API Hash, 계정 전화번호
- 확인된 대상 봇 username, chat ID, 제목
- TDLib 데이터베이스 암호화 키와 TDLib 세션 데이터
- 최종 전송 성공을 기다리거나 실패 후 보관된 녹음 파일

설정값은 Android Keystore의 비추출 키를 사용해 AES-GCM으로 암호화합니다. 로그인 코드와 2단계 인증 비밀번호는 저장하지 않습니다. 실패 후 남은 OGG 녹음 파일은 별도로 암호화되지 않습니다.

### 권한

- 인터넷: TDLib를 통한 Telegram 로그인·메시지 전송
- 마이크: 사용자가 버튼을 누른 뒤 녹음
- 다른 앱 위 표시: 사용자가 켠 플로팅 컨트롤
- 알림·포그라운드 서비스: 보이는 서비스 상태와 종료 동작

서비스는 사용자가 직접 시작하며, 재부팅 후 자동 재시작하거나 테스트 메시지를 자동 전송하지 않습니다.

### 보관과 삭제

TDLib가 최종 전송 성공을 알린 뒤에만 녹음 파일을 삭제합니다. 실패하거나 중단된 녹음은 복구를 위해 앱 전용 저장소에 남습니다. 앱 데이터 삭제 또는 앱 제거 시 Android 동작에 따라 앱 전용 설정·세션·파일이 제거됩니다. 현재 UI에는 모든 로컬 데이터를 한 번에 지우는 기능이 아직 없습니다.

### 공개 기여 시 주의

이슈와 Pull Request에 실제 인증정보, 계정 정보, 녹음, 세션 파일, 개인정보가 보이는 스크린샷을 포함하지 마세요. 민감한 보안 문제는 [비공개 취약점 제보](SECURITY.md)를 사용하세요.

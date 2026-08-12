# Floating Voice Release Master Plan

> **For Hermes:** 이 문서는 재시작 후에도 유지되는 단일 릴리즈 로드맵이다. 한 번에 한 버전만 구현하고, 각 버전의 선행검증·구현·실기기·릴리즈 게이트가 모두 통과한 뒤 사용자 승인으로 다음 버전으로 이동한다.

**기준 시각:** 2026-08-12 05:48 KST<br>
**현재 안정 기준선:** `v0.6.4` (`808e7942e618e2aaaf3527b27d89bac9256e2601`)<br>
**current_stage:** `V7-08 AUTOMATED PASS / NEXT=V7-09 RC1`<br>
**status:** `v7_08_automated_pass_next_v7_09_rc1` — V7-06 메인 전송 대상 관리 UI와 V7-07 Activity 기반 picker·현재 녹음 대상 고정이 A52s에서 사용자 검증을 통과했다. V7-08은 READY→GetMe 계정 전환 시 이전 route 폐기, 인증 이탈 시 fail-closed, service restart 시 일회성 route 폐기, pending snapshot·파일 보존, restart UNKNOWN 처리, 중복 final callback 억제, 자동 retry·fallback 금지를 보강했다. clean core 165 + app 51 고유 unit tests, Debug/Release unit 51씩, localization hard gate, Debug/Release Lint 오류 0, debug/release assembly가 통과했다. 다음은 immutable commit에 결박한 공식 서명 V7-09 RC1과 사용자 직접 A52s 집중 검증이다.<br>
**구현 상태:** v0.5.0 인터랙션 기반, v0.6.0 Quiet Recorder, v0.6.1 플로팅 스타일·safe area, v0.6.2 Telegram 세션 유지 대상 변경, v0.6.3 개별 설정 페이지, v0.6.4 연결정보 인라인·톱니 anchored PopupMenu까지 완료. v0.6.4는 tests 110/fail 0, lint 오류 0, 공식 인증서·v2/v3·ZIP/ELF 16KB 검증과 local/origin/GitHub 일치를 통과함. V7-01은 legacy account/target pure migration model을 커밋 `f1d45e4`로 고정함. V7-02는 destination/catalog/scope/route state machine/dispatch snapshot과 default·next-one·current-recording·FREEZING·no-fallback 계약을 TDD로 구현함. latest catalog identity/revision revalidation, route-attempt-bound completion/abort, scalar-only snapshot, local-ID 재바인딩 차단까지 보완했으며 전체 tests 140/fail 0, lint 오류 0, debug/release assembly와 독립 follow-up review Blocker/High 0을 통과함. 실제 Android 저장소·UI 연결은 건드리지 않음. V7-03은 versioned catalog codec, Android Keystore 암호화 AtomicFile, copy→validate→persist→read-back→publish, raw V7-01 migration, schema marker, backup-only/corrupt recovery, legacy·pending 보존을 구현함. 최종 clean tests 188/fail 0, lint 오류 0, debug/release assembly와 독립 follow-up review Blocker/High 0을 통과함. 공식 서명 내부 APK `r1`은 기존 앱 위 설치·실제 Telegram 전송 후 형의 “이 메시지를 받으면 테스트는 성공이야” 확인으로 A52s 실기기 PASS 처리함. V7-04는 request token, expected client/account, private-chat·bot·canonical ID 검증, duplicate/identity-change 차단, account 전환·저장 race 직렬화, disabled-state 보존, TDLib lookup adapter를 구현함. 최종 core 138 + app 42 = 고유 tests 180/fail 0, Debug/Release Lint 오류 0, 양쪽 assembly·ZIP/ELF 16KB와 독립 review Blocker/High/Medium/Low 0을 통과함. 실제 봇 2개 A52s 게이트는 사용자 결정으로 V7-06 UI 뒤로 이월하고, Hermes의 기기 조작 없이 형이 전달받은 APK로 직접 검증함.

## 1. 목표와 제품 원칙

### 목표

Floating Voice를 빠른 Telegram 음성 전송 도구에서 다음 순서로 확장한다.

1. 여러 개의 검증된 Telegram 봇 목적지와 불변 dispatch
2. Android 시스템 STT 기반 텍스트 공유와 로컬 OGG 출력
3. 메인 버튼 역할 지정과 capability별 안전 게이트
4. 장기 안정성 검증 후 `1.0.0`

### 절대 보존할 핵심 경로

```text
플로팅 버튼 1탭 → OGG/Opus 녹음 시작
녹음 중 중앙 버튼 탭 → 정지 → 검증된 Telegram 목적지로 음성 전송
TDLib 최종 성공 확인 → 로컬 파일 삭제
실패/불확실 → 파일 보존
```

### 공통 안전 불변조건

- 설정 저장·로그인·목적지 확인 과정에서 테스트 메시지를 자동 전송하지 않는다.
- `queued/pending`, `sent`, `shared`를 같은 의미로 쓰지 않는다.
- 음성 파일은 `updateMessageSendSucceeded` 확인 전까지 삭제하지 않는다.
- 실패 시 목적지를 임의로 바꾸거나 기본 목적지로 자동 폴백하지 않는다.
- 텍스트 초안과 STT 결과를 로그·알림·환경설정에 자동 저장하지 않는다.
- 공개 APK에 Telegram 자격정보·세션·서명키·개인 경로를 넣지 않는다.
- 사용자가 명시적으로 승인하기 전 Git commit/push, APK 배포, 외부 메시지 발송을 하지 않는다.
- 모든 폰·태블릿 실기기 검증은 사용자가 직접 수행한다. Hermes는 ADB 조회·설치·실행·권한 변경·탭·입력을 포함해 기기를 조작하지 않고, 검증 APK·체크리스트·결과 기록만 담당한다.

## 2. 버전 순서와 범위

| 순서 | 버전 | 핵심 결과 | 상세 계획 | 상태 |
|---|---|---|---|---|
| 기준선 | `0.4.1` | 단일 검증 봇에 1탭 OGG/Opus 음성 전송 | 과거 릴리즈 | 완료 |
| 1 | `0.5.0` | 취소·상태 머신·얕은 메뉴·고정 목적지 텍스트 전송 | `docs/releases/v0.5.0.md` | 완료 |
| 2 | `0.6.0` | Quiet Recorder 홈·연결·설정 정보구조 | `docs/releases/v0.6.0.md` | 완료 |
| 2.1 | `0.6.1` | 플로팅 아이콘·색상·safe area | `docs/releases/v0.6.1.md` | 완료 |
| 2.2 | `0.6.2` | Telegram 세션 유지 단일 대상 변경 | `docs/releases/v0.6.2.md` | 완료 |
| 2.3 | `0.6.3` | 톱니 메뉴에서 개별 설정 페이지 직접 진입 | `docs/releases/v0.6.3.md` | 완료 |
| 2.4 | `0.6.4` | 연결정보 인라인·anchored PopupMenu | `docs/releases/v0.6.4.md` | **실기기 완료 기준선** |
| 3 | `0.7.0` | 여러 private bot 목적지·기본/다음 1회/이번 녹음·불변 dispatch | [v0.7.0 다중 목적지 계획](floating-voice-v0.7.0-multi-destination.md) | V7-08 자동 검증 PASS · V7-09 RC 준비 |
| 4 | `0.8.0` | 시스템 STT 텍스트 공유·Android Sharesheet·로컬 OGG 출력 | [v0.8.0 STT·공유 계획](floating-voice-v0.8.0-stt-sharing.md) | 계획 |
| 5 | `0.9.0` | 메인 버튼 역할 지정: Telegram 음성·텍스트 공유·빠른 메모 | v0.8.0 실기기 PASS 후 상세화 | 범위 확정 |
| 안정화 | `0.10.x+` | 실사용 피드백·회귀 수정·성능·복구 강화 | 버전별 이슈로 생성 | 미정 |
| 안정판 | `1.0.0` | 핵심 계약과 업데이트 안정성 보장 | 아래 1.0 게이트 | 미정 |

`0.10.x` 다음에 자동으로 `1.0.0`을 붙이지 않는다. 안정성 게이트가 미달이면 `0.11.0`, `0.12.0`으로 계속 진행한다.

## 3. 최신 UX 기준

### 대기 상태

- **1탭:** 즉시 녹음 시작
- **드래그:** 버튼 이동만 수행
- **길게 누르기:** 최대 3개의 라벨이 있는 얕은 1단 메뉴 표시
- **동적 목록:** radial 고리에 나열하지 않고 읽을 수 있는 세로 패널/바텀시트 사용

### 녹음 상태

- 중앙 버튼: 정지 후 현재 녹음 목적지로 전송
- 보조 동작: `취소`, `이번 녹음 보낼 곳`, `출력`
- 전체 목적지 이름을 전송 전에 항상 표시

### 텍스트 작성 상태

- 작성 완료 후 보이스 모드와 같은 목적지 선택 UI를 제공한다.
- 전송 확인 시 선택 목적지를 불변 snapshot으로 고정한다.
- 닫기/뒤로는 전송하지 않고, 실패 시 다른 목적지로 자동 폴백하지 않는다.

### 목적지 수명

- **기본 목적지:** 메인 앱에서 명시적으로 변경; 미래 녹음에 적용
- **다음 1회:** 대기 상태에서 선택; 다음 녹음 시작 시 소비되고 취소 후 부활하지 않음
- **이번 녹음:** 녹음 중 선택; 현재 녹음에만 적용
- **dispatch snapshot:** 정지/전송 시작 시 불변으로 고정; 재시도도 같은 목적지를 사용

### STT/공유

```text
말해서 텍스트 공유
→ visible non-exported SpeechReviewActivity
→ 시스템 SpeechRecognizer
→ 편집 가능한 미리보기
→ 사용자가 Share 탭
→ Android Sharesheet
→ 사용자가 카카오톡·메모·메일 등을 선택
```

Telegram 음성 녹음과 STT 마이크 사용은 동시에 수행하지 않는다.

## 4. 릴리즈 간 선행조건

### 현재 완료 기준선 — `0.6.4`

- v0.5.0의 취소·상태 머신·텍스트 전송 계약이 유지된다.
- Quiet Recorder 홈과 홈 톱니 → 3항목 PopupMenu → 개별 페이지 구조가 실기기에서 확인됐다.
- Telegram 세션을 유지한 단일 대상 변경, API Hash 마스킹, 연결정보 인라인 표시가 검증됐다.
- 다음 기능 버전은 이 기준선을 깨지 않고 update-install 가능한 형태로 시작한다.

### `0.7.0` 시작 전

- `v0.6.4` commit/APK/실기기 결과를 R0 기준선으로 고정한다.
- 기존 단일 `bot_username`/`TargetChat`을 잃지 않는 migration test를 먼저 작성한다.
- legacy target은 canonical bot user ID/owner account ID가 없으므로 migration 뒤 `NEEDS_REVERIFY`로 차단하고, 메시지 없이 재검증한 뒤 사용한다.
- `기본`, `다음 1회`, `이번 녹음`, `dispatch snapshot` 수명 계약을 변경 없이 확정한다.

### `0.8.0` 시작 전

- `0.7.0`의 dispatch snapshot·실패 보존·retry 대상 고정이 안정적이어야 한다.
- Telegram target gate와 STT/로컬 output capability를 분리할지 승인한다. 권장안은 중앙 1탭 Telegram만 verified route를 요구하고 STT/명시적 local output은 독립 허용하는 것이다.
- A52s에서 `SpeechRecognizer`/on-device 지원·한국어 동작을 선행 spike로 측정한다.
- SAF 로컬 보관 위치·보존정책을 사용자에게 보여주고 승인받는다.

### `0.9.0` 시작 전

- `0.8.0`의 Telegram 음성·STT 텍스트 공유·로컬 output을 각각 독립적으로 실기기 검증한다.
- persistent `PrimaryAction`은 `TELEGRAM_VOICE`, `TEXT_SHARE`, `QUICK_MEMO` 중 하나로 제한한다.
- 현재 역할을 아이콘·색상·짧은 라벨로 항상 표시하고 색상만으로 구분하지 않는다.
- 선택한 역할의 로그인·권한·저장소가 준비되지 않으면 실행을 차단하고 이유를 표시한다. 다른 역할로 자동 폴백하지 않는다.
- 길게 누른 메뉴의 다른 기능은 1회성 action이며 persistent 기본 역할을 바꾸지 않는다.

## 5. 공통 단계 게이트

모든 구현 버전은 다음 순서로 진행한다.

1. **R0 기준선:** 현재 브랜치·버전·테스트·APK/기기 상태 기록
2. **R1 위험 spike:** 가장 큰 플랫폼/데이터 위험을 제품 코드 확대 전에 검증
3. **R2 순수 모델 TDD:** 상태·라우팅·마이그레이션을 `core`에서 먼저 검증
4. **R3 Android 통합:** 서비스/UI/TDLib 연결
5. **R4 정적 검증:** unit·lint·localization·manifest·secret scan
6. **R5 실기기 검증:** 실제 A52s 및 Android 10 호환성
7. **R6 릴리즈 후보:** clean build·서명·정렬·메타데이터·freshness 검증
8. **R7 사용자 승인:** commit/tag/release 전 별도 승인

한 단계가 실패하면 같은 버전의 첫 미통과 게이트로 돌아간다. 미통과 상태에서 다음 버전을 병행 구현하지 않는다.

`v0.7.0`부터 릴리즈 후보와 최종 릴리즈를 분리한다. RC는 공식 서명 APK로 실제 사용·복구 검증을 수행하는 단계이고, 최종 릴리즈는 승인된 RC와 동일한 소스를 기능 추가 없이 다시 clean build하여 산출물·업데이트 설치를 최종 확인하는 단계다. RC 이후 소스나 리소스가 바뀌면 기존 RC를 폐기하고 `versionCode`를 올린 RC2/RC3로 전체 회귀와 실기기 검증을 다시 수행한다. 최종 A52s PASS 전에는 tag와 GitHub Release를 만들지 않는다.

## 6. 공통 검증 명령

프로젝트 루트에서 실행한다.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :core:test
./gradlew clean test lintDebug lintRelease assembleDebug assembleRelease
python3 ~/.hermes/skills/software-development/android-native-app-delivery/scripts/verify_android_localization.py .
bash -n scripts/*.sh
```

릴리즈 APK에는 다음을 추가한다.

```bash
apksigner verify --verbose --print-certs <apk>
apksigner verify --min-sdk-version 24 --verbose --print-certs <apk>
zipalign -c -P 16 -v 4 <apk>
aapt2 dump badging <apk>
shasum -a 256 <apk>
python3 ~/.hermes/skills/software-development/android-native-app-delivery/scripts/verify_apk_elf_alignment.py <apk>
```

결과는 `명령 종료코드`, `테스트 수`, `lint error/warning`, `APK mtime/size/hash`, `서명 인증서`, `실기기 수행 결과`를 분리해 기록한다.

## 7. 교차 버전 리스크 레지스터

| ID | 위험 | 영향 | 발생 가능성 | 선행 검증 | 완화/폴백 | 해제 조건 |
|---|---|---:|---:|---|---|---|
| X-01 | overlay tap/drag/long-press 충돌 | 오녹음·오전송 | 높음 | 순수 gesture reducer + 경계값 실기기 테스트 | 단일 recognizer, mutually exclusive terminal event | 빠른 반복 100회 무오동작 |
| X-02 | 동적 overlay window/IME leak | 화면 입력 차단·서비스 오류 | 높음 | composer spike, stop/logout/권한회수 테스트 | 중앙 cleanup registry, Activity fallback | 모든 종료 경로 후 window 0개 |
| X-03 | 짧은 녹음 stop/cancel race | 파일 손상·중복 전송 | 높음 | 0–2초·rapid tap 반복 | 상태 머신, one-shot terminal action, idempotent cleanup | 중복 send 0, 유실 0 |
| X-04 | 목적지 변경 race | 잘못된 방 전송 | 매우 높음 | immutable snapshot unit/integration tests | FREEZING 이후 변경 거부, retry target 고정 | 모든 race test 통과 |
| X-05 | 설정 migration 실패 | 기존 로그인/목적지 손실 | 높음 | v0.4.1 fixture migration test | copy-on-success, 구 키 유지, rollback 가능 | 반복 migration idempotent |
| X-06 | TDLib pending 상태 오판 | 조기 삭제·허위 성공 | 높음 | send update sequence tests | final success에서만 삭제 | 실패/재시작에도 파일 보존 |
| X-07 | 시스템 STT 품질/지원 편차 | 기능 무용·개인정보 오해 | 중간~높음 | A52s 20문장·비행기모드 baseline | 편집 미리보기, keyboard fallback, cloud는 별도 결정 | 측정치 공개 및 사용자 승인 |
| X-08 | MediaRecorder/STT mic 충돌 | 녹음 실패 | 높음 | audio ownership state tests | 상호 배타 lock/state, destroy/release 확인 | overlap 0회 |
| X-09 | Sharesheet/SAF를 전송 성공으로 오인 | 허위 보고·데이터 오처리 | 중간 | chooser/cancel/provider 권한 테스트 | `공유창 열림`만 보고, 직접 성공 주장 금지 | 상태 문구 검토 통과 |
| X-10 | 릴리즈 산출물 stale/mismatch | 잘못된 APK 배포 | 중간 | run start time·mtime·hash·tag 비교 | 고유 output, freshness gate | 로컬/원격/tag/asset 동일 |
| X-11 | Telegram readiness가 STT/local까지 차단 | 기능 접근 불가·잘못된 fallback | 높음 | capability 조합 unit/device tests | 중앙 Telegram tap과 STT/local action gate 분리 | 각 capability 조합이 문서대로 동작 |

## 8. `1.0.0` 진입 게이트

아래 항목이 모두 충족되어야 `1.0.0`을 제안할 수 있다.

### 기능 계약

- 1탭 음성 경로가 UI 확장 후에도 가장 빠른 기본 경로다.
- 취소·정지/전송·목적지 변경이 상태 머신으로 상호 배타적이다.
- 기본/다음 1회/이번 녹음/dispatch snapshot 수명이 사용자 표시와 일치한다.
- STT는 항상 편집 가능한 검수 단계를 거치며 자동 공유하지 않는다.
- 저장된 Primary Action과 실제 중앙 1탭 동작·표시가 항상 일치한다.

### 데이터·업데이트

- `v0.4.1 → 최신 0.x` 실제 업데이트에서 Telegram 세션·설정·기본 목적지가 보존된다.
- migration은 재실행해도 안전하고, 실패 시 이전 데이터를 파괴하지 않는다.
- pending/failed 녹음은 앱/프로세스/기기 재시작 후에도 추적 가능하다.
- release signing certificate가 `v0.4.1`과 동일하다.

### 안정성

- 실제 주사용 기기에서 7일 이상 일상 사용 관찰 또는 이에 준하는 명시적 검증 기간을 거친다.
- 음성 시작/취소/전송 각 100회, 목적지 변경 경계 50회, STT 20문장 이상 측정에서 치명적 유실·오전송 0건이다.
- 화면 회전, 화면 꺼짐/복귀, Home, 서비스 중지, 권한 회수, 네트워크 단절, 프로세스 재시작을 통과한다.
- 알려진 P0/P1 결함 0개, 데이터 유실·오전송·자격정보 노출 결함 0개다.

### 품질·릴리즈

- unit/lint/localization/release build hard gate가 모두 통과한다.
- package/version/minSdk/targetSdk/ABI/16KB 정렬/서명/secret scan이 통과한다.
- README·PRIVACY·SECURITY·릴리즈 노트가 실제 기능/네트워크/보존정책과 일치한다.
- 설치·업데이트·로그인·권한·음성·텍스트·STT·공유 플로우를 실기기에서 검증한다.

## 9. 명시적 비범위

다음은 별도 계획/승인 없이는 포함하지 않는다.

- 카카오톡 지정 채팅방 무인 자동전송
- 공개되지 않은 Kakao 내부 API 사용
- 녹음된 OGG의 사후 STT 변환
- Whisper/대형 모델 APK 내장
- 다중 Telegram 방 동시 전송
- 그룹·채널·토픽 목적지
- 전체 ADB/터미널 기능
- 앱스토어/AAB 배포

## 10. 의사결정 기록

| 날짜 | 결정 | 이유 |
|---|---|---|
| 2026-08-10 | `0.6.x` STT/공유를 `0.7.0`으로 분리 | 기능 규모와 위험이 patch 범위를 넘음 |
| 2026-08-10 | `1.0.0`은 숫자가 아니라 안정성 게이트로 결정 | 0.x 버전 수와 1.0 준비도 분리 |
| 2026-08-10 | idle 목적지는 `다음 1회` 정책 | 잘못된 방으로 연속 전송할 위험 최소화 |
| 2026-08-10 | 목적지/출력은 얕은 메뉴 + 세로 패널 | 다단 radial의 가독성·확장성 문제 방지 |
| 2026-08-10 | 시스템 STT는 별도 live dictation | OGG 파일 변환과 마이크 점유 혼동 방지 |
| 2026-08-10 | 메인 버튼 역할 전환을 당시 `v0.8.0`으로 분리 | STT/공유 기능을 먼저 안정화하고 muscle-memory/오실행 위험을 별도 검증 |
| 2026-08-11 | 실제 v0.6.0~v0.6.4가 Quiet Recorder 안정화에 사용되어 향후 번호를 재배치 | 기존 기능 설계는 유지하되 다중 목적지=`v0.7.0`, STT·공유=`v0.8.0`, 메인 역할=`v0.9.0`으로 정렬 |
| 2026-08-10 | 실기기 검증은 사용자 직접 수행 | Hermes의 폰·태블릿 조작을 금지하고 APK·체크리스트 전달과 사용자 결과 기록으로 분리 |
| 2026-08-10 13:57 KST | V5-02 focusable overlay IME 경로 FAIL | 사용자 실기기에서 창은 보이나 어느 곳을 눌러도 입력 포커스와 키보드가 열리지 않음; Activity 폴백 승인 대기 |
| 2026-08-10 | V5-02 translucent Activity 폴백 승인 | 일반 Activity 입력·IME 경로로 교체하고 overlay 입력 구현은 제품 경로에서 제거 |
| 2026-08-10 14:22 KST | V5-02 Activity 폴백 사용자 실기기 PASS | 창 열림·텍스트 입력·창 닫힘 확인; Activity 방식을 제품 경로로 채택하고 V5-03은 승인 전 대기 |
| 2026-08-12 | `v0.7.0`을 V7-00~V7-10의 11단계로 운영 | V7-09 공식 서명 RC의 실사용·복구 검증과 V7-10 기능 동결 최종 릴리즈를 분리하여 테스트한 APK와 배포 APK의 불일치를 방지 |

## 11. 단계 완료 보고 형식

각 단계 완료 시 다음만 보고한다.

- 완료한 단계
- 수정/생성한 실제 파일
- 실행한 명령과 실제 결과
- 실기기 확인 결과
- 남은 위험/미확인
- 다음 추천 단계
- 다음 단계에 필요한 사용자 승인

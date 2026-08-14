# Floating Voice v0.8.0 STT and Sharing Execution Plan

> **For Hermes:** [마스터 릴리즈 계획](floating-voice-release-master.md)과 [v0.7.0 다중 목적지 계획](floating-voice-v0.7.0-multi-destination.md)의 검증 완료를 선행조건으로 삼는다. 한 번에 이 버전만 구현하며 `v0.9.0` 기능을 선행 구현하지 않는다.

**Goal:** 기존 Telegram 음성 경로를 유지하면서 Android 시스템 STT의 live dictation을 편집 가능한 텍스트로 공유하고, OGG를 사용자 승인 폴더에 보관하거나 Android Sharesheet로 넘길 수 있게 한다.

**Architecture:** `MediaRecorder` 음성 캡처와 `SpeechRecognizer` 텍스트 캡처를 서로 배타적인 mode/state로 분리한다. overlay menu의 사용자 동작은 non-exported `SpeechReviewActivity`를 열고, 이 visible Activity가 recognizer·편집·Sharesheet를 소유한다. STT final result는 draft일 뿐이며 review와 명시적 Share 이후에만 `ACTION_SEND`를 연다. 로컬 archive와 audio share는 Telegram destination과 다른 **output route**로 모델링하고 한 capture당 한 output만 허용한다.

**Capability policy (approved 2026-08-13):** 현재 `MainActivity.startOverlay()`와 `FloatingVoiceService.onStartCommand()`의 `telegram.isReadyWithTarget()` 단일 gate를 기능별 capability로 분리한다. Telegram이 준비되지 않아도 STT/명시적 로컬 녹음·오디오 공유는 각 capability가 준비되면 사용할 수 있다. 중앙 1탭 Telegram 녹음만 verified Telegram route를 요구한다. unavailable action은 이유를 표시하며 다른 route로 자동 폴백하지 않는다.

**Tech Stack:** Android `SpeechRecognizer`/`RecognizerIntent`, API 31 on-device probe, API 33 support/model APIs, Android Sharesheet, SAF `ACTION_OPEN_DOCUMENT_TREE`, `FileProvider`, Java 17, JUnit 5, A52s 실기기 측정.

**Non-goals:** 녹음된 OGG 사후 STT, bundled Whisper, cloud STT, MediaRecorder+SpeechRecognizer 동시 실행, Kakao 지정방 자동전송, 공유 대상 앱의 최종 수신 성공 추적, 복수 output 동시 실행.

**현재 단계 상태 (2026-08-14 18:36 KST):** `V8-05 OUTPUT ROUTE DOMAIN MODEL`. V8-04 R4는 Android 13+ complete-silence segmented STT, API 29–32 bounded chained fallback, explicit Stop review, premature-end visible draft 보존을 구현해 공식 서명 APK로 전달했으며 자동 gate와 독립 후속 리뷰 PASS(Blocker 0/High 0)다. A52s 실기기 판정은 대기 중이지만 형의 “이어서 진행” 승인으로 R4 APK 바이트를 변경하지 않고 V8-05를 시작한다. STT text는 현재 검증된 Telegram 기본 목적지를 기본값으로 삼고, 다른 검증 목적지는 명시 선택하며, Android Sharesheet는 chat destination이 아닌 별도 `SYSTEM_TEXT_SHARE` action이다. 한 capture의 content/output/Telegram identity+revision을 실행 직전에 한 번만 freeze하고 callback 자동 실행·복합 output·실패 시 자동 fallback을 금지한다. 기존 legacy `sendText()` 대신 verified `DispatchTargetSnapshot` 기반 text send seam을 TDD로 연결한다. 폰/ADB 조작과 공개 릴리스는 승인 범위가 아니다.

---

## 0. 사용자에게 보이는 세 경로

```text
A. Telegram 음성
1탭 녹음 → 중앙 탭 → frozen Telegram 목적지 → final success 후 삭제

B. 말해서 텍스트 공유
시스템 STT → 편집 가능한 미리보기 → Share → Android Sharesheet

C. OGG 출력
녹음 → 출력 선택 → 사용자 승인 폴더에 저장 OR audio Sharesheet
```

`보낼 곳`은 Telegram chat을 고르고, `출력`은 Telegram/로컬 저장/시스템 공유를 고른다. 두 축을 합치지 않는다.

## 1. 완료 정의

- STT와 OGG recorder가 동시에 microphone을 소유하지 않는다.
- A52s에서 runtime support와 실제 recognizer 종류/네트워크 가능성을 측정하고 표시한다.
- STT final은 편집 가능한 review 화면을 거치며 callback에서 자동 공유하지 않는다.
- 빈 결과·오류·timeout·cancel은 retry/keyboard fallback을 제공한다.
- Sharesheet를 열었을 때만 `공유창 열림/사용자 확인 필요`로 표시하고 `전송 완료`라고 하지 않는다.
- 로컬 archive는 user-approved SAF tree에 복사·검증된 후에만 source cleanup을 결정한다.
- audio share는 scoped content URI와 임시 read grant만 사용하며 raw path를 노출하지 않는다.
- 한국어 20문장 baseline과 lifecycle/privacy/device matrix를 통과한다.

## 2. 단계 요약

| 단계 | 결과 | 주요 위험 | 완료 게이트 |
|---|---|---|---|
| V8-00 | 선행 버전 기준선 | route 회귀 | v0.7 voice/routing 재확인 |
| V8-01 | A52s STT capability spike | 지원/정확도 편차 | 20문장·비행기모드 측정 |
| V8-02 | STT state/ownership TDD | mic overlap/stale callback | illegal/race tests 통과 |
| V8-03 | system recognizer controller | lifecycle/thread/API 차이 | destroy/error/support matrix |
| V8-04 | review/edit/share text | 무검수 공유/개인정보 | explicit Share gate |
| V8-05 | output-route model | chat/output 혼동 | one-output invariant |
| V8-06 | SAF local archive | partial copy/data loss | verify-before-cleanup |
| V8-07 | audio Sharesheet | URI/retention/허위 성공 | grant·retention·wording gate |
| V8-08 | integration/privacy/accessibility | window leak/텍스트 노출 | full device matrix |
| V8-09 | 공식 서명 RC·실사용 검증 | policy/docs/stale artifact | update+artifact+device gate |
| V8-10 | 기능 동결 최종 릴리스 | 테스트 APK와 배포 APK 불일치 | accepted bytes+tag+hosted release gate |

---

## 3. V8-00 — v0.7.0 기준선 재확인

**수정 파일:** 없음.

**필수 시나리오:**

- 기본/다음 1회/이번 녹음 destination
- freeze 이후 target 변경 거부
- final success 후 delete, failure/retry 후 보존
- process restart 후 original dispatch snapshot
- overlay menu/composer cleanup

**Blocking product gate — Telegram 독립 capability:** 다음 정책을 구현 전에 승인한다.

- 중앙 1탭: verified Telegram route가 있을 때만 기존 fast voice 시작
- `말해서 텍스트 공유`: system recognizer가 가능하면 Telegram 상태와 무관하게 사용
- `로컬 저장 녹음`/`audio share 녹음`: 사용자가 메뉴에서 output을 명시한 경우에만 Telegram 상태와 무관하게 시작
- 사용할 수 없는 action은 숨기지 말고 이유를 표시하며 다른 route로 자동 폴백하지 않음

권장안은 위와 같이 capability를 분리하는 것이다. Telegram 준비를 모든 기능의 필수조건으로 유지하기로 결정한다면 STT/로컬 기능도 사용할 수 없다는 제한을 README와 UI에 명시해야 한다.

**위험:** output route 추가가 Telegram destination state와 결합되면 wrong-room/잘못된 cleanup이 생긴다.

**완화:** v0.7 route tests를 변경 전과 변경 후 동일하게 실행하고, output route는 별도 타입으로 추가한다.

**게이트:** v0.7.0 핵심 tests/device flows PASS.

---

## 4. V8-01 — A52s 시스템 STT 선행 spike

**목적:** 기능을 설계만으로 확정하지 않고 실제 폰의 recognizer/한국어/네트워크 동작을 먼저 측정한다.

**spike 범위:** production menu에 연결하지 않은 visible debug screen 또는 별도 development branch artifact. 외부 전송 없음.

**확인 항목:**

- `SpeechRecognizer.isRecognitionAvailable()`
- API 31+ `isOnDeviceRecognitionAvailable()`
- 일반 recognizer와 on-device recognizer 생성 성공/오류
- API 33+ `checkRecognitionSupport()` 결과
- 모델 download 필요 여부와 사용자 UI 요구 여부
- `ko-KR`, partial/final callbacks, silence timeout
- airplane mode 전/후 동작
- cancel/destroy 후 stale callback

**고정 20문장 corpus:**

1. 오늘 회의는 오후 세 시에 시작합니다.
2. 집에 도착하면 전화해 주세요.
3. 우유와 달걀을 사야 합니다.
4. 내일 서울은 비가 올 수도 있습니다.
5. 아이 약은 저녁 식사 후에 먹입니다.
6. 주차한 위치를 잊지 않도록 메모해 줘.
7. 카카오톡으로 일정 링크를 공유해 주세요.
8. Floating Voice 버전 영 점 칠 점 영을 테스트합니다.
9. API 응답 시간이 이 초를 넘었습니다.
10. GitHub Release에서 APK를 내려받았습니다.
11. SpeechRecognizer가 한국어를 제대로 인식하는지 확인합니다.
12. MediaRecorder와 동시에 마이크를 사용하면 안 됩니다.
13. OGG Opus 파일을 텔레그램 음성으로 보냅니다.
14. OLED 시뮬레이션 결과를 다시 확인해 주세요.
15. TDLib temporary message ID를 기록합니다.
16. 텔레그램 개인 비서 봇으로 음성을 보냅니다.
17. 세탁기가 끝나면 빨래를 건조대로 옮깁니다.
18. 어린이집 준비물은 가방 앞주머니에 넣었습니다.
19. 거실에서 텔레비전 소리가 나는 동안 받아쓰기를 시험합니다.
20. 내용을 확인하고 공유 버튼을 눌러 주세요.

1–8은 일상 한국어, 9–16은 한국어·영어/기술용어, 17–20은 생활·육아 및 중간 생활소음 조건으로 반복한다. 문장·음성·결과에는 실제 개인정보를 넣지 않는다.

**기록:**

- recognizer/support 정보
- final result 성공 수
- 발화 종료부터 final까지 latency
- correction character/word 수
- timeout/error code
- airplane-mode 결과

**기본 go/no-go 기준:**

- crash/hang 0
- ordinary Korean final result 90% 이상
- ordinary Korean median correction burden 15% 이하
- ordinary Korean median final latency 3초 이하
- preview/retry/keyboard fallback을 방해하는 lifecycle 결함 0

기술용어·소음 항목은 별도 관찰값으로 기록한다. 기준 미달이면 시스템 STT를 자동 경로로 강행하지 않고 keyboard-only fallback 또는 `실험 기능` 유지 여부를 사용자에게 결정받는다.

**위험:** on-device API가 있다고 실제 한국어 model이 준비된 것은 아니며 `EXTRA_PREFER_OFFLINE`도 강제가 아니다.

**완화:** API level/flag가 아니라 airplane-mode 포함 실제 측정만 보고한다. 모델 다운로드는 visible user-approved flow에서만 수행한다.

**게이트:** 측정표와 go/no-go 결정 승인.

**2026-08-14 A52s 사용자 결정:** `GO/PASS`.

- R2 APK: `FloatingVoice-v8-01-stt-spike-r2-debug.apk`, SHA-256 `33a87ec0eadb4b69c23fe24fabd7ac02316f0b897e871f4bde12a9f1e0256002`.
- 온라인 캡처: corpus 화면 `20/20`; 화면에 직접 표시된 STANDARD 요약은 `attempts=2`, `success=2`, `successRate=100.0%`, `medianLatency=87ms`, `medianCorrection=7.8%`.
- 비행기모드 캡처: `기기 내 인식`, `비행기모드`, 문장 5에서 recognizer 결과 `아이야금 저녁 식사 후에 먹입니다`; 기준문장의 `아이 약은` 부분과 오인이 있었지만 형은 전체 인식 품질을 수용했다.
- 화면의 `수정 문자 거리 0`/`수정 부담률 0.0%`는 사용자가 수정 입력을 바꾸지 않은 상태의 표시이므로 기준문장과 완전 일치했다는 증거로 사용하지 않는다.
- 전체 20개 attempt ledger, crash/hang, 취소·회전·Home 복귀 항목은 제공된 캡처만으로 확인되지 않았으므로 별도 PASS 수치로 기록하지 않는다.
- 결정 범위: 시스템 STT를 v0.8.0 제품 구현 후보로 채택하고 V8-02로 진행 가능. 제품 메뉴 반영, Release APK, tag/GitHub 공개는 미승인.

---

## 5. V8-02 — speech state·audio ownership TDD

**파일:**

- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/SpeechShareStateMachine.java`
- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/SpeechShareEvent.java`
- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/AudioCaptureOwnership.java`
- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/CaptureCapabilities.java`
- Create: corresponding `core/src/test/...` tests
- Create: `app/src/main/java/com/sidequestlab/floatingvoice/AudioCaptureCoordinator.java`
- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/FloatingVoiceApp.java`
- Extend: v0.5–v0.7 overlay/output state tests

**상태:**

```text
IDLE
STT_CHECKING_SUPPORT
STT_LISTENING
STT_PROCESSING
STT_REVIEW
SHARE_CHOOSER_LAUNCHED
STT_FAILED
STT_CANCELED
TEARING_DOWN
```

**Tests first:**

- recording active에서 STT start 거부
- STT active에서 recording start 거부
- partial result는 draft preview effect만, share effect 0
- final result는 review로만 이동
- blank final은 failed/retry
- review 전 Share 거부
- explicit Share + reviewed nonblank text만 chooser effect
- cancel/result/error race에서 첫 terminal event만 수락
- destroy generation 이전 stale callback 무시
- service teardown에서 ownership release
- retry는 새 generation만 사용
- Telegram unavailable + STT available → overlay/STT action 허용, 중앙 Telegram tap 거부
- Telegram unavailable + local output selected → 해당 local capture만 허용
- capability unavailable action이 다른 route로 자동 폴백하지 않음

**위험:** overlay state, route state, speech state를 하나의 거대 enum으로 합치면 illegal 조합이 늘어난다.

**완화:** speech sub-state와 audio ownership을 분리하되 상위 coordinator가 둘의 invariant를 검사한다.

**게이트:** illegal/race/ownership tests 전부 통과, overlap effect 0.

**완료 증빙 (2026-08-14 10:23 KST):**

- `SpeechShareStateMachine`/`SpeechShareEvent`: partial은 preview만, final은 review만, explicit share만 chooser effect, first-terminal 및 stale-generation 차단, 완료 후 IDLE 복귀와 generation 단조 증가.
- `AudioCaptureOwnership`/`AudioCaptureCoordinator`: recording/STT 동시 점유 거부, 실제 recorder/recognizer 해제 확인 전 lease 유지, stale·중복 release 거부, 연속 STT interaction 지원.
- `FloatingVoiceService`: start/stop/cancel/service teardown의 `MediaRecorder.release()` 실패를 fail-closed로 처리해 점유권과 보존 파일을 유지.
- V8-01 debug STT harness도 process-wide coordinator를 사용하며 `SpeechRecognizer.destroy()` 성공 뒤에만 STT lease를 해제.
- `CaptureCapabilities`: Telegram/STT/local 독립 판정과 unavailable action의 no-fallback 계약을 pure test로 고정.
- 최종 자동검증: clean 211 tasks, core 178 + app debug 74 tests/fail 0, app release variant 70 tests/fail 0, Debug/Release Lint 오류 0, localization 357 keys/hard failure 0, debug/release assembly PASS.
- 패키징 검증: debug APK ZIP/ELF 16KB PASS, v2 서명 PASS. 이 APK는 내부 자동검증 산출물이며 사용자 배포·Release 승인이 아니다.
- 독립 follow-up review: Blocker 0 / High 0 PASS.

---

## 6. V8-03 — SystemSpeechRecognizerController

**파일:**

- Create: `app/src/main/java/com/sidequestlab/floatingvoice/SystemSpeechRecognizerController.java`
- Create: `app/src/main/java/com/sidequestlab/floatingvoice/SpeechRecognitionSupport.java`
- Create: `app/src/main/java/com/sidequestlab/floatingvoice/SpeechReviewActivity.java`
- Create: `app/src/main/res/layout/activity_speech_review.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/FloatingVoiceService.java`
- Modify: bilingual strings

**manifest:** Android 11+ recognition service visibility를 위해 필요한 `<queries>`/`android.speech.RecognitionService` declaration을 공식 API 기준으로 추가한다.

`SpeechReviewActivity`는 `android:exported="false"`이며 overlay menu의 명시적 user gesture로만 연다. launch 전 service가 recorder 비활성을 확인하고 process-wide `AudioCaptureCoordinator` ownership을 획득한다.

**controller 계약:**

- main application thread에서 create/start/stop/cancel/destroy
- API 29–30은 `SpeechRecognizer.isRecognitionAvailable()` 후 일반 system recognizer만 사용
- API 31+에서 runtime on-device availability 확인 후에만 on-device recognizer 생성
- unsupported/creation exception은 일반 system recognizer 또는 keyboard fallback으로 명시
- API 33+ `checkRecognitionSupport(ko-KR)` 결과를 확인하고, model download는 Activity의 별도 사용자 버튼과 승인으로만 `triggerModelDownload()` 호출
- `ko-KR`, partial results, calling package 등 최소 intent extras
- `EXTRA_PREFER_OFFLINE`을 offline 보장으로 표현하지 않음
- finish/cancel/error/teardown에서 exactly-once destroy
- listener callback에 generation token 부여

**선행 테스트:**

- API 29/30 branch: on-device API 호출 없음
- API 31/32 branch
- API 33+ support/model branch
- recognizer null/unavailable/busy/network/no-match/timeout errors
- service stop/permission revoke/screenoff while listening

**위험:** `SpeechRecognizer`는 continuous recognition용이 아니며 implementation이 remote server로 audio를 보낼 수 있다.

**완화:** 짧은 user-initiated dictation만 허용, privacy copy 표시, 자동 restart loop 금지, on-device 여부는 실제 support 결과로 표시.

**게이트:** branch/lifecycle matrix와 실제 A52s repeat 20회에서 crash/leak 0.

**자동검증 완료 증빙 (2026-08-14 13:00 KST):**

- API 29/30 standard-only, API 31+ on-device availability와 explicit standard fallback, API 33+ support check 및 model auto-download 0을 public-seam 테스트로 고정했다.
- generation-scoped completion, stale callback 차단, exactly-once destroy, destroy 실패 fail-closed, recorder/STT 상호 배타 ownership을 검증했다.
- 1차 독립 리뷰 High 3건을 각각 RED로 재현해 수정했고 후속 bounded review는 `PASS / Blocker 0 / High 0`이었다.
- 수정 후 clean build `211` tasks, core `178` + app debug `95` + app release `91` tests/fail 0, Debug/Release Lint 오류 0, localization `364` keys, debug/release assembly, ZIP/ELF 16KB, debug APK v2 서명 PASS.
- 실제 A52s repeat 20회는 사용자 승인대로 V8-03~04 중간 APK 게이트에서 형이 직접 수행한다.

---

## 7. V8-04 — STT review·편집·text Sharesheet

**파일:**

- Create: `app/src/main/java/com/sidequestlab/floatingvoice/AndroidShareController.java`
- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/SpeechReviewActivity.java`
- Modify: `app/src/main/res/layout/activity_speech_review.xml`
- Modify: `FloatingActionMenuController.java`, `FloatingVoiceService.java`
- Modify: bilingual strings/drawables

**flow:**

```text
`말해서 텍스트 공유` 선택
→ visible SpeechReviewActivity
→ support check/listen
→ partial ephemeral display
→ final draft
→ editable review
→ Share button
→ ACTION_SEND type=text/plain + EXTRA_TEXT
→ Intent.createChooser
```

**규칙:**

- final callback에서 chooser를 직접 열지 않음
- preview에서 retry/keyboard edit/cancel 가능
- blank Share disabled
- transcript를 preferences/logs/notification/analytics에 쓰지 않음
- chooser result를 `sent`로 보고하지 않음
- receiving app/room 선택은 사용자 책임

**Tests:**

- recognition error 뒤 keyboard fallback에 visible draft 유지
- cancel은 chooser 0
- duplicate Share tap chooser 1
- long/Unicode/Korean text plain share
- chooser 없는 환경 fallback
- Back/Home/locale/rotation에서 privacy와 cleanup

**위험:** overlay에서 Activity launch가 제한되거나, Activity 전환 중 recorder/STT ownership이 경쟁할 수 있다.

**완화:** user gesture 직후 non-exported review Activity를 열고 그 Activity 안에서 `Intent.createChooser()`를 호출한다. launch 실패 시 STT를 시작하지 않고 keyboard/share 진입도 차단한다. service와 Activity는 process-wide ownership token으로 상호 배제한다.

**게이트:** 모든 share가 visible review+explicit tap을 거치고 상태 문구가 `공유창 열림/사용자 확인 필요`로 제한됨.

---

## 8. V8-05 — voice/text 통합 output route domain model

**파일:**

- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/OutputRoute.java`
- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/OutputRouteStateMachine.java`
- Create: corresponding tests
- Extend: `DispatchTargetSnapshot` only for Telegram route; local/share는 별도 output snapshot
- Extend: STT review UI with current Telegram destination, destination picker, `Telegram으로 보내기`, `Android 공유…`

**route:**

- `TELEGRAM_VOICE`
- `TELEGRAM_TEXT`
- `LOCAL_AUDIO_ARCHIVE`
- `SYSTEM_AUDIO_SHARE`
- `SYSTEM_TEXT_SHARE`

**STT 텍스트 기본 계약:**

- review 화면의 기본 output은 현재 검증된 Telegram 기본 목적지다.
- 사용자는 전송 전에 기존 목적지 선택과 같은 흐름으로 다른 검증된 Telegram 목적지를 고를 수 있다.
- Android 공유는 Telegram destination이 아니라 별도의 `SYSTEM_TEXT_SHARE` output action이다.
- 전송/공유 시작 시 content와 output, Telegram이면 destination identity/revision을 함께 freeze한다.
- final/partial callback은 어떤 output도 자동 실행하지 않으며, 명시적 `보내기` 또는 `공유…`만 외부 동작을 시작한다.
- 실패 시 Telegram↔Sharesheet 또는 다른 Telegram 목적지로 자동 fallback하지 않는다.

**Tests first:**

- 한 capture에 output 하나만 freeze
- Telegram voice/text route만 destination required
- STT text 기본값이 현재 Telegram 목적지이며 명시 변경 전에는 그대로 유지
- STT text의 목적지 변경 후 send가 변경된 destination snapshot만 사용
- `SYSTEM_TEXT_SHARE`는 chat destination을 요구하거나 저장하지 않음
- local/share를 fake chat destination으로 저장하지 않음
- freeze 이후 output 변경 거부
- retry/cleanup가 route별 정책 사용
- output failure에서 Telegram으로 자동 fallback 0

**위험:** `Save + Telegram` 같은 복합 route가 failure semantics를 폭발시킨다.

**완화:** v0.8.0에서는 single output only. 복합 route는 별도 버전/상태 모델 없이는 금지.

**게이트:** route invariants tests 통과.

**구현 현황 (2026-08-14 21:07 KST):** `OutputRoute`/`OutputSnapshot`/`OutputRouteStateMachine`과 snapshot 기반 `VerifiedTextDispatch`를 추가했다. Speech review는 검증된 기본 Telegram 대상, 다른 검증 대상 선택, 별도 Android 공유를 제공한다. Telegram 미설정 상태에서도 별도 Home action으로 STT·Android 공유에 진입할 수 있고 Telegram 전송 capability는 계속 fail-closed다. Telegram 전송은 speech 전용 queue/pending 상태와 memory-only handoff/attempt ID를 사용하며 delivered 전에는 draft를 닫지 않고 reject 시 같은 review로 복귀한다. 회전 중 destination/handoff/result와 거절 feedback도 memory-only ViewModel/registry로 유지하며 stale ordered receipt는 현재 attempt의 feedback/UI를 바꾸지 않는다. 최종 clean gate는 core 195/debug 149/release 145 tests(실패·오류·skip 0), localization 393/393, Lint Fatal/Error 0, Debug/Release assembly PASS다. 독립 follow-up review는 Blocker/High/Medium/Low 0으로 PASS했다.

---

## 9. V8-06 — SAF 로컬 OGG archive

**파일:**

- Create: `app/src/main/java/com/sidequestlab/floatingvoice/LocalArchiveController.java`
- Create: `app/src/main/java/com/sidequestlab/floatingvoice/ArchiveSettingsStore.java`
- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/MainActivity.java`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `FloatingVoiceService.java`
- Modify: bilingual strings

**설정:**

- visible main Activity에서 `ACTION_OPEN_DOCUMENT_TREE`
- 사용자가 고른 URI에 `takePersistableUriPermission`
- selected folder label과 permission 상태 표시
- permission 상실 시 재선택 요구; 임의 private folder fallback 금지

**copy transaction:**

1. source OGG existence/size 확인
2. target document 생성
3. stream copy
4. output close
5. target reopen/byte count 또는 SHA-256 검증
6. 성공 상태 기록
7. 원본 cleanup 정책 수행

**기본 cleanup:** archive copy와 검증이 성공하면 app temporary source를 삭제한다. 실패/불확실이면 source 보존하고 target partial document를 표시/정리 시도한다.

**Tests:**

- permission grant/revoke/reboot persistence
- duplicate filename
- folder full/USB disconnect/stream exception
- 0-byte/short copy/hash mismatch
- Unicode filename
- source delete failure
- user cancel folder picker

**위험:** 외장 문서 provider마다 atomic rename/size/reporting 동작이 다르다.

**완화:** source는 verification 전 삭제하지 않으며, provider가 hash/read-back을 허용하지 않으면 `verified`가 아니라 `copied-unverified`로 보존한다.

**게이트:** A52s의 실제 선택 폴더에서 10개 OGG save/reopen/play, 실패 injection에서 source loss 0.

---

## 10. V8-07 — audio Sharesheet와 FileProvider

**파일:**

- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/file_paths.xml`
- Extend: `AndroidShareController.java`
- Create/modify: main UI의 retained share files 관리 화면 또는 최소 delete action
- Modify: bilingual strings/privacy copy

**flow:**

```text
completed OGG
→ FileProvider content:// URI
→ ACTION_SEND type=audio/ogg
→ FLAG_GRANT_READ_URI_PERMISSION
→ system chooser
→ user selects receiving app
```

**상태/보존:**

- chooser launch는 final delivery evidence가 아니다.
- receiving app의 async read가 끝나기 전에 file을 삭제하지 않는다.
- v0.7 기본은 share source를 retained 상태로 두고 main UI에서 명시적으로 삭제할 수 있게 한다.
- 자동 cleanup 기간을 도입하려면 별도 사용자 결정과 active-share 안전 검증이 필요하다.

**Tests:**

- raw `file://` path 노출 0
- URI grant 없을 때 실패를 재현한 뒤 grant 포함 성공
- chooser cancel/없는 앱
- KakaoTalk/메모앱은 지원 MIME/attachment 수신 여부만 실기기 관찰
- recipient가 읽기 전 source 삭제하지 않음
- app restart 후 retained file 표시/삭제

**위험:** 공유창을 닫았는지/상대 앱이 실제 저장·전송했는지 일반 ACTION_SEND로 신뢰성 있게 알 수 없다.

**완화:** UI/report에서 `공유창을 열었습니다`만 사용. 전송 완료/카카오 특정방 전달을 주장하지 않는다.

**게이트:** scoped URI·readability·retention·manual delete·wording 모두 검증.

---

## 11. V8-08 — 통합·privacy·접근성 hardening

**자동화 파일 계획:**

- Create: `app/src/androidTest/java/com/sidequestlab/floatingvoice/SpeechReviewActivityTest.java`
- Create: `app/src/androidTest/java/com/sidequestlab/floatingvoice/AudioOwnershipInstrumentedTest.java`
- Create: `app/src/androidTest/java/com/sidequestlab/floatingvoice/LocalArchiveInstrumentedTest.java`
- Create: `scripts/verify-v080-stt-device-matrix.sh`
- Modify: `app/build.gradle`/test runner는 AndroidX Test 도입 승인 후 별도 infrastructure commit으로 적용

**정적:**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :core:test
python3 ~/.hermes/skills/software-development/android-native-app-delivery/scripts/verify_android_localization.py .
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew test lintDebug lintRelease
```

**실기기 matrix:**

- voice/STT/output route switching
- recording 중 STT start, STT 중 recording start 차단
- permission revoke: mic/overlay/notification/SAF
- screen off/Home/rotation/locale/font/TalkBack
- recognizer error/no-match/network/timeout/cancel
- chooser text/audio cancel/return
- service/process restart
- local folder unavailable/full
- Telegram pending 중 다른 STT/share 사용

**privacy scan:**

- logcat, notifications, SharedPreferences, saved instance state, crash text에 transcript 없음
- intent에는 explicit Share 시점의 reviewed text만 포함
- content URI scope가 share directory 밖을 노출하지 않음
- APK/source에 API key/cloud endpoint 추가 없음

**위험:** overlay·recognizer·chooser·SAF 네 개 lifecycle이 겹쳐 teardown 복잡도가 높다.

**완화:** 중앙 coordinator의 ordered cleanup: recognizer cancel/destroy → IME dismiss → child windows remove → URI/output state finalize → listener detach. generation token으로 stale callback 차단.

**게이트:** crash/leak/data loss/automatic external action 0.

---

## 12. V8-09 — release candidate

**버전/문서:**

- Modify: `app/build.gradle`
- Modify: `README.md`, `README.en.md`, `PRIVACY.md`, `SECURITY.md`
- Create: `docs/releases/v0.8.0.md`

**privacy 문서 필수 내용:**

- system recognizer가 network-backed일 수 있음
- on-device/offline 표시는 runtime 측정에 근거
- transcript 기본 비영속
- Sharesheet는 사용자가 외부 앱을 선택하는 handoff
- local/share OGG retention·삭제 방법

**final device evidence:**

- A52s 20문장 결과 첨부
- voice/STT mic overlap 0
- text review/share 10회
- local archive 10회
- audio chooser 5회와 retained delete
- `v0.7.0 → v0.8.0` 업데이트에서 session/catalog/pending 보존

**artifact:** master의 clean build/signature/16KB/metadata/hash/secret/freshness gate.

**위험:** 한 번의 성공으로 STT 정확도·offline 여부를 과장하거나, 실제 retention/privacy 동작과 문서가 어긋난 채 배포될 수 있다.

**완화:** 고정 20문장·airplane-mode·SAF/audio-share retention 결과를 release evidence에 포함하고 README/PRIVACY 문구를 그 측정치와 대조한다. 기준 미달이면 STT availability를 끄거나 RC를 중단한다.

**롤백:**

- STT runtime 문제 시 feature availability를 off하고 keyboard/share는 유지 가능
- local/share route 문제 시 Telegram default route를 보존하되 실패 capture를 자동 Telegram 전송하지 않음
- schema 변경은 이전 route/catalog를 파괴하지 않도록 additive로 설계

**RC 게이트:** STT go/no-go 기준 + archive no-loss + share wording + update + artifact + A52s RC 승인. RC 이후 소스·리소스·manifest·빌드 설정·APK byte가 바뀌면 기존 RC를 폐기하고 `versionCode`를 올린 RC2/RC3로 전체 회귀와 실기기 검증을 반복한다.

---

## 13. V8-10 — 기능 동결 최종 릴리스

**선행조건:** 마지막 공식 서명 RC가 전체 A52s matrix를 통과하고 형의 명시적 PASS를 받았으며, 열려 있는 P0/P1·데이터 유실·오전송·자격정보 노출 결함이 0개다.

**수행 범위:**

1. 승인된 RC source commit·worktree·artifact provenance를 다시 확인한다.
2. 기능 추가 없이 unit/lint/localization/release artifact gate를 재확인한다.
3. 기본 전략은 A52s가 승인한 RC bytes를 그대로 최종 APK로 승격하는 것이다.
4. 어떤 이유로든 최종 APK를 재빌드하면 RC와 동등한 source만으로는 부족하며, 새 bytes를 A52s에서 다시 검증받는다.
5. 별도 공개 승인 후에만 annotated tag와 GitHub Release를 생성한다.
6. 공개 asset을 비로그인 상태로 재다운로드해 로컬 승인본과 byte-for-byte, SHA-256, 크기, 서명, package/version/ABI, ZIP/ELF 16KB 일치를 검증한다.

**최종 게이트:** accepted artifact parity + 사용자 공개 승인 + immutable tag + hosted asset 재검증. 하나라도 미충족이면 릴리스 완료로 기록하지 않는다.

---

## 14. 증거 구조

```text
.hermes/evidence/v0.8.0/
  stt-capability-a52s.md
  stt-20-phrase-results.csv
  speech-state-tests.md
  mic-ownership-matrix.md
  text-share-matrix.md
  saf-archive-failure-matrix.md
  audio-share-uri-retention.md
  privacy-scan.md
  rc-artifact-provenance.md
  final-artifact-provenance.md
```

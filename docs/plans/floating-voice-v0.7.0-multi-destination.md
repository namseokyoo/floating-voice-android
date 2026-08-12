# Floating Voice v0.7.0 Multi-Destination Execution Plan

> **For Hermes:** [마스터 릴리즈 계획](floating-voice-release-master.md)과 승인된 `v0.6.4` 기준선을 선행조건으로 삼는다. 한 번에 이 버전만 구현하며 `v0.8.0` 기능을 선행 구현하지 않는다.

**Goal:** 하나의 로그인된 Telegram 사용자 계정에서 여러 개의 **검증된 private bot** 목적지를 관리하고, 기본·다음 1회·이번 녹음·불변 dispatch snapshot을 명확히 분리한다.

**Architecture:** 현재 `AppConfig.botUsername`, 단일 `TargetChat`, temp-message-to-path store를 account configuration, destination catalog, route scope, persistent dispatch record로 분해한다. 순수 모델·migration·race를 `core`에서 TDD하고, TDLib와 Android storage/UI는 그 계약을 실행한다.

**Tech Stack:** Java 17, Android SDK 29–36, TDLib, Android Keystore-backed settings, private SharedPreferences dispatch metadata, AppCompat XML, JUnit 5.

**현재 단계 상태 (2026-08-12 06:00 KST):** `V7-05-CODE-PASS / NEXT=V7-06`. `DestinationResolver`와 lookup adapter, account/client/request generation 검증, duplicate·identity-change·disabled-state·persist-zero/send-zero 계약을 구현했다. `PendingDispatch`/`DispatchState`/`PendingDispatchCodec`/`PendingDispatchStore`를 추가해 불변 dispatch snapshot과 durable FAILED/UNKNOWN retention을 구현했고, live TDLib spike는 이월되어 자동 재시도는 허용하지 않는다. 전체 회귀 PASS. 2026-08-12 사용자 결정에 따라 실제 private bot 2개 A52s 검증은 V7-06 정식 목적지 UI 뒤로 이월한다.

**Non-goals:** 그룹·채널·토픽, 여러 방 동시 전송, 목적지 자동 추천, 다른 Telegram 계정 다중 로그인, 목적지 무효 시 자동 폴백, STT/로컬 archive/Android share.

---

## 0. 핵심 수명 계약

```text
Default destination
  persistent, main app에서만 변경, 미래 녹음에 적용

Next one-shot
  idle overlay에서 선택, 다음 녹음 시작 시 소비, 취소 후 부활하지 않음

Recording destination
  녹음 시작 시 default/next-one에서 복사
  녹음 중 변경하면 현재 녹음에만 적용

Dispatch snapshot
  stop/send 시작 시 account + destination + chatId + peer identity를 불변 고정
  retry와 process recovery도 원래 snapshot 사용
```

### 절대 금지

- next-one을 persistent default로 조용히 승격
- 무효 목적지를 다른 목적지로 자동 대체
- send freeze 후 현재 message의 목적지 변경
- username/title만으로 identity 결정
- 목적지 삭제와 pending dispatch 삭제를 결합
- verify/add 과정에서 test message 자동 전송

## 1. 완료 정의

- 기존 `v0.6.4` 단일 목적지가 첫 default candidate로 손실 없이 이전되며, 새 canonical bot user ID를 확인하기 전에는 `NEEDS_REVERIFY`로 차단된다.
- 여러 private bot을 stable `chatId + bot userId + account userId`로 검증·저장한다.
- 중복/username identity change/account mismatch를 차단한다.
- idle 선택은 `다음 1회`, recording 선택은 `이번 녹음`으로 표시되고 수명이 정확하다.
- stop/send에서 immutable dispatch snapshot이 먼저 저장된 뒤 TDLib를 호출한다.
- pending/failure/retry/process restart가 원래 destination을 유지한다.
- 삭제된 catalog destination의 pending record도 유지된다.
- 실제 A52s에서 잘못된 방 전송 0건으로 race matrix를 통과한다.

## 2. 단계 요약

| 단계 | 결과 | 가장 큰 위험 | 완료 게이트 |
|---|---|---|---|
| V7-00 | v0.6.4 기준선 | 선행 회귀 | v0.6.4 gate 재확인 |
| V7-01 | storage migration spike | 기존 설정/목적지 손실 | fixture migration idempotent |
| V7-02 | destination/route domain TDD | 수명 혼합 | pure transition tests 통과 |
| V7-03 | catalog persistence | partial write/중복 | atomic schema + recovery test |
| V7-04 | multi-bot resolution | identity 바꿔치기 | chatId/userId/account 검증 |
| V7-05 | dispatch snapshot/retry | wrong-room race | freeze-before-send + restart test |
| V7-06 | main destination UI | 오삭제/기본 혼동 | labeled confirmation + pending 보존 |
| V7-07 | overlay picker | next-one/session 혼동 | scope label/consume tests |
| V7-08 | lifecycle/race hardening | process death/TDLib update | device race matrix 통과 |
| V7-09 | 공식 서명 RC·실사용 검증 | migration/서명/stale APK/복구 실패 | update install + 집중 device matrix |
| V7-10 | 기능 동결 최종 릴리즈 | 테스트 APK와 배포 APK 불일치 | clean build + 최종 A52s PASS + tag/release |

---

## 3. V7-00 — 선행 버전 재확인

**수정 파일:** 없음.

**필수 증거:**

- `v0.6.4` voice success/failure/cancel
- state/gesture tests
- overlay cleanup/keyboard/locale
- release certificate와 installed app update 가능 상태

**위험:** 다중 목적지 변경 중 `v0.6.4` 회귀가 섞이면 원인 분리가 어렵다.

**완화:** `v0.6.4` 태그 또는 승인된 commit을 기준점으로 고정하고, UI와 storage migration을 같은 단계에서 시작하지 않는다.

**게이트:** v0.6.4 완료 증거가 없으면 중단.

---

## 4. V7-01 — account/destination storage migration spike

**목적:** 현재 암호화 저장소의 `bot_username`, `target_chat_id/title/username`을 잃지 않고 새 구조로 옮긴다.

**현재 입력:**

- `core/.../AppConfig.java`가 account credential과 bot username을 함께 보유
- `SecureSettingsStore`가 account/target을 같은 encrypted prefs에 저장
- `TargetChat`은 `chatId/title/username`만 보유

**계획 파일:**

- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/TelegramAccountConfig.java`
- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/LegacyConfigSnapshot.java`
- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/DestinationMigration.java`
- Create: corresponding `core/src/test/...` tests
- Later modify: `AppConfig.java`, `SecureSettingsStore.java`

**Tests first:**

1. `v0.4.1–v0.6.4` account+target fixture → account config + one `NEEDS_REVERIFY` default candidate
2. config 있고 target 없음 → account만 보존, catalog empty
3. target 일부 field 손상 → 기존 account 보존, target migration 실패를 명시
4. migration 재실행 → destination 중복 0
5. migration write 중 실패 → legacy keys 삭제/변형 0
6. 성공 검증 후에만 schema-version marker 기록
7. 이미 pending voice path가 있어도 삭제/변경하지 않음

legacy `TargetChat`에는 bot canonical user ID와 owner account ID가 없으므로 migration만으로 `VERIFIED`를 만들지 않는다. 로그인 READY 후 username을 다시 resolve하고, 기존 `chatId`와 새 canonical identity의 관계를 사용자에게 보여준 뒤 명시적으로 재검증한다. 재검증 과정은 메시지를 보내지 않는다.

**저장 전략:**

- `schema_version`을 별도 관리
- 새 값 전체 write·read-back 검증 후 migration committed 표시
- 최소 한 안정 버전 동안 legacy keys를 rollback/read fallback용으로 유지
- Keystore alias/TDLib database key를 바꾸지 않음

**위험:** `SharedPreferences.apply()`의 비동기 write 사이에서 partial migration이 발생할 수 있다.

**완화:** migration commit에는 `commit()` 또는 단일 transaction 가능한 serialization을 사용하고 read-back 검증. production format 선택 전에 corrupt/truncated fixture test.

**게이트:** migration tests와 실제 `v0.6.4 → dev` update 설치에서 login/session/default target 보존.

---

## 5. V7-02 — destination·route domain model TDD

**파일:**

- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/Destination.java`
- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/DestinationCatalog.java`
- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/DestinationScope.java`
- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/RouteStateMachine.java`
- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/DispatchTargetSnapshot.java`
- Create: corresponding `core/src/test/...` files

**Destination 최소 필드:**

- local immutable ID
- authenticated account user ID
- `chatId`
- bot `userId`/canonical peer identity
- configured username + resolved username/title
- user alias
- verification status/revision/verified timestamp
- enabled flag

**Verification status:** `NEEDS_REVERIFY`, `VERIFYING`, `VERIFIED`, `INVALID`, `DISABLED`. `VERIFIED`만 새 capture에 선택할 수 있다.

**Route tests first:**

- default는 future recording에만 적용
- idle next-one은 recording start 때 정확히 한 번 소비
- next-one recording cancel 후 부활하지 않음
- recording-time selection은 current recording에만 적용
- default 변경 중 recording target 불변
- FREEZING 이후 target 변경 거부
- dispatch snapshot 생성 후 원본 catalog mutation 영향 0
- invalid/deleted/disabled/account-mismatch target은 block, fallback 0
- duplicate local ID/chatId/canonical peer 차단

**위험:** 모델이 UI 편의 상태와 delivery truth를 섞을 수 있다.

**완화:** `DestinationCatalog`, `RouteStateMachine`, `DispatchTargetSnapshot`을 별도 타입으로 유지하고 mutable `currentTarget` 하나로 합치지 않는다.

**게이트:** core legal/illegal/race tests 전부 통과.

---

## 6. V7-03 — destination catalog persistence

**파일:**

- Create: `app/src/main/java/com/sidequestlab/floatingvoice/DestinationStore.java`
- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/SecureSettingsStore.java`
- Deprecate/replace: `app/src/main/java/com/sidequestlab/floatingvoice/TargetChat.java`
- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/FloatingVoiceApp.java`
- Add serialization/migration tests through pure codec or Android test seam

**구현 순서:**

1. versioned catalog codec with corrupt/unknown field behavior test
2. account config와 destinations 분리
3. default destination ID 별도 저장
4. catalog update는 copy → validate → atomic persist → publish
5. failed load는 빈 catalog로 조용히 덮어쓰지 않고 recovery 상태 표시
6. legacy target read fallback 유지

**위험:** alias/title에 delimiter/Unicode가 있어 custom ad-hoc serialization을 깨뜨릴 수 있다.

**완화:** versioned structured serialization을 사용하고 Korean/emoji/quotes/long title fixtures를 테스트. 손상 시 원본 blob을 보존한다.

**게이트:** add/update/reorder/disable/delete/default/reload/corrupt/recovery tests 통과; legacy fixture 보존.

---

## 7. V7-04 — 여러 private bot 검증·재검증

**파일:**

- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/TelegramRepository.java`
- Create: `app/src/main/java/com/sidequestlab/floatingvoice/DestinationResolver.java`
- Modify: `MainActivity.java`와 bilingual strings는 V7-06에서 연결
- Add pure resolution decision/request tests

**검증 순서:**

```text
normalize username
→ searchPublicChat
→ ChatTypePrivate 확인
→ GetUser
→ UserTypeBot 확인
→ chatId + bot userId + logged-in account userId snapshot
→ duplicate/identity conflict 확인
→ catalog persist
```

**Tests first:**

- stale async result가 다른 add request를 덮어쓰지 않음
- title 동일/username 다름을 title로 병합하지 않음
- 같은 bot을 다른 alias로 중복 저장하지 않음
- 기존 username이 다른 canonical user를 가리키면 identity-change로 block
- 로그인 계정이 바뀌면 기존 catalog destination 사용 차단
- search/error/non-private/non-bot에서 저장 0
- verify/reverify에서 send call 0

**위험:** username은 변경/재사용 가능하고 async TDLib callback이 늦게 도착할 수 있다.

**완화:** request generation token + expected client/account/username 확인, canonical ID mismatch는 사용자 재승인 전 blocked 상태.

**게이트:** fake/pure tests 통과 후 사용자 승인된 실제 private bot 2개를 add/reverify하고 어떤 메시지도 전송되지 않았음을 확인. **2026-08-12 결정:** 현재 단계에는 호출 가능한 정식 UI가 없으므로 실기기 게이트를 V7-06 UI 구현 뒤로 이월한다. V7-06 검증용 APK를 사용자에게 전달하고, 사용자가 A52s에서 직접 설치·조작해 PASS/FAIL을 회신한다.

---

## 8. V7-05 — persistent dispatch snapshot·retry

**목적:** stop/send race와 process death에서도 원래 목적지를 보장한다.

**Blocking Spike A — TDLib process-death/update correlation:** 실제 private bot 2개와 사용자 승인된 테스트 메시지로 다음을 관찰한다.

- 서로 다른 chat에 보낼 때 temporary message ID와 `chatId` 상관관계
- `SendMessage` callback 전/후 process kill
- 재시작 후 `UpdateMessageSendSucceeded/Failed` replay 여부
- `GetMessage` 등으로 pending 상태를 reconcile할 수 있는지
- logout/account switch 중 pending send 동작

관찰 결과가 없으면 `sending_id`를 durable idempotency key로 간주하지 않는다. `QUEUED/UNKNOWN`은 자동 retry하지 않고, 중복 전송보다 사용자 확인을 우선한다.

**파일:**

- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/PendingDispatch.java`
- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/DispatchState.java`
- Create: corresponding core tests
- Create: `app/src/main/java/com/sidequestlab/floatingvoice/PendingDispatchStore.java`
- Migrate/retain compatibility: `PendingRecordingStore.java`
- Modify: `TelegramRepository.java`
- Modify: `FloatingVoiceService.java`

**freeze/send 순서:**

1. `RECORDING → FREEZING`
2. picker와 duplicate stop 비활성화
3. account/destination/file/duration/revision을 immutable snapshot으로 생성
4. application-owned `dispatchId`와 snapshot을 먼저 persist
5. TDLib `sendMessage(chatId=snapshot.chatId)` 호출
6. temp message ID/sending ID를 dispatch에 연결
7. final success에서만 file delete + record completed
8. failure/uncertain/process death에서 record+file 유지

**Tests first:**

- freeze 직전 default/next/current 변경 races
- freeze 뒤 change reject
- retry가 visible current destination이 아닌 snapshot destination 사용
- catalog destination 삭제 후 pending retry 유지
- temp ID mapping restart recovery
- duplicate callback/idempotent final success
- final success 후 delete 실패 상태
- `can_retry=false`/retry_after 처리
- unknown update에서 file delete 0
- `UNKNOWN`/reconcile 불가 상태에서 automatic retry 0

**위험:** 현재 `PendingRecordingStore.take()`는 failure update에서도 mapping을 제거한다. 재시도와 origin destination을 잃을 수 있다.

**완화:** failure는 record를 `FAILED_RETAINED`로 전환하고 snapshot/path를 보존한다. `take` 중심 API를 상태 전이 API로 교체한다.

**게이트:** Blocking Spike A로 recovery 정책을 확정하고, process-restart simulation과 network failure에서 original chatId/path 유지, wrong-room 0. Spike 미완료면 retry 구현 금지.

---

## 9. V7-06 — 메인 destination 관리 UI

**파일:**

- Create: `app/src/main/res/layout/destination_list_item.xml`
- Create: `app/src/main/res/layout/dialog_destination_editor.xml` 또는 승인된 equivalent
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/MainActivity.java`
- Modify: bilingual strings/styles

**기능:**

- destination 추가/alias/재검증/enable-disable/delete
- default 지정
- Telegram title, username, alias, verification 상태 구분 표시
- destructive delete 확인
- pending/failed dispatch가 있으면 `목록에서 제거해도 기록/파일은 보존` 고지

**선행검증:** UI wireframe 승인. private bot만 지원한다는 문구 표시.

**위험:** delete/default/reverify가 서로 비슷해 오조작 가능.

**완화:** destructive action 분리, default에는 명시적 badge, reverify 결과를 save 전 preview, identity mismatch는 replace가 아니라 blocked flow.

**게이트:** 2개 destination add/reorder/default/reverify/disable/delete/restart; pending record 불변.

---

## 10. V7-07 — overlay destination picker

**파일:**

- Create: `app/src/main/java/com/sidequestlab/floatingvoice/DestinationPickerOverlay.java`
- Create: `app/src/main/res/layout/overlay_destination_picker.xml`
- Create: `app/src/main/res/layout/overlay_destination_row.xml`
- Modify: `FloatingActionMenuController.java`
- Modify: `FloatingVoiceService.java`
- Modify: bilingual strings/drawables

**Blocking Spike B — picker surface 선택:** Android 10과 A52s에서 다음 두 후보를 비교한다.

1. user gesture로 여는 non-exported `DestinationPickerActivity` + bottom-sheet style
2. `TYPE_APPLICATION_OVERLAY` custom vertical list

긴 목록 scroll, TalkBack, 큰 글꼴, 화면 edge, Back/outside, 녹음 중 microphone FGS 유지, picker 닫은 직후 accidental stop을 비교한다. 안정성이 같지 않으면 Activity 방식을 기본으로 선택한다.

**UX:**

- first-level action: `보낼 곳`
- destination은 radial ring이 아닌 labeled vertical panel/bottom sheet
- idle title: `다음 1회 보낼 곳`
- recording title: `이번 녹음 보낼 곳`
- chip: `기본 · 개인비서`, `다음 1회 · 업무봇`, `이번 녹음 · 업무봇`
- 현재 checkmark 1개, color만으로 구분하지 않음

**Tests:**

- idle pick → next recording one-shot consume
- cancel 후 default 복귀, one-shot 부활 안 함
- recording pick → current recording만 변경
- stop freeze 후 picker disabled
- disabled/deleted/account mismatch entry 선택 불가
- adjacent row tap이 stop/send로 전달되지 않음
- Back/outside/service stop cleanup

**위험:** 서비스 기반 overlay에서 복잡한 list/scroll/accessibility가 불안정할 수 있다.

**완화:** v0.5.0 keyboard spike 결과처럼, overlay panel이 신뢰되지 않으면 visible Activity/bottom sheet로 폴백. 3개 recent/pinned만 overlay에 두고 전체는 main app에서 선택하는 축소안도 준비한다.

**게이트:** A52s에서 destination 2개 이상, font 200%, TalkBack, 4개 edge, 50회 scope/race에서 wrong-room 0.

---

## 11. V7-08 — lifecycle·account·race hardening

**실기기/자동화 파일 계획:**

- Create: `app/src/androidTest/java/com/sidequestlab/floatingvoice/DestinationPickerActivityTest.java`
- Create: `app/src/androidTest/java/com/sidequestlab/floatingvoice/DispatchRecoveryInstrumentedTest.java`
- Create: `scripts/verify-v060-process-death.sh`
- Modify: `app/build.gradle`과 `testInstrumentationRunner`는 AndroidX Test 도입을 승인한 별도 test-infrastructure 단계에서만 변경

**실기기 matrix:**

- default 변경 중 idle/recording/pending
- next-one 선택 후 Home/screen off/service restart/process kill
- recording target 변경 직후 stop rapid tap
- network disconnect before/after queued
- destination disable/delete while pending
- Telegram logout/account relogin
- username/title change와 canonical identity mismatch
- locale change와 font scaling

**정책:**

- service restart 시 unconsumed next-one은 폐기하고 persistent default로 복귀
- logout/account mismatch 시 catalog는 삭제하지 않되 blocked 표시
- pending dispatch는 원래 account ID와 destination snapshot 유지
- user가 명시적으로 폐기하기 전 failed file 삭제 금지

**위험:** TDLib callback과 app lifecycle이 다른 thread에서 상태를 갱신한다.

**완화:** catalog/route/dispatch state에는 단일 lock/serialized executor 정책을 문서화하고 listener publish는 immutable snapshot만 전달한다.

**게이트:** unit/race/device matrix PASS, blocked 상태가 자동 fallback으로 변하지 않음.

---

## 12. V7-09 — release candidate·update migration

**버전/문서 파일:**

- Modify: `app/build.gradle`
- Modify: `README.md`, `README.en.md`, `PRIVACY.md`, `SECURITY.md`
- Create: `docs/releases/v0.7.0.md`

**필수 update tests:**

1. `v0.4.1 → v0.7.0 RC`: session/account/legacy target 보존
2. `v0.5.0 → v0.7.0 RC`: default candidate를 `NEEDS_REVERIFY`로 migration → explicit reverify 후 `VERIFIED`
3. `v0.6.4 → v0.7.0 RC`: current session/config/target와 Quiet Recorder UI 보존
4. RC reinstall/restart: duplicate destination 0
5. migration failure fixture: legacy keys 보존
6. same certificate update install

**공통 clean build/artifact gate:** master plan의 R4–R7 수행. 이 단계의 산출물은 공식 서명 RC이며 최종 GitHub Release가 아니다.

**위험:** fixture migration이 통과해도 실제 설치된 Keystore/SharedPreferences/TDLib session 조합에서 legacy target이 유실되거나 잘못 `VERIFIED`될 수 있다.

**완화:** 실제 `v0.4.1`·`v0.6.4` 설치본 위에 RC를 update하고 account/session/legacy blob/default candidate를 read-back한다. 재검증 전 send 차단을 실기기에서 확인하며 실패 시 legacy keys를 보존한 채 release를 중단한다.

**live send gate:** 사용자 승인된 bot 2개에 각각 음성 1건, next-one 1건, current-recording 변경 1건, failure/retry 1건을 수행하고 실제 수신 destination을 확인한다. 승인 없으면 build만 verified, E2E는 미검증으로 분리한다.

**롤백:**

- migration committed 전에는 legacy read path 사용
- catalog UI를 disable해도 migrated default를 통해 단일 destination voice path 유지
- pending dispatch schema downgrade는 지원한다고 가정하지 않음; rollback 전 backup/export 계획 필요

**RC 게이트:** migration + wrong-room 0 + file retention + artifact + 사용자 RC 승인. 실제 사용에서 문제가 발견되거나 소스·리소스가 바뀌면 기존 RC를 폐기하고 `versionCode`를 올린 RC2/RC3를 새로 빌드·서명해 전체 회귀와 A52s 검증을 반복한다.

---

## 13. V7-10 — 기능 동결 최종 릴리즈

**선행조건:**

- 마지막 RC가 A52s 집중 matrix를 통과하고 사용자에게 최종 후보로 승인됨
- RC 이후 코드·리소스·빌드 설정 변경 0
- 열려 있는 P0/P1, 데이터 유실, 오전송, 자격정보 노출 결함 0

**수행 범위:**

1. 승인된 RC 소스 commit과 worktree 일치 확인
2. 기능 추가 없이 clean test/lint/release build
3. 공식 인증서, v2/v3, package/version, arm64 ABI, ZIP/ELF 16KB 정렬, secret scan 확인
4. RC와 동일 인증서로 기존 A52s 앱 위 update install
5. 기본·다음 1회·이번 녹음·재시도 경로 최종 A52s PASS
6. APK mtime/size/SHA-256과 소스 commit을 최종 provenance에 고정
7. 별도 사용자 승인 후에만 tag와 GitHub Release 생성

**불변 조건:** 최종 build 뒤 어떤 소스나 리소스라도 바뀌면 V7-10을 계속하지 않고 V7-09 새 RC로 되돌아간다. 테스트하지 않은 APK를 같은 버전명으로 교체하지 않는다.

**최종 게이트:** clean build + artifact 검증 + update install + 최종 A52s PASS + 사용자 release 승인. 모두 통과하기 전에는 tag/GitHub Release 미생성.

---

## 14. 증거 구조

```text
.hermes/evidence/v0.7.0/
  preflight-v0.6.4.md
  migration-fixtures.md
  destination-identity.md
  route-state-tests.md
  dispatch-recovery.md
  overlay-scope-matrix.md
  device-race-matrix.md
  rc-artifact-provenance.md
  final-artifact-provenance.md
```

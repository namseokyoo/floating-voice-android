# Settings Sections + Safe Target Replacement Implementation Plan

> **For Hermes:** Execute this plan task-by-task with TDD and independent review before the commit-bound APK build.

**Goal:** Deliver v0.6.2/code 12 with Settings split into App settings, Transfer destination, and Telegram connection, and restore target replacement without logout or session reset.

**Architecture:** Keep one verified destination. Add an explicit connection-flow mode for API editing versus destination editing. Resolve a candidate bot against the current authenticated client and commit config+target only after bot verification; stale or failed callbacks retain the previous destination. Stop a running overlay before opening destination editing.

**Tech Stack:** Android Java Views, Material 3, TDLib, encrypted SharedPreferences, pure Java core tests, Gradle/JUnit 5.

---

### Task 1: Pin terminology and target-change policy

**Files:**
- Create: `CONTEXT.md`
- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/TargetChangePolicy.java`
- Test: `core/src/test/java/com/sidequestlab/floatingvoice/core/TargetChangePolicyTest.java`

**Steps:**
1. Test that unauthenticated changes resume Telegram connection, running changes require overlay stop, and authenticated idle changes open the editor.
2. Implement the minimal pure policy and run the focused test.

### Task 2: Make target replacement transactional

**Files:**
- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/TelegramRepository.java`
- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/MainActivity.java`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-ko/strings.xml`

**Steps:**
1. Add generation-bound candidate resolution using the current client and connection config.
2. Preserve the current target during lookup and on every failure/stale callback.
3. On verified bot success, persist the updated config and target under the configuration lock, then notify listeners.
4. Expose the immutable current config so MainActivity refreshes its local copy only after success.
5. Keep initial target setup routed through the same safe resolution path.

### Task 3: Split Settings into three sections

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/MainActivity.java`
- Modify: default and Korean string resources

**Steps:**
1. Rename the first section to App settings and keep language/size/color.
2. Add Transfer destination with current summary and Set/Change action.
3. Rename the account section to Telegram connection and keep API information plus logout.
4. Add explicit connection modes: RESUME, EDIT_API, EDIT_TARGET.
5. Force EDIT_TARGET to show only stage 3 while authentication remains READY.

### Task 4: Stop overlay before destination editing

**Files:**
- Modify: `MainActivity.java`
- Test: `TargetChangePolicyTest.java`

**Steps:**
1. If the overlay is running, show a stop-and-change confirmation.
2. Set a pending editor flag, stop the service, and wait for the service-stopped broadcast.
3. Open destination editing only after `FloatingVoiceService.isRunning()` is false.
4. Preserve pending intent across Activity state restoration.

### Task 5: Review hardening and delivery

**Files:**
- Modify: `app/build.gradle`
- Modify: pending-send stores and repository update routing
- Modify: encrypted routing persistence and Activity accessibility/state restoration
- Create: `docs/releases/v0.6.2.md`

**Steps:**
1. Key pending sends by chat ID + temporary message ID and classify callbacks by message content; migrate v0.6.1 single-target keys at startup.
2. Persist verified config+target as one encrypted `AtomicFile` snapshot so a failed write restores the previous routing state.
3. Invalidate target resolution on authorization generation changes under the repository configuration lock.
4. Restore page/edit/pending-candidate state across Activity recreation and announce results through live regions/Snackbar/headings.
5. Set `versionCode 12`, `versionName 0.6.2`, and a distinct debug suffix.
6. Run focused tests, localization parity, full `clean test lintDebug lintRelease assembleRelease`, and `git diff --check`.
7. Review spec/runtime safety, especially failed lookup preservation, async generation, pending-send chat scoping, persistence rollback, and reachable Settings flow.
8. Commit and push; verify local/origin/GitHub SHA equality.
9. Clean-build the immutable commit, zipalign, sign with official v2/v3 certificate, verify package/version/SDK/ABI/16KB/payload/secrets/freshness, and deliver the new APK without ADB.

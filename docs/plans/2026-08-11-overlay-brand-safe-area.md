# Floating Button Brand + Safe Area Implementation Plan

> **For Hermes:** Execute this plan task-by-task with review before the commit-bound APK build.

**Goal:** Match the idle floating button to the launcher icon, add a persisted color preset beside size settings, and keep the Activity and every overlay surface outside system bars and display cutouts.

**Architecture:** Keep recording/error red fixed. Add a pure core color-preset enum, persist only its stable enum name, and resolve preset resources in the app layer. Apply Activity insets through `WindowInsetsCompat`; calculate service overlay bounds synchronously from current `WindowMetrics` and clamp idle, recording dock, drag, and palette placement to the same absolute safe rectangle.

**Tech Stack:** Android Java/View, Material 3 Views, SharedPreferences, AndroidX WindowInsetsCompat, WindowMetrics, JUnit 4, Gradle.

---

### Task 1: Add persisted overlay color presets

**Objective:** Provide stable Sage/Ocean/Violet/Amber presets with Sage as the migration-safe default.

**Files:**
- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/OverlayColorPreset.java`
- Create: `core/src/test/java/com/sidequestlab/floatingvoice/core/OverlayColorPresetTest.java`
- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/OverlayUiPreferences.java`

**Steps:**
1. Write tests for default/fallback name parsing and bounded position parsing.
2. Run the focused core test and require initial failure.
3. Implement the enum and SharedPreferences accessors using a stable enum name.
4. Run focused and full core tests.

### Task 2: Add the color selector to Settings

**Objective:** Put color selection directly after floating-button size and apply changes while the service is running.

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-ko/strings.xml`
- Modify: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/values-night/colors.xml`
- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/MainActivity.java`
- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/FloatingVoiceService.java`
- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/FloatingOverlayViewController.java`

**Steps:**
1. Add localized preset arrays and four deterministic background/foreground resource pairs.
2. Add an accessible Spinner labelled “Floating button color”.
3. Initialize it from preferences and save selection without rewriting unchanged values.
4. Listen for both size and color keys; update only the idle surface, never recording red.
5. Compile resources and Java.

### Task 3: Match the launcher icon anatomy

**Objective:** Replace the rounded-square/plain-mic idle control with the launcher’s circular mic-and-wave artwork.

**Files:**
- Modify: `app/src/main/res/layout/overlay_primary_control.xml`
- Modify: `app/src/main/res/values/styles.xml`
- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/FloatingOverlayViewController.java`
- Reuse: `app/src/main/res/mipmap-*/ic_launcher_foreground.png`

**Steps:**
1. Set the idle shape corner size to 50% so every size preset stays circular.
2. Reuse the launcher foreground asset, whose opaque content occupies about 65% × 59% of the square.
3. Size the full drawable to the button bounds so its visible symbol matches the launcher’s safe-zone ratio.
4. Apply Sage by default and preserve TalkBack click semantics and 48dp minimum targets.

### Task 4: Fix Activity and overlay safe areas

**Objective:** Prevent top app-bar and bottom CTA clipping and keep service-owned windows outside status/navigation bars and cutouts.

**Files:**
- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/MainActivity.java`
- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/DisplaySafeBounds.java`
- Modify: `app/src/main/java/com/sidequestlab/floatingvoice/FloatingVoiceService.java`
- Create: `core/src/main/java/com/sidequestlab/floatingvoice/core/OverlayReflowPolicy.java`
- Test: `core/src/test/java/com/sidequestlab/floatingvoice/core/OverlayReflowPolicyTest.java`
- Existing tests: `core/src/test/java/com/sidequestlab/floatingvoice/core/AnchoredPanelPlacementTest.java`

**Steps:**
1. Apply `systemBars | displayCutout` insets as additive root padding through `WindowInsetsCompat`.
2. Use `getCurrentWindowMetrics()` on API 30+ and preserve absolute safe bounds.
3. Keep the documented API 29 real-display fallback.
4. Verify idle, resized dock, drag clamp, and palette all consume the same `DisplaySafeBounds` rectangle.
5. Reflow a visible idle bubble or recording dock after configuration/display changes; close an open palette and return its anchor to the safe rectangle.
6. Run placement/reflow tests and Android Lint; review the known API-29 fallback warnings.

### Task 5: Version and release contract

**Objective:** Preserve delivered v0.6.0/code 10 and create a distinct test candidate.

**Files:**
- Modify: `app/build.gradle`
- Create: `docs/releases/v0.6.1.md`

**Steps:**
1. Set `versionCode 11`, `versionName 0.6.1`, and a distinct debug suffix.
2. Document the color presets, launcher-matched default, safe-area behavior, and user-operated checks.
3. Run localization parity and `git diff --check`.

### Task 6: Review, commit, push, and build the APK

**Objective:** Deliver a commit-bound, signed, arm64 candidate with fresh evidence.

**Steps:**
1. Run independent Android safety/spec review against the finalized diff and resolve actionable findings.
2. Run `./gradlew clean test lintDebug lintRelease assembleRelease`.
3. Commit with detailed rationale/impact/verification and push; verify local/origin/GitHub SHA equality.
4. Re-run the clean build from the pushed immutable commit.
5. Zipalign and sign with the established certificate using v2/v3 coverage.
6. Verify package `com.sidequestlab.floatingvoice`, version `0.6.1 (11)`, launcher, SDK 29/36, arm64 ABI, signature, 16KB ZIP/ELF alignment, payload identity, credential-shaped strings, freshness, size, and SHA-256.
7. Deliver `FloatingVoice-arm64-v0.6.1-overlay-style-safe-area-candidate.apk` with a manual device checklist; do not use ADB.

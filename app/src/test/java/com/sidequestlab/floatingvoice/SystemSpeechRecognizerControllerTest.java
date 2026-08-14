package com.sidequestlab.floatingvoice;

import com.sidequestlab.floatingvoice.core.AudioCaptureOwnership;
import com.sidequestlab.floatingvoice.core.SpeechShareStateMachine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SystemSpeechRecognizerControllerTest {
    @Test
    public void startWhileRecordingIsDeniedBeforePlatformAccess() {
        Fixture f = new Fixture(30);
        f.coordinator.startRecording().orElseThrow();

        assertEquals(SystemSpeechRecognizerController.StartResult.OWNERSHIP_DENIED,
                f.controller.start());
        assertFalse(f.platform.touched);
        assertEquals(AudioCaptureOwnership.Owner.RECORDING, f.coordinator.owner());
        assertEquals(List.of(SystemSpeechRecognizerController.OutcomeType.OWNERSHIP_DENIED),
                f.listener.types());
    }

    @Test
    public void unavailableRecognizerFailsExplicitlyAndReleasesAbsentResource() {
        Fixture f = new Fixture(30);
        f.platform.recognitionAvailable = false;

        assertEquals(SystemSpeechRecognizerController.StartResult.UNAVAILABLE,
                f.controller.start());
        assertEquals(AudioCaptureOwnership.Owner.NONE, f.coordinator.owner());
        assertEquals(SpeechShareStateMachine.State.STT_FAILED, f.coordinator.speechState());
        assertEquals(List.of(SystemSpeechRecognizerController.OutcomeType.UNAVAILABLE),
                f.listener.types());
        assertEquals(1L, f.listener.last().generation());
    }

    @Test
    public void api29And30UseOnlyStandardRecognizerWithKoreanRequest() {
        for (int api : List.of(29, 30)) {
            Fixture f = new Fixture(api);

            assertEquals(SystemSpeechRecognizerController.StartResult.STARTED,
                    f.controller.start());
            assertEquals(1, f.platform.standardCreates);
            assertEquals(0, f.platform.onDeviceAvailabilityChecks);
            assertEquals(0, f.platform.onDeviceCreates);
            assertEquals(0, f.platform.supportChecks);
            assertEquals("ko-KR", f.platform.recognizer.lastRequest.languageTag());
            assertTrue(f.platform.recognizer.lastRequest.partialResults());
            assertTrue(f.platform.recognizer.lastRequest.preferOffline());
            assertEquals("com.test.floatingvoice", f.platform.recognizer.lastRequest.callingPackage());
        }
    }

    @Test
    public void api31And32UseOnDeviceOnlyWhenAvailableOtherwiseExplicitStandardFallback() {
        for (int api : List.of(31, 32)) {
            Fixture onDevice = new Fixture(api);
            onDevice.controller.start();
            assertEquals(1, onDevice.platform.onDeviceCreates);
            assertEquals(0, onDevice.platform.standardCreates);
            assertEquals(SpeechRecognitionSupport.Route.ON_DEVICE,
                    onDevice.listener.lastSupport().route());

            Fixture fallback = new Fixture(api);
            fallback.platform.onDeviceAvailable = false;
            fallback.controller.start();
            assertEquals(0, fallback.platform.onDeviceCreates);
            assertEquals(1, fallback.platform.standardCreates);
            assertEquals(SpeechRecognitionSupport.Route.STANDARD,
                    fallback.listener.lastSupport().route());
            assertEquals(SpeechRecognitionSupport.FallbackReason.ON_DEVICE_UNAVAILABLE,
                    fallback.listener.lastSupport().fallbackReason());
        }
    }

    @Test
    public void api31And32LanguageErrorFallsBackFromOnDeviceToStandardOnce() {
        for (int api : List.of(31, 32)) {
            Fixture f = new Fixture(api);
            f.controller.start();

            f.platform.callback.onError(SystemSpeechRecognizerController.PlatformError.LANGUAGE);

            assertEquals(1, f.platform.onDeviceCreates);
            assertEquals(1, f.platform.standardCreates);
            assertEquals(2, f.platform.recognizer.starts);
            assertEquals(1, f.platform.recognizer.destroys);
            assertEquals(AudioCaptureOwnership.Owner.STT, f.coordinator.owner());
            assertEquals(SpeechShareStateMachine.State.STT_LISTENING,
                    f.coordinator.speechState());
            assertEquals(SpeechRecognitionSupport.FallbackReason.ON_DEVICE_LANGUAGE_ERROR,
                    f.listener.lastSupport().fallbackReason());
        }
    }

    @Test
    public void onDeviceCreationFailureFallsBackToStandardOrRequiresKeyboardExplicitly() {
        Fixture fallback = new Fixture(31);
        fallback.platform.throwOnOnDeviceCreate = true;
        assertEquals(SystemSpeechRecognizerController.StartResult.STARTED,
                fallback.controller.start());
        assertEquals(1, fallback.platform.standardCreates);
        assertEquals(SpeechRecognitionSupport.FallbackReason.ON_DEVICE_CREATION_FAILED,
                fallback.listener.lastSupport().fallbackReason());

        Fixture keyboard = new Fixture(31);
        keyboard.platform.throwOnOnDeviceCreate = true;
        keyboard.platform.throwOnStandardCreate = true;
        assertEquals(SystemSpeechRecognizerController.StartResult.KEYBOARD_REQUIRED,
                keyboard.controller.start());
        assertEquals(AudioCaptureOwnership.Owner.NONE, keyboard.coordinator.owner());
        assertEquals(SystemSpeechRecognizerController.OutcomeType.KEYBOARD_REQUIRED,
                keyboard.listener.last().type());
    }

    @Test
    public void api33DownloadRequiredFallsBackToStandardWithoutAutomaticModelDownload() {
        Fixture f = new Fixture(33);
        f.platform.deferSupport = true;

        assertEquals(SystemSpeechRecognizerController.StartResult.STARTED, f.controller.start());
        assertEquals(1, f.platform.supportChecks);
        assertEquals(0, f.platform.recognizer.starts);
        assertEquals(0, f.platform.modelDownloads);
        assertEquals(SpeechRecognitionSupport.ModelState.CHECKING,
                f.listener.lastSupport().modelState());

        f.platform.supportCallback.onResult(
                SystemSpeechRecognizerController.PlatformSupport.DOWNLOAD_REQUIRED);

        assertEquals(0, f.platform.modelDownloads);
        assertEquals(1, f.platform.onDeviceCreates);
        assertEquals(1, f.platform.standardCreates);
        assertEquals(2, f.platform.supportChecks);
        assertEquals(AudioCaptureOwnership.Owner.STT, f.coordinator.owner());
        assertEquals(SpeechRecognitionSupport.Route.STANDARD,
                f.listener.lastSupport().route());
        assertEquals(SpeechRecognitionSupport.FallbackReason.ON_DEVICE_MODEL_DOWNLOAD_REQUIRED,
                f.listener.lastSupport().fallbackReason());

        f.platform.supportCallback.onResult(
                SystemSpeechRecognizerController.PlatformSupport.READY);

        assertEquals(1, f.platform.recognizer.starts);
        assertEquals(0, f.platform.modelDownloads);
        assertEquals(SpeechShareStateMachine.State.STT_LISTENING, f.coordinator.speechState());
    }

    @Test
    public void duplicateReadySupportCallbackStartsListeningOnlyOnce() {
        Fixture f = new Fixture(33);
        f.platform.deferSupport = true;
        f.controller.start();

        f.platform.supportCallback.onResult(
                SystemSpeechRecognizerController.PlatformSupport.READY);
        f.platform.supportCallback.onResult(
                SystemSpeechRecognizerController.PlatformSupport.READY);

        assertEquals(1, f.platform.recognizer.starts);
        assertEquals(1, f.listener.types().stream()
                .filter(type -> type == SystemSpeechRecognizerController.OutcomeType.LISTENING)
                .count());
    }

    @Test
    public void startListeningFailureIsTerminalAndDestroysBeforeLeaseRelease() {
        Fixture f = new Fixture(30);
        f.platform.recognizer.throwOnStart = true;

        assertEquals(SystemSpeechRecognizerController.StartResult.UNAVAILABLE,
                f.controller.start());
        assertEquals(1, f.platform.recognizer.destroys);
        assertEquals(AudioCaptureOwnership.Owner.NONE, f.coordinator.owner());
        assertEquals(SystemSpeechRecognizerController.ErrorKind.START_FAILED,
                f.listener.last().error());
    }

    @Test
    public void synchronousTerminalCallbackDuringStartDoesNotEmitListeningAfterFailure() {
        Fixture f = new Fixture(30);
        f.platform.recognizer.onStart = () -> f.platform.callback.onError(
                SystemSpeechRecognizerController.PlatformError.BUSY);

        f.controller.start();

        assertEquals(SystemSpeechRecognizerController.OutcomeType.ERROR,
                f.listener.last().type());
        assertFalse(f.listener.types().contains(
                SystemSpeechRecognizerController.OutcomeType.LISTENING));
        assertEquals(1, f.platform.recognizer.destroys);
        assertEquals(AudioCaptureOwnership.Owner.NONE, f.coordinator.owner());
    }

    @Test
    public void cancelReenteredFromPre33SupportOutcomePreventsStartOnDestroyedRecognizer() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        FakePlatform platform = new FakePlatform(30);
        SystemSpeechRecognizerController[] holder = new SystemSpeechRecognizerController[1];
        holder[0] = new SystemSpeechRecognizerController(coordinator, platform, outcome -> {
            if (outcome.type() == SystemSpeechRecognizerController.OutcomeType.SUPPORT_CHANGED) {
                holder[0].cancel();
            }
        });

        holder[0].start();

        assertEquals(0, platform.recognizer.starts);
        assertEquals(1, platform.recognizer.destroys);
        assertEquals(AudioCaptureOwnership.Owner.NONE, coordinator.owner());
        assertEquals(SpeechShareStateMachine.State.STT_CANCELED, coordinator.speechState());
    }

    @Test
    public void cancelReenteredFromApi33NonReadyOutcomePreventsFallbackWithoutOwnership() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        FakePlatform platform = new FakePlatform(33);
        platform.deferSupport = true;
        SystemSpeechRecognizerController[] holder = new SystemSpeechRecognizerController[1];
        holder[0] = new SystemSpeechRecognizerController(coordinator, platform, outcome -> {
            if (outcome.support() != null
                    && outcome.support().modelState()
                    == SpeechRecognitionSupport.ModelState.DOWNLOAD_REQUIRED) {
                holder[0].cancel();
            }
        });
        holder[0].start();

        platform.supportCallback.onResult(
                SystemSpeechRecognizerController.PlatformSupport.DOWNLOAD_REQUIRED);

        assertEquals(0, platform.standardCreates);
        assertEquals(1, platform.recognizer.destroys);
        assertEquals(AudioCaptureOwnership.Owner.NONE, coordinator.owner());
        assertEquals(SpeechShareStateMachine.State.STT_CANCELED, coordinator.speechState());
    }

    @Test
    public void blankFinalIsReportedAsNoMatchFailureNotSuccessfulFinal() {
        Fixture f = new Fixture(30);
        f.controller.start();

        f.platform.callback.onFinalResult("   ");

        assertEquals(SystemSpeechRecognizerController.OutcomeType.ERROR,
                f.listener.last().type());
        assertEquals(SystemSpeechRecognizerController.ErrorKind.NO_MATCH,
                f.listener.last().error());
        assertEquals(SpeechShareStateMachine.State.STT_FAILED, f.coordinator.speechState());
        assertFalse(f.listener.types().contains(
                SystemSpeechRecognizerController.OutcomeType.FINAL_RESULT));
    }

    @Test
    public void busyNetworkNoMatchAndTimeoutErrorsMapAtPublicSeam() {
        assertErrorMapping(SystemSpeechRecognizerController.PlatformError.BUSY,
                SystemSpeechRecognizerController.ErrorKind.BUSY);
        assertErrorMapping(SystemSpeechRecognizerController.PlatformError.NETWORK,
                SystemSpeechRecognizerController.ErrorKind.NETWORK);
        assertErrorMapping(SystemSpeechRecognizerController.PlatformError.NO_MATCH,
                SystemSpeechRecognizerController.ErrorKind.NO_MATCH);
        assertErrorMapping(SystemSpeechRecognizerController.PlatformError.TIMEOUT,
                SystemSpeechRecognizerController.ErrorKind.TIMEOUT);
    }

    @Test
    public void partialFinalAndDuplicateTerminalCallbacksRouteOnceAndNeverRestart() {
        Fixture f = new Fixture(30);
        f.controller.start();
        long generation = f.coordinator.speechGeneration();

        f.platform.callback.onPartialResult(" 임시 ");
        f.platform.callback.onProcessing();
        f.platform.callback.onFinalResult(" 최종 ");
        f.platform.callback.onError(SystemSpeechRecognizerController.PlatformError.BUSY);
        f.platform.callback.onFinalResult("late");

        assertEquals(List.of(
                SystemSpeechRecognizerController.OutcomeType.SUPPORT_CHANGED,
                SystemSpeechRecognizerController.OutcomeType.LISTENING,
                SystemSpeechRecognizerController.OutcomeType.PARTIAL_RESULT,
                SystemSpeechRecognizerController.OutcomeType.PROCESSING,
                SystemSpeechRecognizerController.OutcomeType.FINAL_RESULT), f.listener.types());
        assertEquals("최종", f.listener.last().text());
        assertEquals(generation, f.listener.last().generation());
        assertEquals(1, f.platform.recognizer.starts);
        assertEquals(1, f.platform.recognizer.destroys);
        assertEquals(SpeechShareStateMachine.State.STT_REVIEW, f.coordinator.speechState());
        assertEquals(AudioCaptureOwnership.Owner.NONE, f.coordinator.owner());
    }

    @Test
    public void cancelResultRaceUsesFirstTerminalAndPhysicalDestroyExactlyOnce() {
        Fixture f = new Fixture(30);
        f.controller.start();
        SystemSpeechRecognizerController.Callback stale = f.platform.callback;

        f.controller.cancel();
        stale.onFinalResult("late");
        f.controller.cancel();

        assertEquals(1, f.platform.recognizer.cancels);
        assertEquals(1, f.platform.recognizer.destroys);
        assertEquals(SystemSpeechRecognizerController.OutcomeType.CANCELED,
                f.listener.last().type());
        assertEquals(SpeechShareStateMachine.State.STT_CANCELED,
                f.coordinator.speechState());
        assertEquals(AudioCaptureOwnership.Owner.NONE, f.coordinator.owner());
    }

    @Test
    public void destroyExceptionFailsClosedAndIsNeverRetried() {
        Fixture f = new Fixture(30);
        f.controller.start();
        f.platform.recognizer.throwOnDestroy = true;

        f.platform.callback.onError(SystemSpeechRecognizerController.PlatformError.NETWORK);
        f.controller.destroy();

        assertEquals(1, f.platform.recognizer.destroys);
        assertEquals(AudioCaptureOwnership.Owner.STT, f.coordinator.owner());
        assertEquals(SystemSpeechRecognizerController.OutcomeType.DESTROY_FAILED,
                f.listener.last().type());
        assertEquals(SystemSpeechRecognizerController.StartResult.DESTROYED,
                f.controller.start());
    }

    @Test
    public void staleCallbacksAfterDestroyCannotAffectExplicitNextGeneration() {
        Fixture f = new Fixture(30);
        f.controller.start();
        long first = f.coordinator.speechGeneration();
        SystemSpeechRecognizerController.Callback stale = f.platform.callback;
        stale.onError(SystemSpeechRecognizerController.PlatformError.NO_MATCH);

        f.platform.replaceRecognizer();
        assertEquals(SystemSpeechRecognizerController.StartResult.STARTED, f.controller.start());
        long second = f.coordinator.speechGeneration();
        assertNotEquals(first, second);
        stale.onFinalResult("stale transcript");

        assertEquals(SpeechShareStateMachine.State.STT_LISTENING, f.coordinator.speechState());
        assertNull(f.listener.outcomes.stream()
                .filter(outcome -> "stale transcript".equals(outcome.text()))
                .findFirst().orElse(null));
        assertEquals(1, f.platform.recognizer.starts);
    }

    @Test
    public void staleControllerDestroyCannotCompleteNewerReviewInteraction() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        FakePlatform firstPlatform = new FakePlatform(30);
        SystemSpeechRecognizerController first = new SystemSpeechRecognizerController(
                coordinator, firstPlatform, outcome -> { });
        first.start();
        first.cancel();

        FakePlatform secondPlatform = new FakePlatform(30);
        SystemSpeechRecognizerController second = new SystemSpeechRecognizerController(
                coordinator, secondPlatform, outcome -> { });
        second.start();
        long secondGeneration = coordinator.speechGeneration();
        secondPlatform.callback.onFinalResult("두 번째 결과");
        assertEquals(SpeechShareStateMachine.State.STT_REVIEW, coordinator.speechState());

        first.destroy();

        assertEquals(secondGeneration, coordinator.speechGeneration());
        assertEquals(SpeechShareStateMachine.State.STT_REVIEW, coordinator.speechState());
    }

    @Test
    public void lifecycleDestroyCancelsCaptureReleasesAfterDestroyAndCompletesInteraction() {
        Fixture f = new Fixture(30);
        f.controller.start();

        f.controller.destroy();
        f.controller.destroy();

        assertEquals(1, f.platform.recognizer.cancels);
        assertEquals(1, f.platform.recognizer.destroys);
        assertEquals(AudioCaptureOwnership.Owner.NONE, f.coordinator.owner());
        assertEquals(SpeechShareStateMachine.State.IDLE, f.coordinator.speechState());
        assertEquals(SystemSpeechRecognizerController.OutcomeType.DESTROYED,
                f.listener.last().type());
    }

    private static void assertErrorMapping(
            SystemSpeechRecognizerController.PlatformError platformError,
            SystemSpeechRecognizerController.ErrorKind expected) {
        Fixture f = new Fixture(30);
        f.controller.start();
        f.platform.callback.onError(platformError);
        assertEquals(expected, f.listener.last().error());
        assertEquals(SystemSpeechRecognizerController.OutcomeType.ERROR, f.listener.last().type());
        assertEquals(1, f.platform.recognizer.destroys);
        assertEquals(AudioCaptureOwnership.Owner.NONE, f.coordinator.owner());
    }

    private static final class Fixture {
        final AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        final FakePlatform platform;
        final RecordingListener listener = new RecordingListener();
        final SystemSpeechRecognizerController controller;

        Fixture(int apiLevel) {
            platform = new FakePlatform(apiLevel);
            controller = new SystemSpeechRecognizerController(coordinator, platform, listener);
        }
    }

    private static final class RecordingListener
            implements SystemSpeechRecognizerController.Listener {
        final List<SystemSpeechRecognizerController.Outcome> outcomes = new ArrayList<>();

        @Override public void onOutcome(SystemSpeechRecognizerController.Outcome outcome) {
            outcomes.add(outcome);
        }

        List<SystemSpeechRecognizerController.OutcomeType> types() {
            return outcomes.stream().map(SystemSpeechRecognizerController.Outcome::type).toList();
        }

        SystemSpeechRecognizerController.Outcome last() {
            return outcomes.get(outcomes.size() - 1);
        }

        SpeechRecognitionSupport lastSupport() {
            for (int i = outcomes.size() - 1; i >= 0; i--) {
                if (outcomes.get(i).support() != null) return outcomes.get(i).support();
            }
            return null;
        }
    }

    private static final class FakeRecognizer
            implements SystemSpeechRecognizerController.Recognizer {
        int starts;
        int cancels;
        int destroys;
        boolean throwOnStart;
        boolean throwOnDestroy;
        Runnable onStart;
        SystemSpeechRecognizerController.RecognitionRequest lastRequest;

        @Override public void startListening(
                SystemSpeechRecognizerController.RecognitionRequest request) {
            starts++;
            lastRequest = request;
            if (throwOnStart) throw new IllegalStateException("start failed");
            if (onStart != null) onStart.run();
        }

        @Override public void cancel() {
            cancels++;
        }

        @Override public void destroy() {
            destroys++;
            if (throwOnDestroy) throw new IllegalStateException("destroy failed");
        }
    }

    private static final class FakePlatform implements SystemSpeechRecognizerController.Platform {
        final int apiLevel;
        boolean touched;
        boolean recognitionAvailable = true;
        boolean onDeviceAvailable = true;
        boolean throwOnOnDeviceCreate;
        boolean throwOnStandardCreate;
        boolean deferSupport;
        int standardCreates;
        int onDeviceAvailabilityChecks;
        int onDeviceCreates;
        int supportChecks;
        int modelDownloads;
        FakeRecognizer recognizer = new FakeRecognizer();
        SystemSpeechRecognizerController.Callback callback;
        SystemSpeechRecognizerController.SupportCallback supportCallback;

        FakePlatform(int apiLevel) {
            this.apiLevel = apiLevel;
        }

        void replaceRecognizer() {
            recognizer = new FakeRecognizer();
        }

        @Override public void assertMainThread() { }

        @Override public int apiLevel() {
            touched = true;
            return apiLevel;
        }

        @Override public String callingPackage() {
            return "com.test.floatingvoice";
        }

        @Override public boolean isRecognitionAvailable() {
            touched = true;
            return recognitionAvailable;
        }

        @Override public boolean isOnDeviceRecognitionAvailable() {
            touched = true;
            onDeviceAvailabilityChecks++;
            return onDeviceAvailable;
        }

        @Override public SystemSpeechRecognizerController.Recognizer createStandardRecognizer(
                SystemSpeechRecognizerController.Callback callback) {
            touched = true;
            standardCreates++;
            if (throwOnStandardCreate) throw new IllegalStateException("standard failed");
            this.callback = callback;
            return recognizer;
        }

        @Override public SystemSpeechRecognizerController.Recognizer createOnDeviceRecognizer(
                SystemSpeechRecognizerController.Callback callback) {
            touched = true;
            onDeviceCreates++;
            if (throwOnOnDeviceCreate) throw new IllegalStateException("on-device failed");
            this.callback = callback;
            return recognizer;
        }

        @Override public void checkRecognitionSupport(
                SystemSpeechRecognizerController.Recognizer recognizer,
                SystemSpeechRecognizerController.RecognitionRequest request,
                SystemSpeechRecognizerController.SupportCallback callback) {
            supportChecks++;
            supportCallback = callback;
            if (!deferSupport) callback.onResult(
                    SystemSpeechRecognizerController.PlatformSupport.READY);
        }

        @Override public void triggerModelDownload(
                SystemSpeechRecognizerController.Recognizer recognizer,
                SystemSpeechRecognizerController.RecognitionRequest request) {
            modelDownloads++;
        }
    }
}

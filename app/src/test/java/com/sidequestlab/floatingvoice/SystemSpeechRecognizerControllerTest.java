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
            assertEquals(2, f.platform.totalStarts());
            assertEquals(1, f.platform.totalDestroys());
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
    public void finalBeforeStopChainsFreshCycleAndStopFinalReviewsCumulativeTextOnce() {
        Fixture f = new Fixture(30);
        f.controller.start();
        long generation = f.coordinator.speechGeneration();
        SystemSpeechRecognizerController.Callback firstCycle = f.platform.callback;

        firstCycle.onProcessing();
        assertEquals(SpeechShareStateMachine.State.STT_PROCESSING, f.coordinator.speechState());
        firstCycle.onFinalResult(" 첫 문장 ");
        f.platform.runNextPosted();

        assertEquals(generation, f.coordinator.speechGeneration());
        assertEquals(SpeechShareStateMachine.State.STT_LISTENING, f.coordinator.speechState());
        assertEquals(AudioCaptureOwnership.Owner.STT, f.coordinator.owner());
        assertNotEquals(firstCycle, f.platform.callback);
        assertEquals(2, f.platform.standardCreates);
        assertEquals(2, f.platform.totalStarts());
        assertEquals(1, f.platform.totalDestroys());
        assertEquals("첫 문장", f.listener.outcomes.stream()
                .filter(outcome -> outcome.type()
                        == SystemSpeechRecognizerController.OutcomeType.PARTIAL_RESULT)
                .reduce((left, right) -> right).orElseThrow().text());
        assertEquals(SystemSpeechRecognizerController.OutcomeType.LISTENING,
                f.listener.last().type());

        f.controller.stopListening();
        f.controller.stopListening();
        f.platform.callback.onPartialResult("둘째 문장");
        f.platform.callback.onFinalResult("둘째 문장");
        f.platform.callback.onFinalResult("duplicate late");

        assertEquals(1, f.platform.totalStops());
        assertEquals(2, f.platform.totalDestroys());
        assertEquals(AudioCaptureOwnership.Owner.NONE, f.coordinator.owner());
        assertEquals(SpeechShareStateMachine.State.STT_REVIEW, f.coordinator.speechState());
        assertEquals("첫 문장 둘째 문장", f.listener.last().text());
        assertEquals(1, f.listener.outcomes.stream()
                .filter(outcome -> outcome.type()
                        == SystemSpeechRecognizerController.OutcomeType.FINAL_RESULT)
                .count());
    }

    @Test
    public void finalSchedulesFreshCycleOnlyAfterCallbackUnwinds() {
        Fixture f = new Fixture(30);
        f.controller.start();
        long generation = f.coordinator.speechGeneration();
        SystemSpeechRecognizerController.Callback first = f.platform.callback;

        first.onProcessing();
        first.onFinalResult("첫 문장");

        assertEquals(1, f.platform.standardCreates);
        assertEquals(1, f.platform.postedRestarts.size());
        assertEquals(generation, f.coordinator.speechGeneration());
        assertEquals(AudioCaptureOwnership.Owner.STT, f.coordinator.owner());
        assertEquals(SpeechShareStateMachine.State.STT_PROCESSING,
                f.coordinator.speechState());

        f.platform.runNextPosted();

        assertEquals(2, f.platform.standardCreates);
        assertEquals(2, f.platform.totalStarts());
        assertEquals(generation, f.coordinator.speechGeneration());
        assertEquals(AudioCaptureOwnership.Owner.STT, f.coordinator.owner());
        assertEquals(SpeechShareStateMachine.State.STT_LISTENING,
                f.coordinator.speechState());
    }

    @Test
    public void stopWhileFreshCycleIsPendingReviewsAccumulatedTextWithoutRestart() {
        Fixture f = new Fixture(30);
        f.controller.start();
        f.platform.callback.onProcessing();
        f.platform.callback.onFinalResult("첫 문장");

        assertEquals(1, f.platform.postedRestarts.size());
        f.controller.stopListening();
        f.platform.runNextPosted();

        assertEquals(1, f.platform.standardCreates);
        assertEquals(1, f.platform.totalStarts());
        assertEquals(0, f.platform.totalStops());
        assertEquals(AudioCaptureOwnership.Owner.NONE, f.coordinator.owner());
        assertEquals(SpeechShareStateMachine.State.STT_REVIEW,
                f.coordinator.speechState());
        assertEquals(SystemSpeechRecognizerController.OutcomeType.FINAL_RESULT,
                f.listener.last().type());
        assertEquals("첫 문장", f.listener.last().text());
    }

    @Test
    public void chainedBusySchedulesRetryAndPreservesAccumulatedText() {
        Fixture f = new Fixture(30);
        f.controller.start();
        f.platform.callback.onFinalResult("첫 문장");
        f.platform.runNextPosted();
        SystemSpeechRecognizerController.Callback busyCycle = f.platform.callback;

        busyCycle.onError(SystemSpeechRecognizerController.PlatformError.BUSY);

        assertEquals(AudioCaptureOwnership.Owner.STT, f.coordinator.owner());
        assertEquals(1, f.platform.postedRestarts.size());
        assertFalse(f.listener.types().contains(
                SystemSpeechRecognizerController.OutcomeType.ERROR));

        f.platform.runNextPosted();
        assertEquals(3, f.platform.standardCreates);
        assertEquals(3, f.platform.totalStarts());
        f.platform.callback.onPartialResult("둘째 문장");
        f.controller.stopListening();
        f.platform.callback.onFinalResult("둘째 문장");

        assertEquals(SpeechShareStateMachine.State.STT_REVIEW,
                f.coordinator.speechState());
        assertEquals("첫 문장 둘째 문장", f.listener.last().text());
        assertEquals(AudioCaptureOwnership.Owner.NONE, f.coordinator.owner());
    }

    @Test
    public void synchronousBusyDuringChainedStartDefersRetryWithoutRecursion() {
        Fixture f = new Fixture(30);
        f.platform.synchronousErrorCreate = 2;
        f.platform.synchronousError = SystemSpeechRecognizerController.PlatformError.BUSY;
        f.controller.start();
        f.platform.callback.onFinalResult("첫 문장");

        f.platform.runNextPosted();

        assertEquals(2, f.platform.standardCreates);
        assertEquals(2, f.platform.totalStarts());
        assertEquals(AudioCaptureOwnership.Owner.STT, f.coordinator.owner());
        assertEquals(1, f.platform.postedRestarts.size());
        assertFalse(f.listener.types().contains(
                SystemSpeechRecognizerController.OutcomeType.ERROR));

        f.platform.runNextPosted();

        assertEquals(3, f.platform.standardCreates);
        assertEquals(3, f.platform.totalStarts());
        assertEquals(AudioCaptureOwnership.Owner.STT, f.coordinator.owner());
        assertEquals(SpeechShareStateMachine.State.STT_LISTENING,
                f.coordinator.speechState());
    }

    @Test
    public void chainedBusyRetriesAreBoundedAndFourthBusyFailsClosed() {
        Fixture f = new Fixture(30);
        f.controller.start();
        f.platform.callback.onFinalResult("첫 문장");
        f.platform.runNextPosted();

        for (int retry = 0; retry < 3; retry++) {
            f.platform.callback.onError(SystemSpeechRecognizerController.PlatformError.BUSY);
            assertEquals(1, f.platform.postedRestarts.size());
            assertEquals(AudioCaptureOwnership.Owner.STT, f.coordinator.owner());
            f.platform.runNextPosted();
        }

        f.platform.callback.onError(SystemSpeechRecognizerController.PlatformError.BUSY);

        assertEquals(5, f.platform.standardCreates);
        assertEquals(0, f.platform.postedRestarts.size());
        assertEquals(AudioCaptureOwnership.Owner.NONE, f.coordinator.owner());
        assertEquals(SpeechShareStateMachine.State.STT_FAILED,
                f.coordinator.speechState());
        assertEquals(SystemSpeechRecognizerController.OutcomeType.ERROR,
                f.listener.last().type());
        assertEquals(SystemSpeechRecognizerController.ErrorKind.BUSY,
                f.listener.last().error());
    }

    @Test
    public void restartSchedulingFailureReleasesLeaseAndFailsClosed() {
        Fixture f = new Fixture(30);
        f.platform.throwOnPostDelayed = true;
        f.controller.start();

        f.platform.callback.onFinalResult("첫 문장");

        assertEquals(1, f.platform.standardCreates);
        assertEquals(1, f.platform.totalDestroys());
        assertEquals(0, f.platform.postedRestarts.size());
        assertEquals(AudioCaptureOwnership.Owner.NONE, f.coordinator.owner());
        assertEquals(SpeechShareStateMachine.State.STT_FAILED,
                f.coordinator.speechState());
        assertEquals(SystemSpeechRecognizerController.OutcomeType.ERROR,
                f.listener.last().type());
        assertEquals(SystemSpeechRecognizerController.ErrorKind.START_FAILED,
                f.listener.last().error());
    }

    @Test
    public void cancelOrDestroyWhileRestartPendingMakesPostedTaskInert() {
        for (boolean destroy : List.of(false, true)) {
            Fixture f = new Fixture(30);
            f.controller.start();
            f.platform.callback.onFinalResult("첫 문장");
            assertEquals(1, f.platform.postedRestarts.size());

            if (destroy) f.controller.destroy();
            else f.controller.cancel();
            f.platform.runNextPosted();

            assertEquals(1, f.platform.standardCreates);
            assertEquals(1, f.platform.totalStarts());
            assertEquals(1, f.platform.totalDestroys());
            assertEquals(AudioCaptureOwnership.Owner.NONE, f.coordinator.owner());
            assertNotEquals(SpeechShareStateMachine.State.STT_REVIEW,
                    f.coordinator.speechState());
        }
    }

    @Test
    public void fatalBusyAndNetworkErrorsMapAtPublicSeam() {
        assertErrorMapping(SystemSpeechRecognizerController.PlatformError.BUSY,
                SystemSpeechRecognizerController.ErrorKind.BUSY);
        assertErrorMapping(SystemSpeechRecognizerController.PlatformError.NETWORK,
                SystemSpeechRecognizerController.ErrorKind.NETWORK);
    }

    @Test
    public void partialsIncludeCommittedSegmentsAndPreserveIntentionalRepeatedWords() {
        Fixture f = new Fixture(30);
        f.controller.start();
        SystemSpeechRecognizerController.Callback first = f.platform.callback;
        first.onFinalResult("아주");
        f.platform.runNextPosted();
        SystemSpeechRecognizerController.Callback second = f.platform.callback;

        second.onPartialResult("아주 좋아");
        assertEquals("아주 아주 좋아", f.listener.last().text());
        second.onFinalResult("아주 좋아");

        assertEquals("아주 아주 좋아", f.listener.outcomes.stream()
                .filter(outcome -> outcome.type()
                        == SystemSpeechRecognizerController.OutcomeType.PARTIAL_RESULT)
                .reduce((left, right) -> right).orElseThrow().text());
        assertEquals(AudioCaptureOwnership.Owner.STT, f.coordinator.owner());
        assertFalse(f.listener.types().contains(
                SystemSpeechRecognizerController.OutcomeType.FINAL_RESULT));
    }

    @Test
    public void noMatchAndTimeoutChainBeforeStopButReviewAccumulatedTextAfterStop() {
        for (SystemSpeechRecognizerController.PlatformError error : List.of(
                SystemSpeechRecognizerController.PlatformError.NO_MATCH,
                SystemSpeechRecognizerController.PlatformError.TIMEOUT)) {
            Fixture f = new Fixture(30);
            f.controller.start();
            f.platform.callback.onFinalResult("보존할 문장");
            f.platform.runNextPosted();
            SystemSpeechRecognizerController.Callback recoverable = f.platform.callback;

            recoverable.onError(error);
            f.platform.runNextPosted();
            assertEquals(AudioCaptureOwnership.Owner.STT, f.coordinator.owner());
            assertNotEquals(recoverable, f.platform.callback);

            f.controller.stopListening();
            f.platform.callback.onError(error);

            assertEquals(SpeechShareStateMachine.State.STT_REVIEW, f.coordinator.speechState());
            assertEquals("보존할 문장", f.listener.last().text());
            assertEquals(SystemSpeechRecognizerController.OutcomeType.FINAL_RESULT,
                    f.listener.last().type());
        }
    }

    @Test
    public void stopErrorPreservesVisibleCurrentPartialInFinalReview() {
        for (SystemSpeechRecognizerController.PlatformError error
                : SystemSpeechRecognizerController.PlatformError.values()) {
            Fixture f = new Fixture(30);
            f.controller.start();
            f.platform.callback.onFinalResult("이전 구간");
            f.platform.runNextPosted();
            f.platform.callback.onPartialResult("현재 부분");

            f.controller.stopListening();
            f.platform.callback.onError(error);

            assertEquals(error.name(), SpeechShareStateMachine.State.STT_REVIEW,
                    f.coordinator.speechState());
            assertEquals(error.name(), "이전 구간 현재 부분", f.listener.last().text());
            assertEquals(error.name(), SystemSpeechRecognizerController.OutcomeType.FINAL_RESULT,
                    f.listener.last().type());
            assertEquals(error.name(), AudioCaptureOwnership.Owner.NONE, f.coordinator.owner());
        }
    }

    @Test
    public void stopBlankFinalPreservesVisibleCurrentPartialInFinalReview() {
        for (String blankFinal : java.util.Arrays.asList(null, "  \n")) {
            Fixture f = new Fixture(30);
            f.controller.start();
            f.platform.callback.onFinalResult("이전 구간");
            f.platform.runNextPosted();
            f.platform.callback.onPartialResult("현재 부분");

            f.controller.stopListening();
            f.platform.callback.onFinalResult(blankFinal);

            assertEquals(SpeechShareStateMachine.State.STT_REVIEW,
                    f.coordinator.speechState());
            assertEquals("이전 구간 현재 부분", f.listener.last().text());
            assertEquals(SystemSpeechRecognizerController.OutcomeType.FINAL_RESULT,
                    f.listener.last().type());
        }
    }

    @Test
    public void blankStopEntersEditableKeyboardFallbackAndNeverProducesFinalOrShare() {
        Fixture f = new Fixture(30);
        f.controller.start();

        f.controller.stopListening();
        f.platform.callback.onError(SystemSpeechRecognizerController.PlatformError.NO_MATCH);

        assertEquals(SpeechShareStateMachine.State.STT_FAILED, f.coordinator.speechState());
        assertEquals(SystemSpeechRecognizerController.OutcomeType.ERROR, f.listener.last().type());
        assertEquals(SystemSpeechRecognizerController.ErrorKind.NO_MATCH, f.listener.last().error());
        assertFalse(f.listener.types().contains(
                SystemSpeechRecognizerController.OutcomeType.FINAL_RESULT));
        assertEquals(AudioCaptureOwnership.Owner.NONE, f.coordinator.owner());
    }

    @Test
    public void staleAndSynchronousCallbacksFromPriorCycleFailClosed() {
        Fixture f = new Fixture(30);
        f.controller.start();
        SystemSpeechRecognizerController.Callback stale = f.platform.callback;
        stale.onFinalResult("first");
        f.platform.runNextPosted();
        int outcomesAfterChain = f.listener.outcomes.size();

        stale.onFinalResult("duplicate");
        stale.onError(SystemSpeechRecognizerController.PlatformError.NETWORK);

        assertEquals(outcomesAfterChain, f.listener.outcomes.size());
        assertEquals(AudioCaptureOwnership.Owner.STT, f.coordinator.owner());
        assertEquals(2, f.platform.totalStarts());
        assertEquals(1, f.platform.totalDestroys());
    }

    @Test
    public void synchronousTerminalDuringChainedStartFailsClosedWithoutRecursiveStarts() {
        for (boolean synchronousFinal : List.of(true, false)) {
            Fixture f = new Fixture(30);
            if (synchronousFinal) {
                f.platform.synchronousFinalCreate = 2;
            } else {
                f.platform.synchronousErrorCreate = 2;
                f.platform.synchronousError =
                        SystemSpeechRecognizerController.PlatformError.NO_MATCH;
            }
            f.controller.start();

            f.platform.callback.onFinalResult("첫 구간");
            f.platform.runNextPosted();

            assertEquals(2, f.platform.totalStarts());
            assertEquals(2, f.platform.standardCreates);
            assertEquals(2, f.platform.totalDestroys());
            assertEquals(AudioCaptureOwnership.Owner.NONE, f.coordinator.owner());
            assertEquals(SystemSpeechRecognizerController.OutcomeType.ERROR,
                    f.listener.last().type());
            assertEquals(SystemSpeechRecognizerController.ErrorKind.START_FAILED,
                    f.listener.last().error());
        }
    }

    @Test
    public void cancelOrDestroyReenteredFromIntermediateFinalNeverStartsFreshCycle() {
        for (boolean destroy : List.of(false, true)) {
            AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
            FakePlatform platform = new FakePlatform(30);
            SystemSpeechRecognizerController[] holder = new SystemSpeechRecognizerController[1];
            holder[0] = new SystemSpeechRecognizerController(coordinator, platform, outcome -> {
                if (outcome.type()
                        == SystemSpeechRecognizerController.OutcomeType.PARTIAL_RESULT) {
                    if (destroy) holder[0].destroy();
                    else holder[0].cancel();
                }
            });
            holder[0].start();

            platform.callback.onProcessing();
            platform.callback.onFinalResult("중간 결과");

            assertEquals(1, platform.standardCreates);
            assertEquals(1, platform.totalStarts());
            assertEquals(1, platform.totalDestroys());
            assertEquals(AudioCaptureOwnership.Owner.NONE, coordinator.owner());
            assertNotEquals(SpeechShareStateMachine.State.STT_REVIEW, coordinator.speechState());
        }
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
    public void cancelAndDestroyInvalidateSynchronousPlatformCancelCallbacksFirst() {
        for (boolean destroy : List.of(false, true)) {
            for (boolean finalCallback : List.of(false, true)) {
                Fixture f = new Fixture(30);
                f.controller.start();
                SystemSpeechRecognizerController.Callback callback = f.platform.callback;
                f.platform.recognizer.onCancel = () -> {
                    if (finalCallback) callback.onFinalResult("취소 뒤 동기 결과");
                    else callback.onError(SystemSpeechRecognizerController.PlatformError.NETWORK);
                };

                if (destroy) f.controller.destroy();
                else f.controller.cancel();

                assertFalse(f.listener.types().contains(
                        SystemSpeechRecognizerController.OutcomeType.FINAL_RESULT));
                assertFalse(f.listener.types().contains(
                        SystemSpeechRecognizerController.OutcomeType.ERROR));
                assertEquals(1, f.platform.totalDestroys());
                assertEquals(AudioCaptureOwnership.Owner.NONE, f.coordinator.owner());
                assertEquals(destroy ? SpeechShareStateMachine.State.IDLE
                                : SpeechShareStateMachine.State.STT_CANCELED,
                        f.coordinator.speechState());
            }
        }
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
        stale.onError(SystemSpeechRecognizerController.PlatformError.NETWORK);

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
        second.stopListening();
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
        int stops;
        int cancels;
        int destroys;
        boolean throwOnStart;
        boolean throwOnDestroy;
        Runnable onStart;
        Runnable onCancel;
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
            if (onCancel != null) onCancel.run();
        }

        @Override public void stopListening() {
            stops++;
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
        boolean throwOnPostDelayed;
        boolean deferSupport;
        int standardCreates;
        int onDeviceAvailabilityChecks;
        int onDeviceCreates;
        int supportChecks;
        int modelDownloads;
        int synchronousFinalCreate = -1;
        int synchronousErrorCreate = -1;
        SystemSpeechRecognizerController.PlatformError synchronousError;
        FakeRecognizer recognizer = new FakeRecognizer();
        boolean recognizerCreated;
        final List<FakeRecognizer> recognizers = new ArrayList<>();
        final List<SystemSpeechRecognizerController.Callback> callbacks = new ArrayList<>();
        final List<Runnable> postedRestarts = new ArrayList<>();
        SystemSpeechRecognizerController.Callback callback;
        SystemSpeechRecognizerController.SupportCallback supportCallback;

        FakePlatform(int apiLevel) {
            this.apiLevel = apiLevel;
        }

        void replaceRecognizer() {
            recognizer = new FakeRecognizer();
            recognizerCreated = false;
        }

        int totalStarts() {
            return recognizers.stream().mapToInt(value -> value.starts).sum();
        }

        int totalStops() {
            return recognizers.stream().mapToInt(value -> value.stops).sum();
        }

        int totalDestroys() {
            return recognizers.stream().mapToInt(value -> value.destroys).sum();
        }

        void runNextPosted() {
            if (postedRestarts.isEmpty()) return;
            postedRestarts.remove(0).run();
        }

        @Override public void postDelayed(Runnable task, long delayMillis) {
            if (throwOnPostDelayed) throw new IllegalStateException("post failed");
            postedRestarts.add(task);
        }

        private FakeRecognizer createFreshRecognizer(
                SystemSpeechRecognizerController.Callback callback) {
            if (recognizerCreated) recognizer = new FakeRecognizer();
            recognizerCreated = true;
            recognizers.add(recognizer);
            callbacks.add(callback);
            this.callback = callback;
            int createNumber = recognizers.size();
            if (createNumber == synchronousFinalCreate) {
                recognizer.onStart = () -> callback.onFinalResult("동기 결과");
            } else if (createNumber == synchronousErrorCreate) {
                recognizer.onStart = () -> callback.onError(synchronousError);
            }
            return recognizer;
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
            return createFreshRecognizer(callback);
        }

        @Override public SystemSpeechRecognizerController.Recognizer createOnDeviceRecognizer(
                SystemSpeechRecognizerController.Callback callback) {
            touched = true;
            onDeviceCreates++;
            if (throwOnOnDeviceCreate) throw new IllegalStateException("on-device failed");
            return createFreshRecognizer(callback);
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

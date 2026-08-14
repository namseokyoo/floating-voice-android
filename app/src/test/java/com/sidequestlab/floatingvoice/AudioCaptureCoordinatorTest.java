package com.sidequestlab.floatingvoice;

import com.sidequestlab.floatingvoice.core.AudioCaptureOwnership;
import com.sidequestlab.floatingvoice.core.SpeechShareEvent;
import com.sidequestlab.floatingvoice.core.SpeechShareStateMachine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class AudioCaptureCoordinatorTest {
    @Test
    public void activeRecordingRejectsSpeechAndActiveSpeechRejectsRecording() {
        AudioCaptureCoordinator recording = new AudioCaptureCoordinator();
        AudioCaptureOwnership.Lease recordingLease = recording.startRecording().orElseThrow();
        assertTrue(recording.startSpeech().isEmpty());
        assertEquals(AudioCaptureOwnership.Owner.RECORDING, recording.owner());
        assertTrue(recording.finishRecording(recordingLease, true));

        AudioCaptureCoordinator speech = new AudioCaptureCoordinator();
        SpeechShareStateMachine.Transition started = speech.startSpeech().orElseThrow();
        assertEquals(SpeechShareStateMachine.State.STT_CHECKING_SUPPORT, started.nextState());
        assertTrue(speech.startRecording().isEmpty());
        assertEquals(AudioCaptureOwnership.Owner.STT, speech.owner());
    }

    @Test
    public void failedRecorderReleaseKeepsOwnershipAndBlocksSpeech() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        AudioCaptureOwnership.Lease recording = coordinator.startRecording().orElseThrow();

        assertFalse(coordinator.finishRecording(recording, false));
        assertEquals(AudioCaptureOwnership.Owner.RECORDING, coordinator.owner());
        assertTrue(coordinator.startSpeech().isEmpty());
    }

    @Test
    public void terminalSpeechEventKeepsMicUntilRecognizerDestroyIsAcknowledged() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        coordinator.startSpeech().orElseThrow();
        long firstSpeechGeneration = coordinator.speechGeneration();
        long firstOwnershipGeneration = coordinator.ownershipGeneration();
        coordinator.acceptSpeech(SpeechShareEvent.supportAvailable(firstSpeechGeneration));

        coordinator.acceptSpeech(SpeechShareEvent.error(firstSpeechGeneration));
        assertEquals(AudioCaptureOwnership.Owner.STT, coordinator.owner());
        assertTrue(coordinator.startRecording().isEmpty());
        assertFalse(coordinator.finishSpeechCapture(firstSpeechGeneration + 1L));
        assertEquals(AudioCaptureOwnership.Owner.STT, coordinator.owner());
        assertTrue(coordinator.finishSpeechCapture(firstSpeechGeneration));
        assertEquals(AudioCaptureOwnership.Owner.NONE, coordinator.owner());
        assertFalse(coordinator.finishSpeechCapture(firstSpeechGeneration));

        SpeechShareStateMachine.Transition retry = coordinator.retrySpeech().orElseThrow();
        assertEquals(SpeechShareStateMachine.State.STT_CHECKING_SUPPORT, retry.nextState());
        assertNotEquals(firstSpeechGeneration, coordinator.speechGeneration());
        assertNotEquals(firstOwnershipGeneration, coordinator.ownershipGeneration());
        assertEquals(AudioCaptureOwnership.Owner.STT, coordinator.owner());
        assertTrue(coordinator.acceptSpeech(
                SpeechShareEvent.finalResult(firstSpeechGeneration, "stale")).effects().isEmpty());
        assertEquals(SpeechShareStateMachine.State.STT_CHECKING_SUPPORT,
                coordinator.speechState());
    }

    @Test
    public void speechStartAndRetryCannotBypassOwnershipEntryPoints() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();

        SpeechShareStateMachine.Transition directStart = coordinator.acceptSpeech(
                SpeechShareEvent.start());
        assertEquals(SpeechShareStateMachine.State.IDLE, directStart.nextState());
        assertEquals(AudioCaptureOwnership.Owner.NONE, coordinator.owner());

        coordinator.startSpeech().orElseThrow();
        long generation = coordinator.speechGeneration();
        coordinator.acceptSpeech(SpeechShareEvent.error(generation));
        assertTrue(coordinator.finishSpeechCapture(generation));
        SpeechShareStateMachine.Transition directRetry = coordinator.acceptSpeech(
                SpeechShareEvent.retryRequest());
        assertEquals(SpeechShareStateMachine.State.STT_FAILED, directRetry.nextState());
        assertEquals(AudioCaptureOwnership.Owner.NONE, coordinator.owner());
    }

    @Test
    public void teardownReleasesOwnershipAndMakesStaleReleaseInert() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        AudioCaptureOwnership.Lease lease = coordinator.startRecording().orElseThrow();

        coordinator.teardown();

        assertEquals(AudioCaptureOwnership.Owner.NONE, coordinator.owner());
        assertFalse(coordinator.finishRecording(lease, true));
        assertTrue(coordinator.startRecording().isEmpty());
        assertTrue(coordinator.startSpeech().isEmpty());
    }

    @Test
    public void oneRecorderOwnerCanReleaseWithoutTearingDownTheAppLifetimeCoordinator() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        AudioCaptureOwnership.Lease recording = coordinator.startRecording().orElseThrow();

        assertTrue(coordinator.finishRecording(recording, true));

        SpeechShareStateMachine.Transition speech = coordinator.startSpeech().orElseThrow();
        assertEquals(SpeechShareStateMachine.State.STT_CHECKING_SUPPORT, speech.nextState());
        assertEquals(AudioCaptureOwnership.Owner.STT, coordinator.owner());
    }

    @Test
    public void activeSpeechCannotBeReleasedBeforeARecognizerTerminalEvent() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        coordinator.startSpeech().orElseThrow();
        long generation = coordinator.speechGeneration();

        assertFalse(coordinator.finishSpeechCapture(generation));
        assertEquals(AudioCaptureOwnership.Owner.STT, coordinator.owner());
        assertTrue(coordinator.startRecording().isEmpty());
    }

    @Test
    public void interactionCompletionIsGenerationScoped() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        coordinator.startSpeech().orElseThrow();
        long first = coordinator.speechGeneration();
        coordinator.acceptSpeech(SpeechShareEvent.error(first));
        assertTrue(coordinator.finishSpeechCapture(first));

        coordinator.retrySpeech().orElseThrow();
        long second = coordinator.speechGeneration();
        coordinator.acceptSpeech(SpeechShareEvent.supportAvailable(second));
        coordinator.acceptSpeech(SpeechShareEvent.finalResult(second, "현재 결과"));
        assertTrue(coordinator.finishSpeechCapture(second));

        assertFalse(coordinator.completeSpeechInteraction(first));
        assertEquals(SpeechShareStateMachine.State.STT_REVIEW, coordinator.speechState());
        assertTrue(coordinator.completeSpeechInteraction(second));
        assertEquals(SpeechShareStateMachine.State.IDLE, coordinator.speechState());
    }

    @Test
    public void twoSuccessfulSpeechInteractionsUseDistinctGenerations() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        coordinator.startSpeech().orElseThrow();
        long first = coordinator.speechGeneration();
        coordinator.acceptSpeech(SpeechShareEvent.supportAvailable(first));
        coordinator.acceptSpeech(SpeechShareEvent.finalResult(first, "첫 결과"));

        assertFalse(coordinator.completeSpeechInteraction(first));
        assertTrue(coordinator.finishSpeechCapture(first));
        coordinator.acceptSpeech(SpeechShareEvent.share());
        assertTrue(coordinator.completeSpeechInteraction(first));

        coordinator.startSpeech().orElseThrow();
        long second = coordinator.speechGeneration();
        assertNotEquals(first, second);
        assertTrue(coordinator.acceptSpeech(
                SpeechShareEvent.finalResult(first, "stale")).effects().isEmpty());
        assertEquals(SpeechShareStateMachine.State.STT_CHECKING_SUPPORT,
                coordinator.speechState());
    }
}

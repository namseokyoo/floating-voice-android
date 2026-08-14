package com.sidequestlab.floatingvoice;

import com.sidequestlab.floatingvoice.core.AudioCaptureOwnership;
import com.sidequestlab.floatingvoice.core.SpeechShareEvent;
import com.sidequestlab.floatingvoice.core.SpeechShareStateMachine;

import java.util.Optional;

/** App-lifetime invariant boundary for recorder and future SpeechRecognizer capture. */
public final class AudioCaptureCoordinator {
    private final AudioCaptureOwnership ownership = new AudioCaptureOwnership();
    private final SpeechShareStateMachine speech = new SpeechShareStateMachine();
    private AudioCaptureOwnership.Lease speechLease;
    private boolean tearingDown;

    public synchronized Optional<AudioCaptureOwnership.Lease> startRecording() {
        if (tearingDown) return Optional.empty();
        return ownership.acquire(AudioCaptureOwnership.Owner.RECORDING);
    }

    public synchronized boolean finishRecording(
            AudioCaptureOwnership.Lease lease, boolean resourceReleased) {
        if (!resourceReleased) return false;
        return ownership.release(lease);
    }

    public synchronized Optional<SpeechShareStateMachine.Transition> startSpeech() {
        if (tearingDown || speech.state() != SpeechShareStateMachine.State.IDLE) {
            return Optional.empty();
        }
        Optional<AudioCaptureOwnership.Lease> lease =
                ownership.acquire(AudioCaptureOwnership.Owner.STT);
        if (lease.isEmpty()) return Optional.empty();
        speechLease = lease.get();
        return Optional.of(speech.accept(SpeechShareEvent.start()));
    }

    public synchronized Optional<SpeechShareStateMachine.Transition> retrySpeech() {
        if (tearingDown || (speech.state() != SpeechShareStateMachine.State.STT_FAILED
                && speech.state() != SpeechShareStateMachine.State.STT_CANCELED
                && speech.state() != SpeechShareStateMachine.State.STT_REVIEW)) {
            return Optional.empty();
        }
        Optional<AudioCaptureOwnership.Lease> lease =
                ownership.acquire(AudioCaptureOwnership.Owner.STT);
        if (lease.isEmpty()) return Optional.empty();
        speechLease = lease.get();
        return Optional.of(speech.accept(SpeechShareEvent.retryRequest()));
    }

    public synchronized SpeechShareStateMachine.Transition acceptSpeech(SpeechShareEvent event) {
        if (event.type() == SpeechShareEvent.Type.TEARDOWN) {
            SpeechShareStateMachine.State previous = speech.state();
            teardown();
            return new SpeechShareStateMachine.Transition(
                    previous, speech.state(), java.util.List.of());
        }
        if (event.type() == SpeechShareEvent.Type.START
                || event.type() == SpeechShareEvent.Type.RETRY
                || event.type() == SpeechShareEvent.Type.COMPLETE) {
            return new SpeechShareStateMachine.Transition(
                    speech.state(), speech.state(), java.util.List.of());
        }
        return speech.accept(event);
    }

    /** Releases the STT lease only after the recognizer resource has actually been destroyed. */
    public synchronized boolean finishSpeechCapture(long expectedSpeechGeneration) {
        if (speechLease == null
                || expectedSpeechGeneration <= 0L
                || expectedSpeechGeneration != speech.generation()
                || !isCaptureTerminal(speech.state())) {
            return false;
        }
        return releaseSpeechLease();
    }

    public synchronized boolean completeSpeechInteraction(long expectedSpeechGeneration) {
        if (expectedSpeechGeneration <= 0L
                || expectedSpeechGeneration != speech.generation()
                || speechLease != null
                || !isInteractionTerminal(speech.state())) return false;
        return speech.accept(SpeechShareEvent.complete(expectedSpeechGeneration)).nextState()
                == SpeechShareStateMachine.State.IDLE;
    }

    public synchronized void teardown() {
        if (tearingDown) return;
        tearingDown = true;
        speech.accept(SpeechShareEvent.teardown());
        speechLease = null;
        ownership.releaseAll();
    }

    public synchronized AudioCaptureOwnership.Owner owner() {
        return ownership.owner();
    }

    public synchronized long ownershipGeneration() {
        return ownership.generation();
    }

    public synchronized SpeechShareStateMachine.State speechState() {
        return speech.state();
    }

    public synchronized long speechGeneration() {
        return speech.generation();
    }

    private boolean releaseSpeechLease() {
        if (speechLease == null) return false;
        boolean released = ownership.release(speechLease);
        speechLease = null;
        return released;
    }

    private static boolean isCaptureTerminal(SpeechShareStateMachine.State state) {
        return state == SpeechShareStateMachine.State.STT_REVIEW
                || state == SpeechShareStateMachine.State.STT_FAILED
                || state == SpeechShareStateMachine.State.STT_CANCELED
                || state == SpeechShareStateMachine.State.SHARE_CHOOSER_LAUNCHED
                || state == SpeechShareStateMachine.State.TEARING_DOWN;
    }

    private static boolean isInteractionTerminal(SpeechShareStateMachine.State state) {
        return state == SpeechShareStateMachine.State.STT_REVIEW
                || state == SpeechShareStateMachine.State.STT_FAILED
                || state == SpeechShareStateMachine.State.STT_CANCELED
                || state == SpeechShareStateMachine.State.SHARE_CHOOSER_LAUNCHED;
    }
}

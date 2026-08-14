package com.sidequestlab.floatingvoice;

import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Rotation-retained, memory-only owner of one speech review interaction. */
public final class SpeechReviewViewModel extends ViewModel
        implements SystemSpeechRecognizerController.Listener {
    public interface RecognitionPort {
        SystemSpeechRecognizerController.StartResult start();
        void cancel();
        void destroy();
    }

    public interface RecognitionFactory {
        RecognitionPort create(SystemSpeechRecognizerController.Listener listener);
    }

    private final AudioCaptureCoordinator coordinator;
    private final RecognitionPort recognition;
    private final List<SystemSpeechRecognizerController.Outcome> buffered = new ArrayList<>();
    private SpeechReviewSession session;
    private Consumer<SpeechReviewSession.UiState> observer;
    private boolean started;

    public SpeechReviewViewModel(
            AudioCaptureCoordinator coordinator,
            RecognitionFactory recognitionFactory) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        recognition = Objects.requireNonNull(recognitionFactory, "recognitionFactory").create(this);
    }

    public synchronized void startOnce() {
        if (started) {
            publish();
            return;
        }
        started = true;
        recognition.start();
        initializeSessionIfPossible();
        publish();
    }

    public synchronized boolean startKeyboardOnly() {
        if (started) {
            publish();
            return session != null;
        }
        java.util.Optional<com.sidequestlab.floatingvoice.core.SpeechShareStateMachine.Transition>
                startedSpeech = coordinator.startSpeech();
        if (startedSpeech.isEmpty()) return false;
        started = true;
        long generation = coordinator.speechGeneration();
        coordinator.acceptSpeech(com.sidequestlab.floatingvoice.core.SpeechShareEvent
                .supportUnavailable(generation));
        coordinator.finishSpeechCapture(generation);
        initializeSessionIfPossible();
        session.onOutcome(new SystemSpeechRecognizerController.Outcome(
                SystemSpeechRecognizerController.OutcomeType.KEYBOARD_REQUIRED,
                generation, null, null, null));
        publish();
        return true;
    }

    public synchronized void retry() {
        if (session == null || !session.uiState().retryEnabled()) return;
        recognition.start();
        long nextGeneration = coordinator.speechGeneration();
        session.beginGeneration(nextGeneration);
        replayBuffered();
        publish();
    }

    public synchronized void cancel() {
        recognition.cancel();
    }

    public synchronized void close() {
        observer = null;
        recognition.cancel();
        recognition.destroy();
        if (session != null) {
            coordinator.completeSpeechInteraction(session.generation());
        }
    }

    public synchronized boolean hasSession() {
        initializeSessionIfPossible();
        return session != null;
    }

    public synchronized SpeechReviewSession session() {
        initializeSessionIfPossible();
        if (session == null) throw new IllegalStateException("speech session has not started");
        return session;
    }

    public synchronized void setObserver(Consumer<SpeechReviewSession.UiState> observer) {
        this.observer = observer;
        publish();
    }

    public synchronized void clearObserver(Consumer<SpeechReviewSession.UiState> expected) {
        if (observer == expected) observer = null;
    }

    public synchronized void notifyStateChanged() {
        publish();
    }

    @Override public synchronized void onOutcome(
            SystemSpeechRecognizerController.Outcome outcome) {
        if (session == null) {
            buffered.add(outcome);
            initializeSessionIfPossible();
        } else {
            if (outcome.generation() > session.generation()) {
                session.beginGeneration(outcome.generation());
            }
            session.onOutcome(outcome);
        }
        publish();
    }

    @Override protected synchronized void onCleared() {
        close();
        super.onCleared();
    }

    private void initializeSessionIfPossible() {
        if (session != null) return;
        long generation = coordinator.speechGeneration();
        if (generation <= 0L) return;
        session = new SpeechReviewSession(coordinator, generation);
        replayBuffered();
    }

    private void replayBuffered() {
        if (session == null || buffered.isEmpty()) return;
        for (SystemSpeechRecognizerController.Outcome outcome : buffered) {
            session.onOutcome(outcome);
        }
        buffered.clear();
    }

    private void publish() {
        if (observer != null && session != null) observer.accept(session.uiState());
    }
}

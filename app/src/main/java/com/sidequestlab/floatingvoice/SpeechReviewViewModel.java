package com.sidequestlab.floatingvoice;

import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Rotation-retained, memory-only owner of one speech review interaction. */
public final class SpeechReviewViewModel extends ViewModel
        implements SystemSpeechRecognizerController.Listener {
    public enum TelegramFeedback { NONE, REJECTED }
    public record TelegramOutputState(String selectedLocalId,
                                      boolean explicitSelection,
                                      boolean handoffInFlight,
                                      long handoffId) { }
    public interface RecognitionPort {
        SystemSpeechRecognizerController.StartResult start();
        default void stopListening() { }
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
    private String telegramDestinationLocalId;
    private boolean telegramDestinationExplicit;
    private boolean telegramHandoffInFlight;
    private long telegramHandoffId;
    private TelegramFeedback telegramFeedback = TelegramFeedback.NONE;

    public synchronized TelegramOutputState telegramOutputState() {
        return new TelegramOutputState(
                telegramDestinationLocalId, telegramDestinationExplicit,
                telegramHandoffInFlight, telegramHandoffId);
    }

    public synchronized void initializeTelegramDestination(String defaultLocalId) {
        if (telegramDestinationLocalId == null && !telegramDestinationExplicit
                && defaultLocalId != null && !defaultLocalId.isBlank()) {
            telegramDestinationLocalId = defaultLocalId;
        }
    }

    public synchronized void selectTelegramDestination(String localId) {
        if (localId == null || localId.isBlank()) {
            throw new IllegalArgumentException("localId must not be blank");
        }
        telegramDestinationLocalId = localId;
        telegramDestinationExplicit = true;
    }

    public synchronized void invalidateTelegramDestination(String expectedLocalId) {
        if (Objects.equals(telegramDestinationLocalId, expectedLocalId)) {
            telegramDestinationLocalId = null;
        }
    }

    public synchronized boolean beginTelegramHandoff(long handoffId) {
        if (handoffId <= 0L || telegramHandoffInFlight) return false;
        telegramFeedback = TelegramFeedback.NONE;
        telegramHandoffInFlight = true;
        telegramHandoffId = handoffId;
        return true;
    }

    public synchronized boolean matchesTelegramHandoff(long handoffId) {
        return telegramHandoffInFlight && handoffId > 0L
                && telegramHandoffId == handoffId;
    }

    public synchronized void clearTelegramHandoff(long expectedHandoffId) {
        if (!matchesTelegramHandoff(expectedHandoffId)) return;
        telegramHandoffInFlight = false;
        telegramHandoffId = 0L;
    }

    public synchronized boolean rejectTelegramHandoff(long expectedHandoffId) {
        if (!matchesTelegramHandoff(expectedHandoffId)) return false;
        clearTelegramHandoff(expectedHandoffId);
        telegramFeedback = TelegramFeedback.REJECTED;
        return true;
    }

    public synchronized TelegramFeedback telegramFeedback() {
        return telegramFeedback;
    }

    public synchronized void clearTelegramFeedback() {
        telegramFeedback = TelegramFeedback.NONE;
    }

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

    /** Ends dictation into review; unlike cancel(), this preserves recognized text. */
    public synchronized void stopListening() {
        recognition.stopListening();
    }

    public synchronized void close() {
        observer = null;
        recognition.cancel();
        recognition.destroy();
        if (session != null) {
            coordinator.completeSpeechInteraction(session.generation());
        }
        telegramHandoffInFlight = false;
        telegramHandoffId = 0L;
        telegramFeedback = TelegramFeedback.NONE;
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

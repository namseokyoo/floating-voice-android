package com.sidequestlab.floatingvoice;

import com.sidequestlab.floatingvoice.core.SpeechShareEvent;
import com.sidequestlab.floatingvoice.core.SpeechShareStateMachine;

import java.util.Objects;

/** In-memory, generation-scoped review model. It never persists or logs transcript text. */
public final class SpeechReviewSession {
    public enum Stage {
        CHECKING,
        LISTENING,
        PROCESSING,
        EDITING,
        KEYBOARD_FALLBACK,
        SHARE_CONFIRMATION_REQUIRED,
        SHARE_FAILED,
        CANCELED
    }

    public record UiState(
            Stage stage,
            String draft,
            boolean editable,
            boolean shareEnabled,
            boolean retryEnabled,
            SpeechRecognitionSupport support) {
        public UiState {
            Objects.requireNonNull(stage, "stage");
            draft = draft == null ? "" : draft;
        }
    }

    private final AudioCaptureCoordinator coordinator;
    private long generation;
    private String draft = "";
    private Stage stage = Stage.CHECKING;
    private boolean editable;
    private boolean retryEnabled;
    private SpeechRecognitionSupport support;

    public SpeechReviewSession(AudioCaptureCoordinator coordinator, long generation) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        if (generation <= 0L) throw new IllegalArgumentException("generation must be positive");
        this.generation = generation;
    }

    public synchronized void beginGeneration(long nextGeneration) {
        if (nextGeneration <= generation) return;
        generation = nextGeneration;
        draft = "";
        stage = Stage.CHECKING;
        editable = false;
        retryEnabled = false;
        support = null;
    }

    public synchronized void onOutcome(SystemSpeechRecognizerController.Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome.generation() <= 0L || outcome.generation() != generation) return;
        switch (outcome.type()) {
            case SUPPORT_CHANGED -> support = outcome.support();
            case LISTENING -> stage = Stage.LISTENING;
            case PARTIAL_RESULT -> {
                if (outcome.text() != null && !outcome.text().isBlank()) draft = outcome.text();
                stage = Stage.LISTENING;
            }
            case PROCESSING -> stage = Stage.PROCESSING;
            case FINAL_RESULT -> {
                if (outcome.text() != null) draft = outcome.text();
                stage = Stage.EDITING;
                editable = true;
                retryEnabled = true;
            }
            case OWNERSHIP_DENIED, UNAVAILABLE, KEYBOARD_REQUIRED, ERROR, DESTROY_FAILED -> {
                stage = Stage.KEYBOARD_FALLBACK;
                editable = true;
                retryEnabled = true;
            }
            case CANCELED, DESTROYED -> {
                stage = Stage.CANCELED;
                editable = false;
                retryEnabled = false;
            }
        }
    }

    public synchronized void editDraft(String editedDraft) {
        if (!editable) return;
        draft = editedDraft == null ? "" : editedDraft;
    }

    public synchronized UiState uiState() {
        return new UiState(
                stage, draft, editable, editable && !draft.isBlank(), retryEnabled, support);
    }

    /** The only public session operation that may invoke the Android share seam. */
    public synchronized AndroidShareController.Result share(AndroidShareController controller) {
        Objects.requireNonNull(controller, "controller");
        if (generation != coordinator.speechGeneration()) {
            return AndroidShareController.Result.DUPLICATE_IGNORED;
        }
        if (!editable || draft.isBlank()) return AndroidShareController.Result.BLANK_REJECTED;

        SpeechShareStateMachine.State speechState = coordinator.speechState();
        if (speechState == SpeechShareStateMachine.State.STT_FAILED
                || speechState == SpeechShareStateMachine.State.STT_CANCELED) {
            coordinator.acceptSpeech(SpeechShareEvent.keyboardFallback(generation));
        }
        SpeechShareStateMachine.Transition share = coordinator.acceptSpeech(
                SpeechShareEvent.share(generation, draft));
        boolean launchAuthorized = share.effects().stream().anyMatch(effect ->
                effect.type() == SpeechShareStateMachine.EffectType.LAUNCH_SHARE_CHOOSER
                        && draft.equals(effect.text()));
        if (!launchAuthorized) return AndroidShareController.Result.DUPLICATE_IGNORED;

        AndroidShareController.Result result = controller.shareText(draft);
        if (result == AndroidShareController.Result.CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED) {
            stage = Stage.SHARE_CONFIRMATION_REQUIRED;
            editable = false;
            retryEnabled = false;
            return result;
        }

        coordinator.acceptSpeech(SpeechShareEvent.shareLaunchFailed(generation));
        stage = Stage.SHARE_FAILED;
        editable = true;
        retryEnabled = true;
        return result;
    }

    public synchronized void chooserReturned(AndroidShareController controller) {
        controller.chooserReturned();
        coordinator.acceptSpeech(SpeechShareEvent.chooserReturned(generation));
        stage = Stage.EDITING;
        editable = true;
        retryEnabled = true;
    }

    public synchronized long generation() {
        return generation;
    }
}

package com.sidequestlab.floatingvoice;

import com.sidequestlab.floatingvoice.core.SpeechShareEvent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpeechReviewSessionTest {
    @Test
    public void finalCallbackOnlyEnablesEditingAndNeverLaunchesChooser() {
        Fixture f = new Fixture();
        f.finalResult("recognizer final");

        assertEquals(0, f.platform.launches);
        assertTrue(f.session.uiState().editable());
        assertEquals("recognizer final", f.session.uiState().draft());
        assertTrue(f.session.uiState().shareEnabled());
    }

    @Test
    public void editedDraftIsExplicitChooserPayloadNotStaleFinal() {
        Fixture f = new Fixture();
        f.finalResult("stale final");
        f.session.editDraft("사용자 수정본 🎤");

        assertEquals(AndroidShareController.Result.CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED,
                f.session.share(f.shareController));

        assertEquals(1, f.platform.launches);
        assertEquals("사용자 수정본 🎤", f.platform.requests.get(0).text());
    }

    @Test
    public void errorKeyboardFallbackRetainsVisiblePartialWithoutAutoShare() {
        Fixture f = new Fixture();
        f.partial("보이는 부분 문장");
        f.error();

        assertEquals(0, f.platform.launches);
        assertEquals("보이는 부분 문장", f.session.uiState().draft());
        assertTrue(f.session.uiState().editable());
        assertTrue(f.session.uiState().retryEnabled());

        assertEquals(AndroidShareController.Result.CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED,
                f.session.share(f.shareController));
        assertEquals("보이는 부분 문장", f.platform.requests.get(0).text());
    }

    @Test
    public void cancelAndBlankDraftNeverLaunchChooser() {
        Fixture canceled = new Fixture();
        canceled.cancel();
        assertEquals(0, canceled.platform.launches);

        Fixture blank = new Fixture();
        blank.error();
        blank.session.editDraft(" \n");
        assertFalse(blank.session.uiState().shareEnabled());
        assertEquals(AndroidShareController.Result.BLANK_REJECTED,
                blank.session.share(blank.shareController));
        assertEquals(0, blank.platform.launches);
    }

    @Test
    public void launchFailureRollsBackAndExplicitRetryCanOpenChooser() {
        Fixture f = new Fixture();
        f.finalResult("draft");
        f.platform.throwNext = true;

        assertEquals(AndroidShareController.Result.LAUNCH_FAILED,
                f.session.share(f.shareController));
        assertTrue(f.session.uiState().editable());
        assertTrue(f.session.uiState().shareEnabled());

        assertEquals(AndroidShareController.Result.CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED,
                f.session.share(f.shareController));
        assertEquals(2, f.platform.launches);
    }

    @Test
    public void staleGenerationCannotOverwriteRetainedRotationDraft() {
        Fixture f = new Fixture();
        f.finalResult("retained edited draft");
        f.session.editDraft("rotation memory only");

        f.session.onOutcome(outcome(
                SystemSpeechRecognizerController.OutcomeType.FINAL_RESULT,
                f.generation - 1L, "stale", null));

        assertEquals("rotation memory only", f.session.uiState().draft());
        assertEquals(0, f.platform.launches);
    }

    @Test
    public void successfulReviewCanRetryAsNewGenerationWithoutSharing() {
        Fixture f = new Fixture();
        f.finalResult("first final");

        f.session.onOutcome(outcome(
                SystemSpeechRecognizerController.OutcomeType.FINAL_RESULT,
                f.generation, "first final", null));
        f.coordinator.retrySpeech().orElseThrow();
        long retriedGeneration = f.coordinator.speechGeneration();

        assertEquals(f.generation + 1L, retriedGeneration);
        assertEquals(0, f.platform.launches);
    }

    @Test
    public void retryGenerationErrorCannotReusePreviousAttemptDraft() {
        Fixture f = new Fixture();
        f.finalResult("previous attempt text");
        f.coordinator.retrySpeech().orElseThrow();
        long nextGeneration = f.coordinator.speechGeneration();
        f.session.beginGeneration(nextGeneration);
        f.coordinator.acceptSpeech(SpeechShareEvent.supportAvailable(nextGeneration));
        f.coordinator.acceptSpeech(SpeechShareEvent.error(nextGeneration));
        f.coordinator.finishSpeechCapture(nextGeneration);

        f.session.onOutcome(outcome(
                SystemSpeechRecognizerController.OutcomeType.ERROR,
                nextGeneration, null, SystemSpeechRecognizerController.ErrorKind.NO_MATCH));

        assertEquals("", f.session.uiState().draft());
        assertFalse(f.session.uiState().shareEnabled());
        assertTrue(f.session.uiState().editable());
    }

    @Test
    public void staleReviewSessionCannotLaunchChooserForNewGeneration() {
        Fixture old = new Fixture();
        old.finalResult("old draft");
        old.coordinator.completeSpeechInteraction(old.generation);

        old.coordinator.startSpeech().orElseThrow();
        long currentGeneration = old.coordinator.speechGeneration();
        old.coordinator.acceptSpeech(SpeechShareEvent.supportAvailable(currentGeneration));
        old.coordinator.acceptSpeech(SpeechShareEvent.finalResult(
                currentGeneration, "current draft"));
        old.coordinator.finishSpeechCapture(currentGeneration);

        assertEquals(AndroidShareController.Result.DUPLICATE_IGNORED,
                old.session.share(old.shareController));
        assertEquals(0, old.platform.launches);
    }

    private static SystemSpeechRecognizerController.Outcome outcome(
            SystemSpeechRecognizerController.OutcomeType type,
            long generation,
            String text,
            SystemSpeechRecognizerController.ErrorKind error) {
        return new SystemSpeechRecognizerController.Outcome(
                type, generation, text, error, null);
    }

    private static final class Fixture {
        final AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        final long generation;
        final SpeechReviewSession session;
        final FakePlatform platform = new FakePlatform();
        final AndroidShareController shareController =
                new AndroidShareController(platform, "Share text");

        Fixture() {
            coordinator.startSpeech().orElseThrow();
            generation = coordinator.speechGeneration();
            coordinator.acceptSpeech(SpeechShareEvent.supportAvailable(generation));
            session = new SpeechReviewSession(coordinator, generation);
        }

        void partial(String text) {
            coordinator.acceptSpeech(SpeechShareEvent.partialResult(generation, text));
            session.onOutcome(outcome(
                    SystemSpeechRecognizerController.OutcomeType.PARTIAL_RESULT,
                    generation, text, null));
        }

        void finalResult(String text) {
            coordinator.acceptSpeech(SpeechShareEvent.finalResult(generation, text));
            coordinator.finishSpeechCapture(generation);
            session.onOutcome(outcome(
                    SystemSpeechRecognizerController.OutcomeType.FINAL_RESULT,
                    generation, text, null));
        }

        void error() {
            coordinator.acceptSpeech(SpeechShareEvent.error(generation));
            coordinator.finishSpeechCapture(generation);
            session.onOutcome(outcome(
                    SystemSpeechRecognizerController.OutcomeType.ERROR,
                    generation, null, SystemSpeechRecognizerController.ErrorKind.NO_MATCH));
        }

        void cancel() {
            coordinator.acceptSpeech(SpeechShareEvent.cancel(generation));
            coordinator.finishSpeechCapture(generation);
            session.onOutcome(outcome(
                    SystemSpeechRecognizerController.OutcomeType.CANCELED,
                    generation, null, null));
        }
    }

    private static final class FakePlatform implements AndroidShareController.Platform {
        int launches;
        boolean throwNext;
        final List<AndroidShareController.TextShareRequest> requests = new ArrayList<>();

        @Override public AndroidShareController.PlatformResult openTextChooser(
                AndroidShareController.TextShareRequest request) {
            launches++;
            requests.add(request);
            if (throwNext) {
                throwNext = false;
                throw new IllegalStateException("failed");
            }
            return AndroidShareController.PlatformResult.OPENED;
        }
    }
}

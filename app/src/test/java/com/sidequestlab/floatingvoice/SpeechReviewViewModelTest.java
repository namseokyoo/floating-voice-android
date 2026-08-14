package com.sidequestlab.floatingvoice;

import com.sidequestlab.floatingvoice.core.SpeechShareEvent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SpeechReviewViewModelTest {
    @Test
    public void explicitStopDelegatesToRecognitionWithoutCancelingTheSession() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        FakeRecognitionFactory factory = new FakeRecognitionFactory(coordinator);
        SpeechReviewViewModel model = new SpeechReviewViewModel(coordinator, factory);
        model.startOnce();

        model.stopListening();

        assertEquals(1, factory.port.stops);
        assertEquals(0, factory.port.cancels);
    }

    @Test
    public void recreatedActivityStartRequestDoesNotDuplicateRecognitionAndDraftStaysInMemory() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        FakeRecognitionFactory factory = new FakeRecognitionFactory(coordinator);
        SpeechReviewViewModel model = new SpeechReviewViewModel(coordinator, factory);

        model.startOnce();
        long generation = coordinator.speechGeneration();
        coordinator.acceptSpeech(SpeechShareEvent.finalResult(generation, "final"));
        coordinator.finishSpeechCapture(generation);
        factory.listener.onOutcome(new SystemSpeechRecognizerController.Outcome(
                SystemSpeechRecognizerController.OutcomeType.FINAL_RESULT,
                generation, "final", null, null));
        model.session().editDraft("rotation edit");

        model.startOnce();

        assertEquals(1, factory.port.starts);
        assertEquals("rotation edit", model.session().uiState().draft());
    }

    @Test
    public void rotationRetainsExplicitTelegramDestinationAndInFlightHandoffIdentity() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        SpeechReviewViewModel model = new SpeechReviewViewModel(
                coordinator, new FakeRecognitionFactory(coordinator));
        model.initializeTelegramDestination("default-a");
        model.selectTelegramDestination("explicit-b");

        assertEquals(true, model.beginTelegramHandoff(42L));
        SpeechReviewViewModel.TelegramOutputState retained = model.telegramOutputState();

        assertEquals("explicit-b", retained.selectedLocalId());
        assertEquals(true, retained.explicitSelection());
        assertEquals(true, retained.handoffInFlight());
        assertEquals(42L, retained.handoffId());
        assertEquals(true, model.matchesTelegramHandoff(42L));
    }

    @Test
    public void rejectedTelegramFeedbackSurvivesRepeatedRenderStatePublication() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        SpeechReviewViewModel model = new SpeechReviewViewModel(
                coordinator, new FakeRecognitionFactory(coordinator));
        model.initializeTelegramDestination("default-a");
        assertEquals(true, model.beginTelegramHandoff(42L));

        assertEquals(true, model.rejectTelegramHandoff(42L));
        model.notifyStateChanged();

        assertEquals(false, model.telegramOutputState().handoffInFlight());
        assertEquals(SpeechReviewViewModel.TelegramFeedback.REJECTED,
                model.telegramFeedback());
    }

    @Test
    public void staleOrderedRejectionCannotAlterCurrentHandoffOrFeedback() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        SpeechReviewViewModel model = new SpeechReviewViewModel(
                coordinator, new FakeRecognitionFactory(coordinator));
        assertEquals(true, model.beginTelegramHandoff(41L));
        model.clearTelegramHandoff(41L);
        assertEquals(true, model.beginTelegramHandoff(42L));

        assertEquals(false, model.rejectTelegramHandoff(41L));

        assertEquals(true, model.matchesTelegramHandoff(42L));
        assertEquals(SpeechReviewViewModel.TelegramFeedback.NONE,
                model.telegramFeedback());
    }

    @Test
    public void synchronousRetryOutcomeAdvancesSessionBeforeApplyingNewGeneration() {
        AudioCaptureCoordinator coordinator = new AudioCaptureCoordinator();
        FakeRecognitionFactory factory = new FakeRecognitionFactory(coordinator);
        SpeechReviewViewModel model = new SpeechReviewViewModel(coordinator, factory);
        factory.emitListeningOnStart = true;

        model.startOnce();
        long firstGeneration = coordinator.speechGeneration();
        coordinator.acceptSpeech(SpeechShareEvent.error(firstGeneration));
        coordinator.finishSpeechCapture(firstGeneration);
        factory.listener.onOutcome(new SystemSpeechRecognizerController.Outcome(
                SystemSpeechRecognizerController.OutcomeType.ERROR,
                firstGeneration, null,
                SystemSpeechRecognizerController.ErrorKind.NO_MATCH, null));

        model.retry();

        assertEquals(firstGeneration + 1L, model.session().generation());
        assertEquals(SpeechReviewSession.Stage.LISTENING,
                model.session().uiState().stage());
        assertEquals(2, factory.port.starts);
    }

    private static final class FakeRecognitionFactory
            implements SpeechReviewViewModel.RecognitionFactory {
        final AudioCaptureCoordinator coordinator;
        final FakeRecognitionPort port = new FakeRecognitionPort();
        SystemSpeechRecognizerController.Listener listener;
        boolean emitListeningOnStart;

        FakeRecognitionFactory(AudioCaptureCoordinator coordinator) {
            this.coordinator = coordinator;
        }

        @Override public SpeechReviewViewModel.RecognitionPort create(
                SystemSpeechRecognizerController.Listener listener) {
            this.listener = listener;
            port.onStart = () -> {
                if (coordinator.speechState()
                        == com.sidequestlab.floatingvoice.core.SpeechShareStateMachine.State.IDLE) {
                    coordinator.startSpeech().orElseThrow();
                } else {
                    coordinator.retrySpeech().orElseThrow();
                }
                long generation = coordinator.speechGeneration();
                coordinator.acceptSpeech(SpeechShareEvent.supportAvailable(generation));
                if (emitListeningOnStart) {
                    listener.onOutcome(new SystemSpeechRecognizerController.Outcome(
                            SystemSpeechRecognizerController.OutcomeType.LISTENING,
                            generation, null, null, null));
                }
            };
            return port;
        }
    }

    private static final class FakeRecognitionPort
            implements SpeechReviewViewModel.RecognitionPort {
        int starts;
        int stops;
        int cancels;
        Runnable onStart;

        @Override public SystemSpeechRecognizerController.StartResult start() {
            starts++;
            onStart.run();
            return SystemSpeechRecognizerController.StartResult.STARTED;
        }

        @Override public void stopListening() { stops++; }
        @Override public void cancel() { cancels++; }
        @Override public void destroy() { }
    }
}

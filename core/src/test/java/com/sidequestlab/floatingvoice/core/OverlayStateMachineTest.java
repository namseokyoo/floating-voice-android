package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OverlayStateMachineTest {
    @Test
    void idleTapStartsVoiceExactlyOnce() {
        OverlayStateMachine machine = new OverlayStateMachine();

        OverlayStateMachine.Transition transition = machine.accept(OverlayEvent.TAP);

        assertEquals(OverlayStateMachine.State.VOICE_STARTING, transition.nextState());
        assertEquals(List.of(OverlayStateMachine.Effect.START_VOICE), transition.effects());
        assertEquals(OverlayStateMachine.State.VOICE_STARTING, machine.state());

        OverlayStateMachine.Transition duplicate = machine.accept(OverlayEvent.TAP);
        assertEquals(OverlayStateMachine.State.VOICE_STARTING, duplicate.nextState());
        assertEquals(List.of(), duplicate.effects());
    }

    @Test
    void recordingBeginsOnlyAfterRecorderStartSucceeds() {
        OverlayStateMachine machine = new OverlayStateMachine();
        machine.accept(OverlayEvent.TAP);

        OverlayStateMachine.Transition transition = machine.accept(OverlayEvent.VOICE_START_SUCCEEDED);

        assertEquals(OverlayStateMachine.State.RECORDING, transition.nextState());
        assertEquals(List.of(), transition.effects());
    }

    @Test
    void recorderStartFailureReturnsIdleWithoutSending() {
        OverlayStateMachine machine = new OverlayStateMachine();
        machine.accept(OverlayEvent.TAP);

        OverlayStateMachine.Transition transition = machine.accept(OverlayEvent.VOICE_START_FAILED);

        assertEquals(OverlayStateMachine.State.IDLE, transition.nextState());
        assertEquals(List.of(), transition.effects());
    }

    @Test
    void longPressOpensMenuWithoutStartingRecording() {
        OverlayStateMachine machine = new OverlayStateMachine();

        OverlayStateMachine.Transition transition = machine.accept(OverlayEvent.LONG_PRESS);

        assertEquals(OverlayStateMachine.State.MENU_OPEN, transition.nextState());
        assertEquals(List.of(OverlayStateMachine.Effect.SHOW_MENU), transition.effects());
    }

    @Test
    void explicitLocalArchiveActionStartsExactlyOneLocalRecording() {
        OverlayStateMachine machine = new OverlayStateMachine();
        machine.accept(OverlayEvent.LONG_PRESS);

        OverlayStateMachine.Transition transition =
                machine.accept(OverlayEvent.START_LOCAL_RECORDING);

        assertEquals(OverlayStateMachine.State.VOICE_STARTING, transition.nextState());
        assertEquals(List.of(
                OverlayStateMachine.Effect.HIDE_MENU,
                OverlayStateMachine.Effect.START_LOCAL_VOICE), transition.effects());
        assertEquals(List.of(), machine.accept(OverlayEvent.START_LOCAL_RECORDING).effects());
    }

    @Test
    void gestureCancelClosesAnOpenMenu() {
        OverlayStateMachine machine = new OverlayStateMachine();
        machine.accept(OverlayEvent.LONG_PRESS);

        OverlayStateMachine.Transition transition = machine.accept(OverlayEvent.GESTURE_CANCELED);

        assertEquals(OverlayStateMachine.State.IDLE, transition.nextState());
        assertEquals(List.of(OverlayStateMachine.Effect.HIDE_MENU), transition.effects());
    }

    @Test
    void illegalEventDoesNotChangeStateOrProduceEffects() {
        OverlayStateMachine machine = new OverlayStateMachine();

        OverlayStateMachine.Transition transition = machine.accept(OverlayEvent.VOICE_STOP_SUCCEEDED);

        assertEquals(OverlayStateMachine.State.IDLE, transition.nextState());
        assertEquals(List.of(), transition.effects());
    }

    @Test
    void stopEmitsSendOnlyAfterRecorderStopAndReleaseSucceed() {
        OverlayStateMachine machine = recordingMachine();

        OverlayStateMachine.Transition requested = machine.accept(OverlayEvent.TAP);
        assertEquals(OverlayStateMachine.State.VOICE_STOPPING, requested.nextState());
        assertEquals(List.of(OverlayStateMachine.Effect.STOP_VOICE), requested.effects());

        OverlayStateMachine.Transition succeeded = machine.accept(OverlayEvent.VOICE_STOP_SUCCEEDED);
        assertEquals(OverlayStateMachine.State.VOICE_QUEUEING, succeeded.nextState());
        assertEquals(List.of(OverlayStateMachine.Effect.SEND_VOICE), succeeded.effects());

        OverlayStateMachine.Transition duplicate = machine.accept(OverlayEvent.VOICE_STOP_SUCCEEDED);
        assertEquals(OverlayStateMachine.State.VOICE_QUEUEING, duplicate.nextState());
        assertEquals(List.of(), duplicate.effects());
    }

    @Test
    void recorderStopFailureReturnsIdleWithoutSending() {
        OverlayStateMachine machine = recordingMachine();
        machine.accept(OverlayEvent.TAP);

        OverlayStateMachine.Transition failed = machine.accept(OverlayEvent.VOICE_STOP_FAILED);

        assertEquals(OverlayStateMachine.State.IDLE, failed.nextState());
        assertEquals(List.of(), failed.effects());
    }

    @Test
    void firstStopOrCancelTerminalRequestWins() {
        OverlayStateMachine stopping = recordingMachine();
        stopping.accept(OverlayEvent.TAP);
        OverlayStateMachine.Transition lateCancel = stopping.accept(OverlayEvent.CANCEL_VOICE_REQUESTED);
        assertEquals(OverlayStateMachine.State.VOICE_STOPPING, lateCancel.nextState());
        assertEquals(List.of(), lateCancel.effects());

        OverlayStateMachine canceling = recordingMachine();
        OverlayStateMachine.Transition cancel = canceling.accept(OverlayEvent.CANCEL_VOICE_REQUESTED);
        assertEquals(OverlayStateMachine.State.VOICE_CANCELING, cancel.nextState());
        assertEquals(List.of(OverlayStateMachine.Effect.CANCEL_VOICE), cancel.effects());
        OverlayStateMachine.Transition lateStop = canceling.accept(OverlayEvent.TAP);
        assertEquals(OverlayStateMachine.State.VOICE_CANCELING, lateStop.nextState());
        assertEquals(List.of(), lateStop.effects());
    }

    @Test
    void voiceQueueProgressesToPendingAndAllowsAnotherRecording() {
        OverlayStateMachine machine = recordingMachine();
        machine.accept(OverlayEvent.TAP);
        machine.accept(OverlayEvent.VOICE_STOP_SUCCEEDED);

        OverlayStateMachine.Transition queued = machine.accept(OverlayEvent.VOICE_QUEUED);
        assertEquals(OverlayStateMachine.State.VOICE_PENDING, queued.nextState());
        assertEquals(List.of(), queued.effects());

        OverlayStateMachine.Transition nextTap = machine.accept(OverlayEvent.TAP);
        assertEquals(OverlayStateMachine.State.VOICE_STARTING, nextTap.nextState());
        assertEquals(List.of(OverlayStateMachine.Effect.START_VOICE), nextTap.effects());
    }

    @Test
    void teardownMakesDuplicateAndStaleCallbacksInert() {
        OverlayStateMachine machine = recordingMachine();
        machine.accept(OverlayEvent.TAP);
        machine.accept(OverlayEvent.TEARDOWN);

        for (OverlayEvent stale : List.of(
                OverlayEvent.VOICE_STOP_SUCCEEDED,
                OverlayEvent.VOICE_STOP_FAILED,
                OverlayEvent.VOICE_QUEUED,
                OverlayEvent.TAP)) {
            OverlayStateMachine.Transition transition = machine.accept(stale);
            assertEquals(OverlayStateMachine.State.TEARING_DOWN, transition.nextState());
            assertEquals(List.of(), transition.effects());
        }
    }

    @Test
    void cancelCompletionReturnsIdleAndDuplicateCallbacksAreIgnored() {
        OverlayStateMachine machine = recordingMachine();
        machine.accept(OverlayEvent.CANCEL_VOICE_REQUESTED);

        OverlayStateMachine.Transition completed = machine.accept(OverlayEvent.VOICE_CANCEL_SUCCEEDED);
        assertEquals(OverlayStateMachine.State.IDLE, completed.nextState());
        assertEquals(List.of(), completed.effects());

        OverlayStateMachine.Transition duplicate = machine.accept(OverlayEvent.VOICE_CANCEL_SUCCEEDED);
        assertEquals(OverlayStateMachine.State.IDLE, duplicate.nextState());
        assertEquals(List.of(), duplicate.effects());
    }

    @Test
    void cancelFailureAlsoReturnsIdleWithoutSending() {
        OverlayStateMachine machine = recordingMachine();
        machine.accept(OverlayEvent.CANCEL_VOICE_REQUESTED);

        OverlayStateMachine.Transition failed = machine.accept(OverlayEvent.VOICE_CANCEL_FAILED);

        assertEquals(OverlayStateMachine.State.IDLE, failed.nextState());
        assertEquals(List.of(), failed.effects());
    }

    @Test
    void rejectedVoiceReturnsIdleWithoutAnotherSend() {
        OverlayStateMachine machine = recordingMachine();
        machine.accept(OverlayEvent.TAP);
        machine.accept(OverlayEvent.VOICE_STOP_SUCCEEDED);

        OverlayStateMachine.Transition rejected = machine.accept(OverlayEvent.VOICE_REJECTED);

        assertEquals(OverlayStateMachine.State.IDLE, rejected.nextState());
        assertEquals(List.of(), rejected.effects());
    }

    @Test
    void completedLocalArchiveReturnsIdleWithoutAbusingQueuedOrRejected() {
        OverlayStateMachine machine = recordingMachine();
        machine.accept(OverlayEvent.TAP);
        OverlayStateMachine.Transition stopped =
                machine.accept(OverlayEvent.LOCAL_VOICE_STOP_SUCCEEDED);
        assertEquals(OverlayStateMachine.State.VOICE_ARCHIVING, stopped.nextState());
        assertEquals(List.of(OverlayStateMachine.Effect.SEND_VOICE), stopped.effects());

        OverlayStateMachine.Transition blockedTap = machine.accept(OverlayEvent.TAP);
        assertEquals(OverlayStateMachine.State.VOICE_ARCHIVING, blockedTap.nextState());
        assertEquals(List.of(), blockedTap.effects());

        OverlayStateMachine.Transition completed =
                machine.accept(OverlayEvent.VOICE_COMPLETED, machine.attemptId());

        assertEquals(OverlayStateMachine.State.IDLE, completed.nextState());
        assertEquals(List.of(), completed.effects());
    }

    @Test
    void textRejectAfterSubmitReturnsIdleBecauseComposerAlreadyClosed() {
        OverlayStateMachine machine = textComposingMachine();

        OverlayStateMachine.Transition submit = machine.accept(OverlayEvent.SUBMIT_TEXT);
        assertEquals(OverlayStateMachine.State.TEXT_QUEUEING, submit.nextState());
        assertEquals(List.of(OverlayStateMachine.Effect.SEND_TEXT), submit.effects());

        OverlayStateMachine.Transition rejected = machine.accept(OverlayEvent.TEXT_REJECTED);
        assertEquals(OverlayStateMachine.State.IDLE, rejected.nextState());
        assertEquals(List.of(), rejected.effects());
    }

    @Test
    void textQueueProgressesToPendingThenIdle() {
        OverlayStateMachine machine = textComposingMachine();
        machine.accept(OverlayEvent.SUBMIT_TEXT);

        OverlayStateMachine.Transition queued = machine.accept(OverlayEvent.TEXT_QUEUED);
        assertEquals(OverlayStateMachine.State.TEXT_PENDING, queued.nextState());
        assertEquals(List.of(), queued.effects());

        OverlayStateMachine.Transition delivered = machine.accept(OverlayEvent.TEXT_DELIVERED);
        assertEquals(OverlayStateMachine.State.IDLE, delivered.nextState());
        assertEquals(List.of(), delivered.effects());
    }

    @Test
    void textDeliveryCanFinishDirectlyFromQueueingWhenCallbacksRace() {
        OverlayStateMachine machine = textComposingMachine();
        machine.accept(OverlayEvent.SUBMIT_TEXT);

        OverlayStateMachine.Transition delivered = machine.accept(OverlayEvent.TEXT_DELIVERED);

        assertEquals(OverlayStateMachine.State.IDLE, delivered.nextState());
        assertEquals(List.of(), delivered.effects());
    }

    @Test
    void visibleBubbleCanStartRecordingWhileTextDeliveryIsPending() {
        OverlayStateMachine machine = textComposingMachine();
        machine.accept(OverlayEvent.SUBMIT_TEXT);
        machine.accept(OverlayEvent.TEXT_QUEUED);
        long textAttempt = machine.attemptId();

        assertTextSendCanYieldToRecording(machine, textAttempt);
    }

    @Test
    void visibleBubbleCanStartRecordingWhileTextIsStillQueueing() {
        OverlayStateMachine machine = textComposingMachine();
        machine.accept(OverlayEvent.SUBMIT_TEXT);
        long textAttempt = machine.attemptId();

        assertTextSendCanYieldToRecording(machine, textAttempt);
    }

    private static void assertTextSendCanYieldToRecording(
            OverlayStateMachine machine, long textAttempt) {
        OverlayStateMachine.Transition tapped = machine.accept(OverlayEvent.TAP);

        assertEquals(OverlayStateMachine.State.VOICE_STARTING, tapped.nextState());
        assertEquals(List.of(OverlayStateMachine.Effect.START_VOICE), tapped.effects());
        assertEquals(textAttempt + 1, machine.attemptId());
        OverlayStateMachine.Transition staleDelivery =
                machine.accept(OverlayEvent.TEXT_DELIVERED, textAttempt);
        assertEquals(OverlayStateMachine.State.VOICE_STARTING, staleDelivery.nextState());
        assertEquals(List.of(), staleDelivery.effects());
    }

    @Test
    void menuPrimaryTapClosesMenuAndComposeHidesMenuBeforeOpeningComposer() {
        OverlayStateMachine closeMachine = new OverlayStateMachine();
        closeMachine.accept(OverlayEvent.LONG_PRESS);
        OverlayStateMachine.Transition closed = closeMachine.accept(OverlayEvent.TAP);
        assertEquals(OverlayStateMachine.State.IDLE, closed.nextState());
        assertEquals(List.of(OverlayStateMachine.Effect.HIDE_MENU), closed.effects());

        OverlayStateMachine composeMachine = new OverlayStateMachine();
        composeMachine.accept(OverlayEvent.LONG_PRESS);
        OverlayStateMachine.Transition compose = composeMachine.accept(OverlayEvent.COMPOSE_TEXT);
        assertEquals(OverlayStateMachine.State.TEXT_COMPOSING, compose.nextState());
        assertEquals(List.of(
                OverlayStateMachine.Effect.HIDE_MENU,
                OverlayStateMachine.Effect.OPEN_TEXT_COMPOSER), compose.effects());
    }

    @Test
    void closingComposerReturnsToIdleWithoutTransportEffects() {
        OverlayStateMachine machine = textComposingMachine();

        OverlayStateMachine.Transition closed = machine.accept(OverlayEvent.CLOSE_COMPOSER);

        assertEquals(OverlayStateMachine.State.IDLE, closed.nextState());
        assertEquals(List.of(), closed.effects());
    }

    @Test
    void callbackAttemptMustMatchCurrentRecordingGeneration() {
        OverlayStateMachine machine = recordingMachine();
        long firstAttempt = machine.attemptId();
        machine.accept(OverlayEvent.TAP);
        machine.accept(OverlayEvent.VOICE_STOP_SUCCEEDED);

        OverlayStateMachine.Transition queued =
                machine.accept(OverlayEvent.VOICE_QUEUED, firstAttempt);
        assertEquals(OverlayStateMachine.State.VOICE_PENDING, queued.nextState());
        assertEquals(List.of(), queued.effects());

        machine.accept(OverlayEvent.TAP);
        long secondAttempt = machine.attemptId();
        OverlayStateMachine.Transition stale =
                machine.accept(OverlayEvent.VOICE_REJECTED, firstAttempt);

        assertEquals(firstAttempt + 1, secondAttempt);
        assertEquals(OverlayStateMachine.State.VOICE_STARTING, stale.nextState());
        assertEquals(List.of(), stale.effects());
    }

    @Test
    void matchingScopedRejectionIsAccepted() {
        OverlayStateMachine machine = recordingMachine();
        long attempt = machine.attemptId();
        machine.accept(OverlayEvent.TAP);
        machine.accept(OverlayEvent.VOICE_STOP_SUCCEEDED);

        OverlayStateMachine.Transition rejected =
                machine.accept(OverlayEvent.VOICE_REJECTED, attempt);

        assertEquals(OverlayStateMachine.State.IDLE, rejected.nextState());
        assertEquals(List.of(), rejected.effects());
    }

    @Test
    void duplicateAndCrossTerminalCallbacksAreInert() {
        OverlayStateMachine stopping = recordingMachine();
        stopping.accept(OverlayEvent.TAP);
        for (OverlayEvent event : List.of(
                OverlayEvent.VOICE_CANCEL_SUCCEEDED,
                OverlayEvent.VOICE_CANCEL_FAILED)) {
            OverlayStateMachine.Transition transition = stopping.accept(event);
            assertEquals(OverlayStateMachine.State.VOICE_STOPPING, transition.nextState());
            assertEquals(List.of(), transition.effects());
        }

        OverlayStateMachine canceling = recordingMachine();
        canceling.accept(OverlayEvent.CANCEL_VOICE_REQUESTED);
        for (OverlayEvent event : List.of(
                OverlayEvent.VOICE_STOP_SUCCEEDED,
                OverlayEvent.VOICE_STOP_FAILED)) {
            OverlayStateMachine.Transition transition = canceling.accept(event);
            assertEquals(OverlayStateMachine.State.VOICE_CANCELING, transition.nextState());
            assertEquals(List.of(), transition.effects());
        }
    }

    @Test
    void textRejectFromPendingReturnsIdleBecauseComposerAlreadyClosed() {
        OverlayStateMachine machine = textComposingMachine();
        machine.accept(OverlayEvent.SUBMIT_TEXT);
        machine.accept(OverlayEvent.TEXT_QUEUED);

        OverlayStateMachine.Transition rejected = machine.accept(OverlayEvent.TEXT_REJECTED);

        assertEquals(OverlayStateMachine.State.IDLE, rejected.nextState());
        assertEquals(List.of(), rejected.effects());
    }

    @Test
    void everyStateRejectsEveryIllegalEventWithoutEffects() {
        Map<OverlayStateMachine.State, EnumSet<OverlayEvent>> legal = legalEvents();
        for (OverlayStateMachine.State state : OverlayStateMachine.State.values()) {
            for (OverlayEvent event : OverlayEvent.values()) {
                if (event == OverlayEvent.TEARDOWN || legal.get(state).contains(event)) continue;
                OverlayStateMachine machine = machineIn(state);
                OverlayStateMachine.Transition transition = machine.accept(event);
                assertEquals(state, transition.nextState(), state + " accepted " + event);
                assertEquals(List.of(), transition.effects(), state + " emitted for " + event);
            }
        }
    }

    @Test
    void teardownFromEveryStateMakesEveryLaterEventInert() {
        for (OverlayStateMachine.State state : OverlayStateMachine.State.values()) {
            OverlayStateMachine machine = machineIn(state);
            machine.accept(OverlayEvent.TEARDOWN);
            for (OverlayEvent event : OverlayEvent.values()) {
                OverlayStateMachine.Transition transition = machine.accept(event);
                assertEquals(OverlayStateMachine.State.TEARING_DOWN, transition.nextState());
                assertEquals(List.of(), transition.effects());
            }
        }
    }

    @Test
    void speechReviewMenuActionHasExplicitOpenCloseLifecycleAndNeverStartsVoice() {
        OverlayStateMachine machine = new OverlayStateMachine();
        machine.accept(OverlayEvent.LONG_PRESS);

        OverlayStateMachine.Transition opening = machine.accept(OverlayEvent.OPEN_SPEECH_REVIEW);
        assertEquals(OverlayStateMachine.State.SPEECH_REVIEW_OPENING, opening.nextState());
        assertEquals(List.of(
                OverlayStateMachine.Effect.HIDE_MENU,
                OverlayStateMachine.Effect.OPEN_SPEECH_REVIEW), opening.effects());
        assertEquals(0, opening.effects().stream()
                .filter(effect -> effect == OverlayStateMachine.Effect.START_VOICE).count());

        OverlayStateMachine.Transition opened = machine.accept(
                OverlayEvent.SPEECH_REVIEW_OPENED);
        assertEquals(OverlayStateMachine.State.SPEECH_REVIEW_OPEN, opened.nextState());
        assertEquals(List.of(), opened.effects());

        OverlayStateMachine.Transition closed = machine.accept(
                OverlayEvent.CLOSE_SPEECH_REVIEW);
        assertEquals(OverlayStateMachine.State.IDLE, closed.nextState());
        assertEquals(List.of(OverlayStateMachine.Effect.RESTORE_PRIMARY_OVERLAY),
                closed.effects());
    }

    @Test
    void explicitSpeechReviewTelegramSubmitTransitionsToExactlyOneTextSend() {
        OverlayStateMachine machine = new OverlayStateMachine();
        machine.accept(OverlayEvent.LONG_PRESS);
        machine.accept(OverlayEvent.OPEN_SPEECH_REVIEW);
        machine.accept(OverlayEvent.SPEECH_REVIEW_OPENED);

        OverlayStateMachine.Transition submit = machine.accept(OverlayEvent.SUBMIT_TEXT);

        assertEquals(OverlayStateMachine.State.SPEECH_TEXT_QUEUEING, submit.nextState());
        assertEquals(List.of(OverlayStateMachine.Effect.SEND_TEXT), submit.effects());
        OverlayStateMachine.Transition duplicate = machine.accept(OverlayEvent.SUBMIT_TEXT);
        assertEquals(OverlayStateMachine.State.SPEECH_TEXT_QUEUEING, duplicate.nextState());
        assertEquals(List.of(), duplicate.effects());
    }

    @Test
    void rejectedSpeechTextSendReturnsToReviewWhileDeliveredSendClosesIt() {
        OverlayStateMachine rejected = machineIn(OverlayStateMachine.State.SPEECH_REVIEW_OPEN);
        rejected.accept(OverlayEvent.SUBMIT_TEXT);
        assertEquals(OverlayStateMachine.State.SPEECH_REVIEW_OPEN,
                rejected.accept(OverlayEvent.TEXT_REJECTED).nextState());

        OverlayStateMachine delivered = machineIn(OverlayStateMachine.State.SPEECH_REVIEW_OPEN);
        delivered.accept(OverlayEvent.SUBMIT_TEXT);
        assertEquals(OverlayStateMachine.State.SPEECH_TEXT_PENDING,
                delivered.accept(OverlayEvent.TEXT_QUEUED).nextState());
        assertEquals(OverlayStateMachine.State.IDLE,
                delivered.accept(OverlayEvent.TEXT_DELIVERED).nextState());
    }

    @Test
    void closedSpeechTextPendingCanStartVoiceAndOldDeliveryCallbackIsInert() {
        OverlayStateMachine machine = machineIn(OverlayStateMachine.State.SPEECH_REVIEW_OPEN);
        machine.accept(OverlayEvent.SUBMIT_TEXT);
        machine.accept(OverlayEvent.TEXT_QUEUED);
        long speechTextAttempt = machine.attemptId();

        OverlayStateMachine.Transition tapped = machine.accept(OverlayEvent.TAP);

        assertEquals(OverlayStateMachine.State.VOICE_STARTING, tapped.nextState());
        assertEquals(List.of(OverlayStateMachine.Effect.START_VOICE), tapped.effects());
        assertEquals(List.of(), machine.accept(
                OverlayEvent.TEXT_DELIVERED, speechTextAttempt).effects());
        assertEquals(OverlayStateMachine.State.VOICE_STARTING, machine.state());
    }

    @Test
    void speechReviewCloseWhileOpeningRestoresIdleOverlay() {
        OverlayStateMachine machine = new OverlayStateMachine();
        machine.accept(OverlayEvent.LONG_PRESS);
        machine.accept(OverlayEvent.OPEN_SPEECH_REVIEW);

        OverlayStateMachine.Transition closed = machine.accept(
                OverlayEvent.CLOSE_SPEECH_REVIEW);

        assertEquals(OverlayStateMachine.State.IDLE, closed.nextState());
        assertEquals(List.of(OverlayStateMachine.Effect.RESTORE_PRIMARY_OVERLAY),
                closed.effects());
    }

    @Test
    void speechReviewLaunchFailureRestoresIdleOverlayWithoutStartingStt() {
        OverlayStateMachine machine = new OverlayStateMachine();
        machine.accept(OverlayEvent.LONG_PRESS);
        machine.accept(OverlayEvent.OPEN_SPEECH_REVIEW);

        OverlayStateMachine.Transition failed = machine.accept(
                OverlayEvent.SPEECH_REVIEW_LAUNCH_FAILED);

        assertEquals(OverlayStateMachine.State.IDLE, failed.nextState());
        assertEquals(List.of(OverlayStateMachine.Effect.RESTORE_PRIMARY_OVERLAY),
                failed.effects());
        assertEquals(0, failed.effects().stream()
                .filter(effect -> effect == OverlayStateMachine.Effect.START_VOICE).count());
    }

    private static Map<OverlayStateMachine.State, EnumSet<OverlayEvent>> legalEvents() {
        Map<OverlayStateMachine.State, EnumSet<OverlayEvent>> legal =
                new EnumMap<>(OverlayStateMachine.State.class);
        legal.put(OverlayStateMachine.State.IDLE, EnumSet.of(OverlayEvent.TAP, OverlayEvent.LONG_PRESS));
        legal.put(OverlayStateMachine.State.MENU_OPEN, EnumSet.of(
                OverlayEvent.TAP, OverlayEvent.GESTURE_CANCELED, OverlayEvent.COMPOSE_TEXT,
                OverlayEvent.OPEN_SPEECH_REVIEW, OverlayEvent.START_LOCAL_RECORDING));
        legal.put(OverlayStateMachine.State.VOICE_STARTING, EnumSet.of(
                OverlayEvent.VOICE_START_SUCCEEDED, OverlayEvent.VOICE_START_FAILED));
        legal.put(OverlayStateMachine.State.RECORDING, EnumSet.of(
                OverlayEvent.TAP, OverlayEvent.CANCEL_VOICE_REQUESTED));
        legal.put(OverlayStateMachine.State.VOICE_STOPPING, EnumSet.of(
                OverlayEvent.VOICE_STOP_SUCCEEDED, OverlayEvent.LOCAL_VOICE_STOP_SUCCEEDED,
                OverlayEvent.VOICE_STOP_FAILED));
        legal.put(OverlayStateMachine.State.VOICE_CANCELING, EnumSet.of(
                OverlayEvent.VOICE_CANCEL_SUCCEEDED, OverlayEvent.VOICE_CANCEL_FAILED));
        legal.put(OverlayStateMachine.State.VOICE_QUEUEING, EnumSet.of(
                OverlayEvent.VOICE_QUEUED, OverlayEvent.VOICE_REJECTED, OverlayEvent.TAP));
        legal.put(OverlayStateMachine.State.VOICE_PENDING, EnumSet.of(
                OverlayEvent.VOICE_REJECTED, OverlayEvent.TAP));
        legal.put(OverlayStateMachine.State.VOICE_ARCHIVING,
                EnumSet.of(OverlayEvent.VOICE_COMPLETED));
        legal.put(OverlayStateMachine.State.TEXT_COMPOSING, EnumSet.of(
                OverlayEvent.SUBMIT_TEXT, OverlayEvent.CLOSE_COMPOSER));
        legal.put(OverlayStateMachine.State.SPEECH_REVIEW_OPENING, EnumSet.of(
                OverlayEvent.SPEECH_REVIEW_OPENED,
                OverlayEvent.SPEECH_REVIEW_LAUNCH_FAILED,
                OverlayEvent.CLOSE_SPEECH_REVIEW));
        legal.put(OverlayStateMachine.State.SPEECH_REVIEW_OPEN, EnumSet.of(
                OverlayEvent.SUBMIT_TEXT, OverlayEvent.CLOSE_SPEECH_REVIEW));
        legal.put(OverlayStateMachine.State.SPEECH_TEXT_QUEUEING, EnumSet.of(
                OverlayEvent.TEXT_QUEUED, OverlayEvent.TEXT_REJECTED,
                OverlayEvent.TEXT_DELIVERED, OverlayEvent.TAP));
        legal.put(OverlayStateMachine.State.SPEECH_TEXT_PENDING, EnumSet.of(
                OverlayEvent.TEXT_DELIVERED, OverlayEvent.TEXT_REJECTED,
                OverlayEvent.TAP));
        legal.put(OverlayStateMachine.State.TEXT_QUEUEING, EnumSet.of(
                OverlayEvent.TEXT_QUEUED, OverlayEvent.TEXT_REJECTED,
                OverlayEvent.TEXT_DELIVERED, OverlayEvent.TAP));
        legal.put(OverlayStateMachine.State.TEXT_PENDING, EnumSet.of(
                OverlayEvent.TEXT_DELIVERED, OverlayEvent.TEXT_REJECTED, OverlayEvent.TAP));
        legal.put(OverlayStateMachine.State.TEARING_DOWN, EnumSet.noneOf(OverlayEvent.class));
        return legal;
    }

    private static OverlayStateMachine machineIn(OverlayStateMachine.State target) {
        OverlayStateMachine machine = new OverlayStateMachine();
        switch (target) {
            case IDLE -> { }
            case MENU_OPEN -> machine.accept(OverlayEvent.LONG_PRESS);
            case VOICE_STARTING -> machine.accept(OverlayEvent.TAP);
            case RECORDING -> {
                machine.accept(OverlayEvent.TAP);
                machine.accept(OverlayEvent.VOICE_START_SUCCEEDED);
            }
            case VOICE_STOPPING -> {
                machine = recordingMachine();
                machine.accept(OverlayEvent.TAP);
            }
            case VOICE_CANCELING -> {
                machine = recordingMachine();
                machine.accept(OverlayEvent.CANCEL_VOICE_REQUESTED);
            }
            case VOICE_QUEUEING -> {
                machine = recordingMachine();
                machine.accept(OverlayEvent.TAP);
                machine.accept(OverlayEvent.VOICE_STOP_SUCCEEDED);
            }
            case VOICE_PENDING -> {
                machine = recordingMachine();
                machine.accept(OverlayEvent.TAP);
                machine.accept(OverlayEvent.VOICE_STOP_SUCCEEDED);
                machine.accept(OverlayEvent.VOICE_QUEUED);
            }
            case VOICE_ARCHIVING -> {
                machine = recordingMachine();
                machine.accept(OverlayEvent.TAP);
                machine.accept(OverlayEvent.LOCAL_VOICE_STOP_SUCCEEDED);
            }
            case TEXT_COMPOSING -> {
                machine.accept(OverlayEvent.LONG_PRESS);
                machine.accept(OverlayEvent.COMPOSE_TEXT);
            }
            case SPEECH_REVIEW_OPENING -> {
                machine.accept(OverlayEvent.LONG_PRESS);
                machine.accept(OverlayEvent.OPEN_SPEECH_REVIEW);
            }
            case SPEECH_REVIEW_OPEN -> {
                machine.accept(OverlayEvent.LONG_PRESS);
                machine.accept(OverlayEvent.OPEN_SPEECH_REVIEW);
                machine.accept(OverlayEvent.SPEECH_REVIEW_OPENED);
            }
            case SPEECH_TEXT_QUEUEING -> {
                machine = machineIn(OverlayStateMachine.State.SPEECH_REVIEW_OPEN);
                machine.accept(OverlayEvent.SUBMIT_TEXT);
            }
            case SPEECH_TEXT_PENDING -> {
                machine = machineIn(OverlayStateMachine.State.SPEECH_REVIEW_OPEN);
                machine.accept(OverlayEvent.SUBMIT_TEXT);
                machine.accept(OverlayEvent.TEXT_QUEUED);
            }
            case TEXT_QUEUEING -> {
                machine = textComposingMachine();
                machine.accept(OverlayEvent.SUBMIT_TEXT);
            }
            case TEXT_PENDING -> {
                machine = textComposingMachine();
                machine.accept(OverlayEvent.SUBMIT_TEXT);
                machine.accept(OverlayEvent.TEXT_QUEUED);
            }
            case TEARING_DOWN -> machine.accept(OverlayEvent.TEARDOWN);
        }
        assertEquals(target, machine.state());
        return machine;
    }

    private static OverlayStateMachine textComposingMachine() {
        OverlayStateMachine machine = new OverlayStateMachine();
        machine.accept(OverlayEvent.LONG_PRESS);
        OverlayStateMachine.Transition compose = machine.accept(OverlayEvent.COMPOSE_TEXT);
        assertEquals(OverlayStateMachine.State.TEXT_COMPOSING, compose.nextState());
        assertEquals(List.of(
                OverlayStateMachine.Effect.HIDE_MENU,
                OverlayStateMachine.Effect.OPEN_TEXT_COMPOSER), compose.effects());
        return machine;
    }

    private static OverlayStateMachine recordingMachine() {
        OverlayStateMachine machine = new OverlayStateMachine();
        machine.accept(OverlayEvent.TAP);
        machine.accept(OverlayEvent.VOICE_START_SUCCEEDED);
        return machine;
    }
}

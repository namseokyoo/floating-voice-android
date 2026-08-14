package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SpeechShareStateMachineTest {
    @Test
    void partialPreviewsDraftButFinalOnlyMovesToReview() {
        SpeechShareStateMachine machine = listeningMachine();
        long generation = machine.generation();

        SpeechShareStateMachine.Transition partial = machine.accept(
                SpeechShareEvent.partialResult(generation, "부분 문장"));
        assertEquals(SpeechShareStateMachine.State.STT_LISTENING, partial.nextState());
        assertEquals(List.of(SpeechShareStateMachine.Effect.draftPreview("부분 문장")),
                partial.effects());
        assertEquals(0, partial.effects().stream()
                .filter(effect -> effect.type()
                        == SpeechShareStateMachine.EffectType.LAUNCH_SHARE_CHOOSER)
                .count());

        machine.accept(SpeechShareEvent.processing(generation));
        SpeechShareStateMachine.Transition result = machine.accept(
                SpeechShareEvent.finalResult(generation, "최종 문장"));
        assertEquals(SpeechShareStateMachine.State.STT_REVIEW, result.nextState());
        assertEquals(List.of(SpeechShareStateMachine.Effect.review("최종 문장")),
                result.effects());
    }

    @Test
    void blankFinalFailsAndOffersRetry() {
        SpeechShareStateMachine machine = listeningMachine();

        SpeechShareStateMachine.Transition result = machine.accept(
                SpeechShareEvent.finalResult(machine.generation(), "  \n"));

        assertEquals(SpeechShareStateMachine.State.STT_FAILED, result.nextState());
        assertEquals(List.of(SpeechShareStateMachine.Effect.retry()), result.effects());
    }

    @Test
    void shareRequiresExplicitRequestAfterReviewedNonblankFinal() {
        SpeechShareStateMachine beforeReview = listeningMachine();
        SpeechShareStateMachine.Transition rejected = beforeReview.accept(
                SpeechShareEvent.share(beforeReview.generation(), "too early"));
        assertEquals(SpeechShareStateMachine.State.STT_LISTENING, rejected.nextState());
        assertEquals(List.of(), rejected.effects());

        SpeechShareStateMachine reviewed = listeningMachine();
        reviewed.accept(SpeechShareEvent.finalResult(reviewed.generation(), "검토할 문장"));
        assertEquals(List.of(), reviewed.accept(SpeechShareEvent.partialResult(
                reviewed.generation(), "늦은 부분 결과")).effects());

        SpeechShareStateMachine.Transition shared = reviewed.accept(
                SpeechShareEvent.share(reviewed.generation(), "검토할 문장"));
        assertEquals(SpeechShareStateMachine.State.SHARE_CHOOSER_LAUNCHED,
                shared.nextState());
        assertEquals(List.of(SpeechShareStateMachine.Effect.shareChooser("검토할 문장")),
                shared.effects());
        assertEquals(List.of(), reviewed.accept(SpeechShareEvent.share(
                reviewed.generation(), "duplicate")).effects());
    }

    @Test
    void firstCancelResultOrErrorTerminalEventWins() {
        SpeechShareStateMachine canceled = listeningMachine();
        long canceledGeneration = canceled.generation();
        assertEquals(SpeechShareStateMachine.State.STT_CANCELED,
                canceled.accept(SpeechShareEvent.cancel(canceledGeneration)).nextState());
        assertEquals(List.of(), canceled.accept(
                SpeechShareEvent.finalResult(canceledGeneration, "late")).effects());
        assertEquals(SpeechShareStateMachine.State.STT_CANCELED, canceled.state());

        SpeechShareStateMachine completed = listeningMachine();
        long completedGeneration = completed.generation();
        completed.accept(SpeechShareEvent.finalResult(completedGeneration, "first"));
        assertEquals(SpeechShareStateMachine.State.STT_REVIEW, completed.state());
        assertEquals(List.of(), completed.accept(
                SpeechShareEvent.error(completedGeneration)).effects());
        assertEquals(SpeechShareStateMachine.State.STT_REVIEW, completed.state());

        SpeechShareStateMachine failed = listeningMachine();
        long failedGeneration = failed.generation();
        failed.accept(SpeechShareEvent.error(failedGeneration));
        assertEquals(SpeechShareStateMachine.State.STT_FAILED, failed.state());
        assertEquals(List.of(), failed.accept(
                SpeechShareEvent.cancel(failedGeneration)).effects());
        assertEquals(SpeechShareStateMachine.State.STT_FAILED, failed.state());
    }

    @Test
    void staleCallbacksBeforeDestroyAreIgnoredAndRetryUsesNewGeneration() {
        SpeechShareStateMachine machine = listeningMachine();
        long first = machine.generation();
        machine.accept(SpeechShareEvent.error(first));

        SpeechShareStateMachine.Transition retry = machine.accept(SpeechShareEvent.retryRequest());
        long second = machine.generation();
        assertNotEquals(first, second);
        assertEquals(SpeechShareStateMachine.State.STT_CHECKING_SUPPORT, retry.nextState());
        assertEquals(List.of(SpeechShareStateMachine.Effect.checkSupport(second)), retry.effects());
        assertEquals(List.of(), machine.accept(
                SpeechShareEvent.finalResult(first, "stale")).effects());
        assertEquals(SpeechShareStateMachine.State.STT_CHECKING_SUPPORT, machine.state());

        machine.accept(SpeechShareEvent.cancel(second));
        SpeechShareStateMachine.Transition thirdAttempt =
                machine.accept(SpeechShareEvent.retryRequest());
        long third = machine.generation();
        assertEquals(SpeechShareStateMachine.State.STT_CHECKING_SUPPORT,
                thirdAttempt.nextState());
        assertEquals(List.of(), machine.accept(SpeechShareEvent.cancel(second)).effects());
        assertEquals(SpeechShareStateMachine.State.STT_CHECKING_SUPPORT, machine.state());

        machine.accept(SpeechShareEvent.teardown());
        assertEquals(SpeechShareStateMachine.State.TEARING_DOWN, machine.state());
        assertEquals(List.of(), machine.accept(
                SpeechShareEvent.supportAvailable(second)).effects());
    }

    @Test
    void completedSuccessfulInteractionReturnsToIdleWithoutReusingGeneration() {
        SpeechShareStateMachine machine = listeningMachine();
        long first = machine.generation();
        machine.accept(SpeechShareEvent.finalResult(first, "첫 결과"));
        machine.accept(SpeechShareEvent.share(first, "첫 결과"));

        SpeechShareStateMachine.Transition completed =
                machine.accept(SpeechShareEvent.complete(first));
        assertEquals(SpeechShareStateMachine.State.IDLE, completed.nextState());

        machine.accept(SpeechShareEvent.start());
        long second = machine.generation();
        assertNotEquals(first, second);
        assertEquals(List.of(), machine.accept(
                SpeechShareEvent.finalResult(first, "stale")).effects());
        assertEquals(SpeechShareStateMachine.State.STT_CHECKING_SUPPORT, machine.state());
    }

    @Test
    void editedReviewTextNotRecognizerFinalBecomesChooserPayload() {
        SpeechShareStateMachine machine = listeningMachine();
        long generation = machine.generation();
        machine.accept(SpeechShareEvent.finalResult(generation, "stale recognizer final"));

        SpeechShareStateMachine.Transition shared = machine.accept(
                SpeechShareEvent.share(generation, "사용자가 고친 문장 ✨"));

        assertEquals(List.of(SpeechShareStateMachine.Effect.shareChooser(
                "사용자가 고친 문장 ✨")), shared.effects());
    }

    @Test
    void recognitionErrorCanEnterKeyboardReviewWithVisiblePartialAndNoChooser() {
        SpeechShareStateMachine machine = listeningMachine();
        long generation = machine.generation();
        machine.accept(SpeechShareEvent.partialResult(generation, "남아 있는 부분"));
        machine.accept(SpeechShareEvent.error(generation));

        SpeechShareStateMachine.Transition fallback = machine.accept(
                SpeechShareEvent.keyboardFallback(generation));

        assertEquals(SpeechShareStateMachine.State.STT_REVIEW, fallback.nextState());
        assertEquals(List.of(SpeechShareStateMachine.Effect.review("남아 있는 부분")),
                fallback.effects());
        assertEquals(0, fallback.effects().stream()
                .filter(effect -> effect.type()
                        == SpeechShareStateMachine.EffectType.LAUNCH_SHARE_CHOOSER)
                .count());
    }

    @Test
    void chooserLaunchFailureRollsBackForSafeExplicitRetry() {
        SpeechShareStateMachine machine = listeningMachine();
        long generation = machine.generation();
        machine.accept(SpeechShareEvent.finalResult(generation, "draft"));
        machine.accept(SpeechShareEvent.share(generation, "edited"));

        SpeechShareStateMachine.Transition failed = machine.accept(
                SpeechShareEvent.shareLaunchFailed(generation));
        assertEquals(SpeechShareStateMachine.State.STT_REVIEW, failed.nextState());
        assertEquals(List.of(), failed.effects());

        SpeechShareStateMachine.Transition retry = machine.accept(
                SpeechShareEvent.share(generation, "edited again"));
        assertEquals(List.of(SpeechShareStateMachine.Effect.shareChooser("edited again")),
                retry.effects());
    }

    @Test
    void completionAndChooserReturnAreGenerationScoped() {
        SpeechShareStateMachine machine = listeningMachine();
        long generation = machine.generation();
        machine.accept(SpeechShareEvent.finalResult(generation, "draft"));
        assertEquals(List.of(), machine.accept(
                SpeechShareEvent.share(generation + 1L, "stale share")).effects());
        assertEquals(SpeechShareStateMachine.State.STT_REVIEW, machine.state());
        machine.accept(SpeechShareEvent.share(generation, "draft"));

        assertEquals(SpeechShareStateMachine.State.SHARE_CHOOSER_LAUNCHED,
                machine.accept(SpeechShareEvent.complete(generation + 1L)).nextState());
        assertEquals(SpeechShareStateMachine.State.SHARE_CHOOSER_LAUNCHED,
                machine.accept(SpeechShareEvent.chooserReturned(generation + 1L)).nextState());
        assertEquals(SpeechShareStateMachine.State.STT_REVIEW,
                machine.accept(SpeechShareEvent.chooserReturned(generation)).nextState());
        assertEquals(SpeechShareStateMachine.State.IDLE,
                machine.accept(SpeechShareEvent.complete(generation)).nextState());
    }

    private static SpeechShareStateMachine listeningMachine() {
        SpeechShareStateMachine machine = new SpeechShareStateMachine();
        SpeechShareStateMachine.Transition start = machine.accept(SpeechShareEvent.start());
        assertEquals(SpeechShareStateMachine.State.STT_CHECKING_SUPPORT, start.nextState());
        long generation = machine.generation();
        assertEquals(List.of(SpeechShareStateMachine.Effect.checkSupport(generation)),
                start.effects());
        SpeechShareStateMachine.Transition supported = machine.accept(
                SpeechShareEvent.supportAvailable(generation));
        assertEquals(SpeechShareStateMachine.State.STT_LISTENING, supported.nextState());
        assertEquals(List.of(SpeechShareStateMachine.Effect.startListening(generation)),
                supported.effects());
        return machine;
    }
}

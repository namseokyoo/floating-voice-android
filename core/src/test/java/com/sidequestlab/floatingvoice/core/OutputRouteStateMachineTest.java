package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputRouteStateMachineTest {
    @Test
    void textCaptureDefaultsToVerifiedTelegramDestinationAndFreezesOneSnapshot() {
        OutputRouteStateMachine machine = new OutputRouteStateMachine(
                OutputRoute.ContentKind.TEXT, 7L,
                catalog(primary(), secondary()), "primary");

        assertEquals(OutputRoute.TELEGRAM_TEXT, machine.selectedRoute());
        assertEquals("primary", machine.selectedDestination().orElseThrow().localId());

        OutputSnapshot snapshot = machine.freeze("검토하고 수정한 문장").orElseThrow();

        assertEquals(OutputRouteStateMachine.Phase.FROZEN, machine.phase());
        assertEquals(OutputRoute.TELEGRAM_TEXT, snapshot.route());
        assertEquals("검토하고 수정한 문장", snapshot.payload());
        DispatchTargetSnapshot target = snapshot.telegramTarget().orElseThrow();
        assertEquals("primary", target.localId());
        assertEquals(100L, target.chatId());
        assertEquals(4L, target.verificationRevision());
        assertTrue(machine.freeze("두 번째 실행").isEmpty());
    }

    @Test
    void explicitVerifiedDestinationChangeFreezesOnlyTheChangedTelegramTarget() {
        OutputRouteStateMachine machine = new OutputRouteStateMachine(
                OutputRoute.ContentKind.TEXT, 7L,
                catalog(primary(), secondary()), "primary");

        assertTrue(machine.selectTelegramDestination("secondary"));
        OutputSnapshot snapshot = machine.freeze("두 번째 봇으로 보낼 문장").orElseThrow();

        DispatchTargetSnapshot target = snapshot.telegramTarget().orElseThrow();
        assertEquals("secondary", target.localId());
        assertEquals(200L, target.chatId());
        assertEquals(5L, target.verificationRevision());
    }

    @Test
    void systemTextShareNeverRequiresOrStoresAFakeChatDestination() {
        OutputRouteStateMachine machine = new OutputRouteStateMachine(
                OutputRoute.ContentKind.TEXT, 7L,
                catalog(primary(), secondary()), "primary");

        assertTrue(machine.selectRoute(OutputRoute.SYSTEM_TEXT_SHARE));
        assertTrue(machine.selectedDestination().isEmpty());
        assertTrue(!machine.selectTelegramDestination("secondary"));

        OutputSnapshot snapshot = machine.freeze("공유할 검토 문장").orElseThrow();

        assertEquals(OutputRoute.SYSTEM_TEXT_SHARE, snapshot.route());
        assertTrue(snapshot.telegramTarget().isEmpty());
        assertTrue(!machine.selectRoute(OutputRoute.TELEGRAM_TEXT));
    }

    @Test
    void changedDestinationRevisionBlocksFreezeWithoutAutomaticFallback() {
        OutputRouteStateMachine machine = new OutputRouteStateMachine(
                OutputRoute.ContentKind.TEXT, 7L,
                catalog(primary(), secondary()), "primary");
        assertTrue(machine.selectTelegramDestination("secondary"));

        machine.replaceCatalog(catalog(primary(),
                destination("secondary", 200L, 600L, 6L)));

        assertTrue(machine.freeze("잘못된 방으로 보내면 안 되는 문장").isEmpty());
        assertEquals(OutputRoute.TELEGRAM_TEXT, machine.selectedRoute());
        assertEquals("secondary", machine.selectedDestination().orElseThrow().localId());
        assertEquals(OutputRouteStateMachine.Phase.SELECTING, machine.phase());
    }

    @Test
    void systemTextShareWorksWithoutTelegramAccountOrDestination() {
        OutputRouteStateMachine machine = new OutputRouteStateMachine(
                OutputRoute.ContentKind.TEXT, 0L,
                DestinationCatalog.empty(), null);

        assertTrue(machine.selectRoute(OutputRoute.SYSTEM_TEXT_SHARE));
        OutputSnapshot snapshot = machine.freeze("Telegram 없이 공유할 문장").orElseThrow();

        assertEquals(OutputRoute.SYSTEM_TEXT_SHARE, snapshot.route());
        assertTrue(snapshot.telegramTarget().isEmpty());
    }

    @Test
    void localArchiveFreezesWithoutTelegramAndNeverFallsBack() {
        OutputRouteStateMachine machine = new OutputRouteStateMachine(
                OutputRoute.ContentKind.AUDIO, 0L, DestinationCatalog.empty(), null);

        assertTrue(machine.selectRoute(OutputRoute.LOCAL_AUDIO_ARCHIVE));
        OutputSnapshot snapshot = machine.freeze("/tmp/voice.ogg").orElseThrow();

        assertEquals(OutputRoute.LOCAL_AUDIO_ARCHIVE, snapshot.route());
        assertTrue(snapshot.telegramTarget().isEmpty());
        assertTrue(!machine.selectRoute(OutputRoute.TELEGRAM_VOICE));
    }

    @Test
    void retryUsesTheExactFrozenSnapshotAndCompletionClearsPayloadReference() {
        OutputRouteStateMachine machine = new OutputRouteStateMachine(
                OutputRoute.ContentKind.TEXT, 7L,
                catalog(primary(), secondary()), "primary");
        OutputSnapshot frozen = machine.freeze("원래 대상에 다시 보낼 문장").orElseThrow();

        assertSame(frozen,
                machine.snapshotForRetry(frozen.outputAttemptId()).orElseThrow());
        assertTrue(machine.snapshotForRetry(frozen.outputAttemptId() + 1L).isEmpty());
        assertTrue(!machine.selectRoute(OutputRoute.SYSTEM_TEXT_SHARE));
        assertTrue(!machine.complete(frozen.outputAttemptId() + 1L));

        assertTrue(machine.complete(frozen.outputAttemptId()));
        assertEquals(OutputRouteStateMachine.Phase.COMPLETED, machine.phase());
        assertTrue(machine.snapshotForRetry(frozen.outputAttemptId()).isEmpty());
        assertTrue(!machine.complete(frozen.outputAttemptId()));
    }

    private static DestinationCatalog catalog(Destination... destinations) {
        return new DestinationCatalog(List.of(destinations)).withDefault("primary", 7L);
    }

    private static Destination primary() {
        return destination("primary", 100L, 500L, 4L);
    }

    private static Destination secondary() {
        return destination("secondary", 200L, 600L, 5L);
    }

    private static Destination destination(
            String localId, long chatId, long peerUserId, long revision) {
        return new Destination(
                localId, 7L, chatId, peerUserId,
                localId + "_configured", localId + "_resolved",
                "Resolved " + localId, "Alias " + localId,
                Destination.VerificationStatus.VERIFIED,
                revision, 1_700_000_000_000L + revision, true);
    }
}

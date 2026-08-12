package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PendingDispatchTest {
    @Test
    void preparedDispatchOwnsImmutableTargetFileAndDuration() {
        PendingDispatch dispatch = PendingDispatch.prepare(
                "dispatch-1", snapshot("primary", 100L), "/tmp/voice.ogg", 7,
                1_700_000_000_000L);

        assertEquals(DispatchState.PREPARED, dispatch.state());
        assertEquals("dispatch-1", dispatch.dispatchId());
        assertEquals(100L, dispatch.target().chatId());
        assertEquals("/tmp/voice.ogg", dispatch.absolutePath());
        assertEquals(7, dispatch.durationSeconds());
        assertFalse(dispatch.automaticRetryAllowed());
    }

    @Test
    void queuedAndFailureKeepOriginalSnapshotAndFile() {
        PendingDispatch prepared = PendingDispatch.prepare(
                "dispatch-2", snapshot("secondary", 200L), "/tmp/second.ogg", 3,
                1_700_000_000_100L);
        PendingDispatch queued = prepared.queued(-9L);
        PendingDispatch failed = queued.failedRetained(429, "retry later", true, 30);

        assertEquals(DispatchState.FAILED_RETAINED, failed.state());
        assertEquals(200L, failed.target().chatId());
        assertEquals("/tmp/second.ogg", failed.absolutePath());
        assertEquals(-9L, failed.temporaryMessageId());
        assertTrue(failed.serverRetryable());
        assertEquals(30, failed.retryAfterSeconds());
        assertFalse(failed.automaticRetryAllowed());
    }

    @Test
    void restartTurnsPreparedOrQueuedIntoUnknownWithoutAutomaticRetry() {
        PendingDispatch prepared = PendingDispatch.prepare(
                "dispatch-3", snapshot("primary", 100L), "/tmp/voice.ogg", 2,
                1_700_000_000_200L);
        PendingDispatch queued = prepared.queued(-11L);

        assertEquals(DispatchState.UNKNOWN_RETAINED, prepared.recoveredAfterRestart().state());
        assertEquals(DispatchState.UNKNOWN_RETAINED, queued.recoveredAfterRestart().state());
        assertFalse(queued.recoveredAfterRestart().automaticRetryAllowed());
    }

    @Test
    void successIsIdempotentAndDeleteFailureRemainsVisible() {
        PendingDispatch queued = PendingDispatch.prepare(
                "dispatch-4", snapshot("primary", 100L), "/tmp/voice.ogg", 2,
                1_700_000_000_300L).queued(-12L);

        PendingDispatch retained = queued.completed(false);
        assertEquals(DispatchState.COMPLETED_FILE_RETAINED, retained.state());
        assertEquals(retained, retained.completed(false));

        PendingDispatch deleted = queued.completed(true);
        assertEquals(DispatchState.COMPLETED, deleted.state());
        assertEquals(deleted, deleted.completed(true));
    }

    @Test
    void retainedCompletedFileCanBeMarkedDeletedWithoutChangingDispatchTruth() {
        PendingDispatch retained = PendingDispatch.prepare(
                "dispatch-4b", snapshot("primary", 100L), "/tmp/voice.ogg", 2,
                1_700_000_000_350L).queued(-22L).completed(false);

        PendingDispatch deleted = retained.fileDeletedAfterCompletion();

        assertEquals(DispatchState.COMPLETED, deleted.state());
        assertEquals(retained.target(), deleted.target());
        assertEquals(retained.absolutePath(), deleted.absolutePath());
        assertEquals(retained.temporaryMessageId(), deleted.temporaryMessageId());
    }

    @Test
    void originalDestinationSurvivesVisibleCatalogChanges() {
        PendingDispatch pending = PendingDispatch.prepare(
                "dispatch-5", snapshot("primary", 100L), "/tmp/voice.ogg", 4,
                1_700_000_000_400L).queued(-13L);

        DispatchTargetSnapshot visibleNow = snapshot("secondary", 200L);

        assertEquals(100L, pending.target().chatId());
        assertEquals(200L, visibleNow.chatId());
    }

    private static DispatchTargetSnapshot snapshot(String localId, long chatId) {
        Destination destination = new Destination(
                localId, 7L, chatId, chatId + 1,
                localId + "_configured", localId + "_resolved",
                "Resolved " + localId, "Alias " + localId,
                Destination.VerificationStatus.VERIFIED,
                4L, 1_700_000_000_000L, true);
        RouteStateMachine machine = new RouteStateMachine(
                7L, DestinationCatalog.restore(java.util.List.of(destination), localId), localId);
        machine.startRecording();
        machine.beginFreezing();
        return machine.freeze().orElseThrow();
    }
}

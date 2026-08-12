package com.sidequestlab.floatingvoice;

import com.sidequestlab.floatingvoice.core.DispatchState;
import com.sidequestlab.floatingvoice.core.DispatchTargetSnapshot;
import com.sidequestlab.floatingvoice.core.PendingDispatch;
import com.sidequestlab.floatingvoice.core.PendingMessageKey;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class PendingDispatchStoreTest {
    @Test
    public void prepareMustCommitBeforeQueueMappingCanExist() {
        FakeBackend backend = new FakeBackend();
        PendingDispatchStore store = new PendingDispatchStore(backend);
        PendingDispatch prepared = prepared("d1", 100L);

        assertTrue(store.prepare(prepared));
        assertEquals(DispatchState.PREPARED, store.find("d1").orElseThrow().state());
        assertTrue(store.markQueued("d1", new PendingMessageKey(100L, -7L)));
        assertEquals("d1", store.findDispatchId(new PendingMessageKey(100L, -7L)).orElseThrow());
        assertEquals(DispatchState.QUEUED, store.find("d1").orElseThrow().state());
    }

    @Test
    public void failureRetainsRecordPathAndSnapshotButConsumesMessageMapping() {
        FakeBackend backend = new FakeBackend();
        PendingDispatchStore store = new PendingDispatchStore(backend);
        store.prepare(prepared("d2", 200L));
        PendingMessageKey key = new PendingMessageKey(200L, -8L);
        store.markQueued("d2", key);

        assertTrue(store.markFailed(key, 429, "retry later", true, 30));
        PendingDispatch failed = store.find("d2").orElseThrow();
        assertEquals(DispatchState.FAILED_RETAINED, failed.state());
        assertEquals(200L, failed.target().chatId());
        assertEquals("/tmp/d2.ogg", failed.absolutePath());
        assertTrue(store.findDispatchId(key).isEmpty());
        assertFalse(failed.automaticRetryAllowed());
    }

    @Test
    public void unknownMessageDoesNotConsumeAnyOtherDispatch() {
        FakeBackend backend = new FakeBackend();
        PendingDispatchStore store = new PendingDispatchStore(backend);
        store.prepare(prepared("d3", 300L));
        PendingMessageKey known = new PendingMessageKey(300L, -9L);
        store.markQueued("d3", known);

        assertFalse(store.markFailed(new PendingMessageKey(999L, -9L), 500, "wrong chat", false, 0));
        assertEquals(DispatchState.QUEUED, store.find("d3").orElseThrow().state());
        assertEquals("d3", store.findDispatchId(known).orElseThrow());
    }

    @Test
    public void restartMarksPreparedAndQueuedUnknownWithoutDeletingMappingsOrFiles() {
        FakeBackend backend = new FakeBackend();
        PendingDispatchStore store = new PendingDispatchStore(backend);
        store.prepare(prepared("prepared", 100L));
        store.prepare(prepared("queued", 200L));
        PendingMessageKey queuedKey = new PendingMessageKey(200L, -10L);
        store.markQueued("queued", queuedKey);

        assertEquals(2, store.markUncertainAfterRestart());
        assertEquals(DispatchState.UNKNOWN_RETAINED,
                store.find("prepared").orElseThrow().state());
        assertEquals(DispatchState.UNKNOWN_RETAINED,
                store.find("queued").orElseThrow().state());
        assertEquals("queued", store.findDispatchId(queuedKey).orElseThrow());
        assertFalse(store.find("prepared").orElseThrow().automaticRetryAllowed());
        assertFalse(store.find("queued").orElseThrow().automaticRetryAllowed());
    }

    @Test
    public void lateFinalSuccessAfterRestartCompletesOriginalSnapshotOnlyOnce() {
        FakeBackend backend = new FakeBackend();
        PendingDispatchStore store = new PendingDispatchStore(backend);
        PendingMessageKey original = new PendingMessageKey(200L, -30L);
        assertTrue(store.prepare(preparedFor("late", "original", 200L)));
        assertTrue(store.markQueued("late", original));
        assertEquals(1, store.markUncertainAfterRestart());

        assertTrue(store.markCompleted(original, false));
        PendingDispatch completed = store.find("late").orElseThrow();
        assertEquals(DispatchState.COMPLETED_FILE_RETAINED, completed.state());
        assertEquals("original", completed.target().localId());
        assertEquals(200L, completed.target().chatId());
        assertEquals("/tmp/late.ogg", completed.absolutePath());
        assertFalse(store.markCompleted(original, false));
    }

    @Test
    public void destinationRemovalCannotChangeFailedDispatchSnapshotOrPath() {
        FakeBackend backend = new FakeBackend();
        PendingDispatchStore store = new PendingDispatchStore(backend);
        PendingMessageKey key = new PendingMessageKey(700L, -31L);
        assertTrue(store.prepare(preparedFor("removed", "deleted-destination", 700L)));
        assertTrue(store.markQueued("removed", key));

        assertTrue(store.markFailed(key, 503, "offline", true, 5));

        PendingDispatch retained = store.find("removed").orElseThrow();
        assertEquals(DispatchState.FAILED_RETAINED, retained.state());
        assertEquals("deleted-destination", retained.target().localId());
        assertEquals(700L, retained.target().chatId());
        assertEquals("/tmp/removed.ogg", retained.absolutePath());
        assertFalse(retained.automaticRetryAllowed());
    }

    @Test
    public void accountCloseMarksPendingUnknownAndDetachesEveryMessageMapping() {
        FakeBackend backend = new FakeBackend();
        PendingDispatchStore store = new PendingDispatchStore(backend);
        PendingMessageKey first = new PendingMessageKey(800L, -40L);
        PendingMessageKey second = new PendingMessageKey(900L, -41L);
        assertTrue(store.prepare(preparedFor("old-a", "old-account-a", 800L)));
        assertTrue(store.prepare(preparedFor("old-b", "old-account-b", 900L)));
        assertTrue(store.markQueued("old-a", first));
        assertTrue(store.markQueued("old-b", second));

        assertEquals(2, store.markUncertainAndDetachMessages());

        assertEquals(DispatchState.UNKNOWN_RETAINED,
                store.find("old-a").orElseThrow().state());
        assertEquals(DispatchState.UNKNOWN_RETAINED,
                store.find("old-b").orElseThrow().state());
        assertTrue(store.findDispatchId(first).isEmpty());
        assertTrue(store.findDispatchId(second).isEmpty());
    }

    @Test
    public void messageKeyCollisionCannotBeReassignedToAnotherDispatch() {
        FakeBackend backend = new FakeBackend();
        PendingDispatchStore store = new PendingDispatchStore(backend);
        PendingMessageKey collision = new PendingMessageKey(910L, -50L);
        assertTrue(store.prepare(preparedFor("owner-a", "account-a", 910L)));
        assertTrue(store.prepare(preparedFor("owner-b", "account-b", 910L)));
        assertTrue(store.markQueued("owner-a", collision));

        assertFalse(store.markQueued("owner-b", collision));

        assertEquals("owner-a", store.findDispatchId(collision).orElseThrow());
        assertEquals(DispatchState.QUEUED,
                store.find("owner-a").orElseThrow().state());
        assertEquals(DispatchState.PREPARED,
                store.find("owner-b").orElseThrow().state());
    }

    @Test
    public void finalTransitionRequiresExpectedDispatchOwner() {
        FakeBackend backend = new FakeBackend();
        PendingDispatchStore store = new PendingDispatchStore(backend);
        PendingMessageKey key = new PendingMessageKey(920L, -51L);
        assertTrue(store.prepare(preparedFor("expected-a", "account-a", 920L)));
        assertTrue(store.prepare(preparedFor("expected-b", "account-b", 920L)));
        assertTrue(store.markQueued("expected-a", key));

        assertFalse(store.markCompleted("expected-b", key, false));
        assertFalse(store.markFailed("expected-b", key, 500, "stale", false, 0));

        assertEquals("expected-a", store.findDispatchId(key).orElseThrow());
        assertEquals(DispatchState.QUEUED,
                store.find("expected-a").orElseThrow().state());
        assertEquals(DispatchState.PREPARED,
                store.find("expected-b").orElseThrow().state());
    }

    @Test
    public void commitFailureNeverPublishesPreparedOrQueuedState() {
        FakeBackend backend = new FakeBackend();
        PendingDispatchStore store = new PendingDispatchStore(backend);
        backend.failNext = true;
        assertFalse(store.prepare(prepared("d4", 400L)));
        assertTrue(store.find("d4").isEmpty());

        assertTrue(store.prepare(prepared("d4", 400L)));
        PendingMessageKey failedQueue = new PendingMessageKey(400L, -11L);
        backend.failNext = true;
        assertFalse(store.markQueued("d4", failedQueue));
        assertEquals(DispatchState.PREPARED, store.find("d4").orElseThrow().state());

        assertTrue(store.markQueuePersistenceUnknown("d4", -11L));
        assertEquals(DispatchState.UNKNOWN_RETAINED,
                store.find("d4").orElseThrow().state());
        assertTrue(store.findDispatchId(failedQueue).isEmpty());
    }

    @Test
    public void completionIsCommittedBeforeFileDeletionAndCanThenAdvance() {
        FakeBackend backend = new FakeBackend();
        PendingDispatchStore store = new PendingDispatchStore(backend);
        PendingMessageKey key = new PendingMessageKey(500L, -12L);
        assertTrue(store.prepare(prepared("d5", 500L)));
        assertTrue(store.markQueued("d5", key));
        assertEquals("d5", store.findByMessage(key).orElseThrow().dispatchId());

        backend.failNext = true;
        assertFalse(store.markCompleted(key, false));
        assertEquals(DispatchState.QUEUED, store.find("d5").orElseThrow().state());
        assertTrue(store.findDispatchId(key).isPresent());

        assertTrue(store.markCompleted(key, false));
        assertEquals(DispatchState.COMPLETED_FILE_RETAINED,
                store.find("d5").orElseThrow().state());
        assertTrue(store.findDispatchId(key).isEmpty());

        assertTrue(store.markFileDeleted("d5"));
        assertEquals(DispatchState.COMPLETED, store.find("d5").orElseThrow().state());
    }

    @Test
    public void retainedCountForDestinationCountsOnlyNonTerminalDispatches() {
        FakeBackend backend = new FakeBackend();
        PendingDispatchStore store = new PendingDispatchStore(backend);
        store.prepare(preparedFor("a1", "destA", 100L));
        store.prepare(preparedFor("a2", "destA", 101L));
        store.prepare(preparedFor("b1", "destB", 200L));
        store.markQueued("a1", new PendingMessageKey(100L, -20L));
        store.markCompleted(new PendingMessageKey(100L, -20L), true);

        assertEquals(1, store.retainedCountForDestination("destA"));
        assertEquals(1, store.retainedCountForDestination("destB"));
        assertEquals(0, store.retainedCountForDestination("missing"));
    }

    private static PendingDispatch prepared(String id, long chatId) {
        return preparedFor(id, id, chatId);
    }

    private static PendingDispatch preparedFor(String id, String localId, long chatId) {
        DispatchTargetSnapshot target = DispatchTargetSnapshot.restore(
                1L, localId, 7L, chatId, chatId + 1,
                id + "_bot", id + "_bot", "Title", "Alias", 1L, 2L);
        return PendingDispatch.prepare(id, target, "/tmp/" + id + ".ogg", 2, 3L);
    }

    private static final class FakeBackend implements PendingDispatchStore.Backend {
        private final Map<String, String> values = new HashMap<>();
        private boolean failNext;

        @Override public Map<String, String> readAll() { return new HashMap<>(values); }
        @Override public String read(String key) { return values.get(key); }
        @Override public boolean commit(Map<String, String> puts, Set<String> removes) {
            if (failNext) {
                failNext = false;
                return false;
            }
            Map<String, String> next = new HashMap<>(values);
            next.putAll(puts);
            for (String key : removes) next.remove(key);
            values.clear();
            values.putAll(next);
            return true;
        }
    }
}

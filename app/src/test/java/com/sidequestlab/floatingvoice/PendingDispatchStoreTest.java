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

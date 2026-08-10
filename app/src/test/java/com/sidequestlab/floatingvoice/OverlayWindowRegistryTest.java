package com.sidequestlab.floatingvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class OverlayWindowRegistryTest {
    @Test public void failedRemovalIsRetainedAndRemoveAllRetriesIt() {
        FakeBackend backend = new FakeBackend();
        OverlayWindowRegistry<Object, Object> registry = new OverlayWindowRegistry<>(backend);
        Object view = new Object();
        registry.add(view, new Object());
        backend.failNextRemove = true;

        assertFalse(registry.remove(view));
        assertEquals(1, registry.attachedCount());
        assertTrue(registry.removeAll());
        assertEquals(0, registry.attachedCount());
        assertEquals(2, backend.removeCalls);
    }

    @Test public void successfulRemovalIsIdempotent() {
        FakeBackend backend = new FakeBackend();
        OverlayWindowRegistry<Object, Object> registry = new OverlayWindowRegistry<>(backend);
        Object view = new Object();
        registry.add(view, new Object());

        assertTrue(registry.remove(view));
        assertTrue(registry.remove(view));
        assertEquals(1, backend.removeCalls);
    }

    @Test public void addFailureDoesNotRegisterTheView() {
        FakeBackend backend = new FakeBackend();
        backend.failAdd = true;
        OverlayWindowRegistry<Object, Object> registry = new OverlayWindowRegistry<>(backend);

        try {
            registry.add(new Object(), new Object());
        } catch (IllegalStateException expected) {
            // expected
        }

        assertEquals(0, registry.attachedCount());
    }

    private static final class FakeBackend implements OverlayWindowRegistry.Backend<Object, Object> {
        boolean failAdd;
        boolean failNextRemove;
        int removeCalls;
        final List<Object> updated = new ArrayList<>();

        @Override public void add(Object view, Object params) {
            if (failAdd) throw new IllegalStateException("add");
        }
        @Override public void update(Object view, Object params) {
            updated.add(view);
        }
        @Override public void remove(Object view) {
            removeCalls++;
            if (failNextRemove) {
                failNextRemove = false;
                throw new IllegalStateException("remove");
            }
        }
    }
}

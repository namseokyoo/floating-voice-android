package com.sidequestlab.floatingvoice;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

final class OverlayWindowRegistry<V, P> {
    interface Backend<V, P> {
        void add(V view, P params);
        void update(V view, P params);
        void remove(V view);
    }

    private final Backend<V, P> backend;
    private final Map<V, Boolean> attachedViews = new IdentityHashMap<>();

    OverlayWindowRegistry(Backend<V, P> backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    void add(V view, P params) {
        backend.add(view, params);
        attachedViews.put(view, Boolean.TRUE);
    }

    void update(V view, P params) {
        if (view != null && attachedViews.containsKey(view)) backend.update(view, params);
    }

    boolean remove(V view) {
        if (view == null || !attachedViews.containsKey(view)) return true;
        try {
            backend.remove(view);
            attachedViews.remove(view);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    boolean removeAll() {
        boolean removedAll = true;
        for (V view : new ArrayList<>(attachedViews.keySet())) {
            removedAll &= remove(view);
        }
        return removedAll;
    }

    boolean removeAllWithRetries(int maxAttempts) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts");
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (removeAll()) return true;
        }
        return attachedViews.isEmpty();
    }

    int attachedCount() {
        return attachedViews.size();
    }
}

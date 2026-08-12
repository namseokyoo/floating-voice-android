package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.content.SharedPreferences;

import com.sidequestlab.floatingvoice.core.PendingDispatch;
import com.sidequestlab.floatingvoice.core.PendingDispatchCodec;
import com.sidequestlab.floatingvoice.core.PendingMessageKey;

import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Atomically stores durable dispatch records and chat-scoped TDLib message mappings. */
public final class PendingDispatchStore {
    private static final String RECORD_PREFIX = "dispatch_";
    private static final String MESSAGE_PREFIX = "message_";

    interface Backend {
        Map<String, String> readAll();
        String read(String key);
        boolean commit(Map<String, String> puts, Set<String> removes);
    }

    private final Backend backend;

    public PendingDispatchStore(Context context) {
        this(new PreferencesBackend(context.getSharedPreferences(
                "pending_voice_dispatches", Context.MODE_PRIVATE)));
    }

    PendingDispatchStore(Backend backend) {
        this.backend = backend;
    }

    public synchronized boolean prepare(PendingDispatch dispatch) {
        if (find(dispatch.dispatchId()).isPresent()) return false;
        return backend.commit(Map.of(recordKey(dispatch.dispatchId()), encode(dispatch)), Set.of());
    }

    public synchronized Optional<PendingDispatch> find(String dispatchId) {
        return decode(backend.read(recordKey(dispatchId)));
    }

    public synchronized Optional<String> findDispatchId(PendingMessageKey message) {
        String value = backend.read(messageKey(message));
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    public synchronized Optional<PendingDispatch> findByMessage(PendingMessageKey message) {
        return findDispatchId(message).flatMap(this::find);
    }

    public synchronized boolean markQueued(String dispatchId, PendingMessageKey message) {
        Optional<PendingDispatch> current = find(dispatchId);
        if (current.isEmpty() || current.get().target().chatId() != message.chatId()) return false;
        Optional<String> currentOwner = findDispatchId(message);
        if (currentOwner.isPresent() && !currentOwner.get().equals(dispatchId)) return false;
        PendingDispatch queued = current.get().queued(message.temporaryMessageId());
        if (queued == current.get()) return false;
        return backend.commit(Map.of(
                recordKey(dispatchId), encode(queued),
                messageKey(message), dispatchId), Set.of());
    }

    public synchronized boolean markQueuePersistenceUnknown(
            String dispatchId, long temporaryMessageId) {
        Optional<PendingDispatch> current = find(dispatchId);
        if (current.isEmpty()) return false;
        PendingDispatch uncertain = current.get()
                .queued(temporaryMessageId)
                .recoveredAfterRestart();
        if (uncertain == current.get()) return false;
        return backend.commit(Map.of(recordKey(dispatchId), encode(uncertain)), Set.of());
    }

    public synchronized boolean markFailed(PendingMessageKey message, int errorCode,
                                           String errorMessage, boolean canRetry,
                                           int retryAfterSeconds) {
        Optional<String> dispatchId = findDispatchId(message);
        return dispatchId.isPresent() && markFailed(dispatchId.get(), message,
                errorCode, errorMessage, canRetry, retryAfterSeconds);
    }

    public synchronized boolean markFailed(String expectedDispatchId, PendingMessageKey message,
                                           int errorCode, String errorMessage, boolean canRetry,
                                           int retryAfterSeconds) {
        Optional<String> owner = findDispatchId(message);
        if (owner.isEmpty() || !owner.get().equals(expectedDispatchId)) return false;
        Optional<PendingDispatch> current = find(expectedDispatchId);
        if (current.isEmpty()) return false;
        PendingDispatch failed = current.get().failedRetained(
                errorCode, errorMessage, canRetry, retryAfterSeconds);
        return backend.commit(Map.of(recordKey(expectedDispatchId), encode(failed)),
                Set.of(messageKey(message)));
    }

    /** Marks a dispatch that never produced a TDLib temporary message as failed and retained. */
    public synchronized boolean markRejected(String dispatchId, int errorCode,
                                             String errorMessage) {
        Optional<PendingDispatch> current = find(dispatchId);
        if (current.isEmpty()) return false;
        PendingDispatch failed = current.get().failedRetained(
                errorCode, errorMessage, false, 0);
        return backend.commit(Map.of(recordKey(dispatchId), encode(failed)), Set.of());
    }

    public synchronized boolean markCompleted(PendingMessageKey message, boolean fileDeleted) {
        Optional<String> dispatchId = findDispatchId(message);
        return dispatchId.isPresent()
                && markCompleted(dispatchId.get(), message, fileDeleted);
    }

    public synchronized boolean markCompleted(String expectedDispatchId,
                                               PendingMessageKey message,
                                               boolean fileDeleted) {
        Optional<String> owner = findDispatchId(message);
        if (owner.isEmpty() || !owner.get().equals(expectedDispatchId)) return false;
        Optional<PendingDispatch> current = find(expectedDispatchId);
        if (current.isEmpty()) return false;
        PendingDispatch completed = current.get().completed(fileDeleted);
        return backend.commit(Map.of(recordKey(expectedDispatchId), encode(completed)),
                Set.of(messageKey(message)));
    }

    public synchronized boolean markFileDeleted(String dispatchId) {
        Optional<PendingDispatch> current = find(dispatchId);
        if (current.isEmpty()) return false;
        PendingDispatch completed = current.get().fileDeletedAfterCompletion();
        if (completed == current.get()) return false;
        return backend.commit(Map.of(recordKey(dispatchId), encode(completed)), Set.of());
    }

    public synchronized int markUncertainAfterRestart() {
        Map<String, String> updates = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : backend.readAll().entrySet()) {
            if (!entry.getKey().startsWith(RECORD_PREFIX)) continue;
            Optional<PendingDispatch> decoded = decode(entry.getValue());
            if (decoded.isEmpty()) continue;
            PendingDispatch uncertain = decoded.get().recoveredAfterRestart();
            if (uncertain != decoded.get()) updates.put(entry.getKey(), encode(uncertain));
        }
        return updates.isEmpty() || !backend.commit(updates, Set.of()) ? 0 : updates.size();
    }

    /** Marks one pre-final dispatch uncertain without attaching a message identity. */
    public synchronized boolean markUncertain(String dispatchId) {
        Optional<PendingDispatch> current = find(dispatchId);
        if (current.isEmpty()) return false;
        PendingDispatch uncertain = current.get().recoveredAfterRestart();
        if (uncertain == current.get()) return false;
        return backend.commit(Map.of(recordKey(dispatchId), encode(uncertain)), Set.of());
    }

    /** Separates a closing TDLib account from all durable temporary-message identities. */
    public synchronized int markUncertainAndDetachMessages() {
        Map<String, String> updates = new LinkedHashMap<>();
        Set<String> removals = new java.util.HashSet<>();
        for (Map.Entry<String, String> entry : backend.readAll().entrySet()) {
            if (entry.getKey().startsWith(MESSAGE_PREFIX)) {
                removals.add(entry.getKey());
                continue;
            }
            if (!entry.getKey().startsWith(RECORD_PREFIX)) continue;
            Optional<PendingDispatch> decoded = decode(entry.getValue());
            if (decoded.isEmpty()) continue;
            PendingDispatch uncertain = decoded.get().recoveredAfterRestart();
            if (uncertain != decoded.get()) updates.put(entry.getKey(), encode(uncertain));
        }
        if (updates.isEmpty() && removals.isEmpty()) return 0;
        return backend.commit(updates, removals) ? updates.size() : 0;
    }

    public synchronized int retainedCount() {
        int count = 0;
        for (Map.Entry<String, String> entry : backend.readAll().entrySet()) {
            if (entry.getKey().startsWith(RECORD_PREFIX) && decode(entry.getValue()).isPresent()) {
                count++;
            }
        }
        return count;
    }

    /** Counts non-terminal dispatch records that were frozen to one destination snapshot. */
    public synchronized int retainedCountForDestination(String localId) {
        int count = 0;
        for (Map.Entry<String, String> entry : backend.readAll().entrySet()) {
            if (!entry.getKey().startsWith(RECORD_PREFIX)) continue;
            Optional<PendingDispatch> dispatch = decode(entry.getValue());
            if (dispatch.isEmpty()) continue;
            if (!dispatch.get().target().localId().equals(localId)) continue;
            switch (dispatch.get().state()) {
                case COMPLETED, COMPLETED_FILE_RETAINED -> { }
                default -> count++;
            }
        }
        return count;
    }

    private static String recordKey(String dispatchId) {
        return RECORD_PREFIX + dispatchId;
    }

    private static String messageKey(PendingMessageKey message) {
        return MESSAGE_PREFIX + message.storageSuffix();
    }

    private static String encode(PendingDispatch dispatch) {
        return Base64.getEncoder().encodeToString(PendingDispatchCodec.encode(dispatch));
    }

    private static Optional<PendingDispatch> decode(String encoded) {
        if (encoded == null) return Optional.empty();
        try {
            return Optional.of(PendingDispatchCodec.decode(Base64.getDecoder().decode(encoded)));
        } catch (RuntimeException error) {
            return Optional.empty();
        }
    }

    private static final class PreferencesBackend implements Backend {
        private final SharedPreferences preferences;

        private PreferencesBackend(SharedPreferences preferences) {
            this.preferences = preferences;
        }

        @Override public Map<String, String> readAll() {
            Map<String, String> values = new HashMap<>();
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (entry.getValue() instanceof String value) values.put(entry.getKey(), value);
            }
            return Collections.unmodifiableMap(values);
        }

        @Override public String read(String key) {
            return preferences.getString(key, null);
        }

        @Override public boolean commit(Map<String, String> puts, Set<String> removes) {
            SharedPreferences.Editor editor = preferences.edit();
            for (Map.Entry<String, String> entry : puts.entrySet()) {
                editor.putString(entry.getKey(), entry.getValue());
            }
            for (String key : removes) editor.remove(key);
            return editor.commit();
        }
    }
}

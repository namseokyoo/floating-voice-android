package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/** Persists temporary TDLib message-id to recording-path mappings across process restarts. */
public final class PendingRecordingStore {
    private static final String PREFIX = "message_";
    private final SharedPreferences preferences;

    public PendingRecordingStore(Context context) {
        preferences = context.getSharedPreferences("pending_voice_recordings", Context.MODE_PRIVATE);
    }

    public synchronized void put(long temporaryMessageId, String absolutePath) {
        preferences.edit().putString(PREFIX + temporaryMessageId, absolutePath).apply();
    }

    public synchronized String take(long temporaryMessageId) {
        String key = PREFIX + temporaryMessageId;
        String value = preferences.getString(key, null);
        if (value != null) preferences.edit().remove(key).apply();
        return value;
    }

    public synchronized String peek(long temporaryMessageId) {
        return preferences.getString(PREFIX + temporaryMessageId, null);
    }

    public synchronized Map<Long, String> all() {
        Map<Long, String> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith(PREFIX) && entry.getValue() instanceof String) {
                try {
                    result.put(Long.parseLong(entry.getKey().substring(PREFIX.length())),
                            (String) entry.getValue());
                } catch (NumberFormatException ignored) { }
            }
        }
        return result;
    }
}

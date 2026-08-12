package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.content.SharedPreferences;

import com.sidequestlab.floatingvoice.core.PendingMessageKey;

import java.util.Map;

/** Persists chat-scoped TDLib temporary-message IDs to recording paths. */
public final class PendingRecordingStore {
    private static final String PREFIX = "message_";
    private final SharedPreferences preferences;

    public PendingRecordingStore(Context context) {
        preferences = context.getSharedPreferences("pending_voice_recordings", Context.MODE_PRIVATE);
    }

    public synchronized void put(PendingMessageKey message, String absolutePath) {
        preferences.edit().putString(key(message), absolutePath).apply();
    }

    public synchronized String take(PendingMessageKey message) {
        String key = key(message);
        String value = preferences.getString(key, null);
        if (value != null) preferences.edit().remove(key).apply();
        return value;
    }

    /** Peeks without consuming so failure updates never lose the retained file. */
    public synchronized String get(PendingMessageKey message) {
        return preferences.getString(key(message), null);
    }

    /** Migrates v0.6.1's single-target keys before the Telegram client starts. */
    public synchronized void migrateLegacy(long chatId) {
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String legacyKey = entry.getKey();
            if (!legacyKey.startsWith(PREFIX)
                    || legacyKey.substring(PREFIX.length()).contains("_")
                    || !(entry.getValue() instanceof String)) continue;
            try {
                long temporaryId = Long.parseLong(legacyKey.substring(PREFIX.length()));
                String scopedKey = key(new PendingMessageKey(chatId, temporaryId));
                if (!preferences.contains(scopedKey)) {
                    editor.putString(scopedKey, (String) entry.getValue());
                }
                editor.remove(legacyKey);
                changed = true;
            } catch (NumberFormatException ignored) { }
        }
        if (changed) editor.apply();
    }

    private static String key(PendingMessageKey message) {
        return PREFIX + message.storageSuffix();
    }
}

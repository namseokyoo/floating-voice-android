package com.sidequestlab.floatingvoice;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import com.sidequestlab.floatingvoice.core.PendingMessageKey;

import java.util.Map;

/** Persists chat-scoped TDLib temporary text-message IDs without message content. */
final class PendingTextSendStore {
    private static final String PREFIX = "message_";
    private final SharedPreferences preferences;

    PendingTextSendStore(Context context) {
        preferences = context.getSharedPreferences("pending_text_sends", Context.MODE_PRIVATE);
    }

    @SuppressLint("ApplySharedPref") // TDLib callback thread; ID must be durable before return.
    synchronized void put(PendingMessageKey message) {
        preferences.edit().putBoolean(key(message), true).commit();
    }

    @SuppressLint("ApplySharedPref") // TDLib update thread; removal must be durable before return.
    synchronized boolean take(PendingMessageKey message) {
        String key = key(message);
        if (!preferences.contains(key)) return false;
        preferences.edit().remove(key).commit();
        return true;
    }

    /** Migrates v0.6.1's single-target keys before the Telegram client starts. */
    synchronized void migrateLegacy(long chatId) {
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String legacyKey = entry.getKey();
            if (!legacyKey.startsWith(PREFIX)
                    || legacyKey.substring(PREFIX.length()).contains("_")
                    || !(entry.getValue() instanceof Boolean)) continue;
            try {
                long temporaryId = Long.parseLong(legacyKey.substring(PREFIX.length()));
                String scopedKey = key(new PendingMessageKey(chatId, temporaryId));
                if (!preferences.contains(scopedKey)) {
                    editor.putBoolean(scopedKey, true);
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

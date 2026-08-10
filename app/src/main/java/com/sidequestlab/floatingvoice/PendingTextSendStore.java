package com.sidequestlab.floatingvoice;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

/** Persists temporary TDLib text-message IDs without storing message content. */
final class PendingTextSendStore {
    private static final String PREFIX = "message_";
    private final SharedPreferences preferences;

    PendingTextSendStore(Context context) {
        preferences = context.getSharedPreferences("pending_text_sends", Context.MODE_PRIVATE);
    }

    @SuppressLint("ApplySharedPref") // TDLib callback thread; ID must be durable before return.
    synchronized void put(long temporaryMessageId) {
        preferences.edit().putBoolean(PREFIX + temporaryMessageId, true).commit();
    }

    @SuppressLint("ApplySharedPref") // TDLib update thread; removal must be atomic before return.
    synchronized boolean take(long temporaryMessageId) {
        String key = PREFIX + temporaryMessageId;
        if (!preferences.contains(key)) return false;
        preferences.edit().remove(key).commit();
        return true;
    }
}

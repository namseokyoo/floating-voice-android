package com.sidequestlab.floatingvoice.core;

import java.util.Objects;

/** TDLib temporary message identity scoped to its chat. */
public final class PendingMessageKey {
    private final long chatId;
    private final long temporaryMessageId;

    public PendingMessageKey(long chatId, long temporaryMessageId) {
        this.chatId = chatId;
        this.temporaryMessageId = temporaryMessageId;
    }

    public long chatId() { return chatId; }
    public long temporaryMessageId() { return temporaryMessageId; }
    public String storageSuffix() { return chatId + "_" + temporaryMessageId; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PendingMessageKey)) return false;
        PendingMessageKey key = (PendingMessageKey) other;
        return chatId == key.chatId && temporaryMessageId == key.temporaryMessageId;
    }

    @Override public int hashCode() {
        return Objects.hash(chatId, temporaryMessageId);
    }
}

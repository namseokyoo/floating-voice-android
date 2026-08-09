package com.sidequestlab.floatingvoice;

import java.util.Objects;

public final class TargetChat {
    private final long chatId;
    private final String title;
    private final String username;

    public TargetChat(long chatId, String title, String username) {
        this.chatId = chatId;
        this.title = Objects.requireNonNull(title);
        this.username = Objects.requireNonNull(username);
    }

    public long chatId() { return chatId; }
    public String title() { return title; }
    public String username() { return username; }

    @Override public String toString() {
        return title + " (@" + username + ", " + chatId + ")";
    }
}

package com.sidequestlab.floatingvoice.core;

/** Immutable raw snapshot of the v0.4.1-v0.6.4 encrypted settings keys. */
public final class LegacyConfigSnapshot {
    private final String apiIdText;
    private final String apiHash;
    private final String phoneNumber;
    private final String botUsername;
    private final String targetChatIdText;
    private final String targetTitle;
    private final String targetUsername;
    private final String pendingRecordingPath;

    public LegacyConfigSnapshot(String apiIdText, String apiHash, String phoneNumber,
                                String botUsername, String targetChatIdText,
                                String targetTitle, String targetUsername,
                                String pendingRecordingPath) {
        this.apiIdText = apiIdText;
        this.apiHash = apiHash;
        this.phoneNumber = phoneNumber;
        this.botUsername = botUsername;
        this.targetChatIdText = targetChatIdText;
        this.targetTitle = targetTitle;
        this.targetUsername = targetUsername;
        this.pendingRecordingPath = pendingRecordingPath;
    }

    public String apiIdText() { return apiIdText; }
    public String apiHash() { return apiHash; }
    public String phoneNumber() { return phoneNumber; }
    public String botUsername() { return botUsername; }
    public String targetChatIdText() { return targetChatIdText; }
    public String targetTitle() { return targetTitle; }
    public String targetUsername() { return targetUsername; }
    public String pendingRecordingPath() { return pendingRecordingPath; }
}

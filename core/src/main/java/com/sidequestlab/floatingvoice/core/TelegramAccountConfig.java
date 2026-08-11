package com.sidequestlab.floatingvoice.core;

import java.util.Objects;

/** Telegram connection identity separated from any destination or transient auth secret. */
public final class TelegramAccountConfig {
    private final int apiId;
    private final String apiHash;
    private final String phoneNumber;

    TelegramAccountConfig(int apiId, String apiHash, String phoneNumber) {
        this.apiId = apiId;
        this.apiHash = Objects.requireNonNull(apiHash);
        this.phoneNumber = Objects.requireNonNull(phoneNumber);
    }

    public int apiId() { return apiId; }
    public String apiHash() { return apiHash; }
    public String phoneNumber() { return phoneNumber; }
}

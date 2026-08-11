package com.sidequestlab.floatingvoice.core;

/** Presentation-only formatting for persisted Telegram connection identifiers. */
public final class ConnectionInfoFormatter {
    private static final String MASK = "••••••••••••";

    private ConnectionInfoFormatter() { }

    public static String maskApiHash(String apiHash) {
        String suffix = apiHashSuffix(apiHash);
        return suffix.isEmpty() ? MASK : MASK + suffix;
    }

    public static String apiHashSuffix(String apiHash) {
        String normalized = apiHash == null ? "" : apiHash.trim();
        return normalized.length() < 4 ? "" : normalized.substring(normalized.length() - 4);
    }
}

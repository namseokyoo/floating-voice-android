package com.sidequestlab.messvoice.core;

import java.util.Locale;
import java.util.regex.Pattern;

public final class UsernameNormalizer {
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9_]{5,32}");

    private UsernameNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        value = value.replaceFirst("(?i)^https?://", "");
        value = value.replaceFirst("(?i)^(?:www\\.)?(?:t\\.me|telegram\\.me)/", "");
        value = value.replaceFirst("^@+", "");
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValid(String normalizedUsername) {
        return normalizedUsername != null && USERNAME.matcher(normalizedUsername).matches();
    }
}

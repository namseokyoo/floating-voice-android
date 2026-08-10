package com.sidequestlab.floatingvoice.core;

public final class RecordingElapsedFormatter {
    private RecordingElapsedFormatter() {
    }

    public static String format(long elapsedMs) {
        long totalSeconds = elapsedMs <= 0
                ? 0
                : Math.min(elapsedMs / 1_000, 99 * 60 + 59);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return twoDigits(minutes) + ":" + twoDigits(seconds);
    }

    private static String twoDigits(long value) {
        return value < 10 ? "0" + value : Long.toString(value);
    }
}

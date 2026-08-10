package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RecordingElapsedFormatterTest {
    @Test
    void zeroAndNegativeElapsedTimeClampToZero() {
        assertAll(
                () -> assertEquals("00:00", RecordingElapsedFormatter.format(0)),
                () -> assertEquals("00:00", RecordingElapsedFormatter.format(-1)),
                () -> assertEquals("00:00", RecordingElapsedFormatter.format(Long.MIN_VALUE)));
    }

    @Test
    void millisecondsAreFlooredToWholeSeconds() {
        assertEquals("00:00", RecordingElapsedFormatter.format(999));
    }

    @Test
    void oneSecondFormatsAsExpected() {
        assertEquals("00:01", RecordingElapsedFormatter.format(1_000));
    }

    @Test
    void minuteBoundaryFormatsDeterministically() {
        assertAll(
                () -> assertEquals("00:59", RecordingElapsedFormatter.format(59_999)),
                () -> assertEquals("01:00", RecordingElapsedFormatter.format(60_000)));
    }

    @Test
    void elapsedTimeCapsAtNinetyNineMinutesAndFiftyNineSeconds() {
        assertAll(
                () -> assertEquals("99:59", RecordingElapsedFormatter.format(5_999_000)),
                () -> assertEquals("99:59", RecordingElapsedFormatter.format(6_000_000)),
                () -> assertEquals("99:59", RecordingElapsedFormatter.format(Long.MAX_VALUE)));
    }
}

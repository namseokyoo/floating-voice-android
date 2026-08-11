package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionInfoFormatterTest {
    @Test
    void revealsOnlyLastFourApiHashCharacters() {
        assertEquals("••••••••••••cdef", ConnectionInfoFormatter.maskApiHash(
                "0123456789abcdef0123456789abcdef"));
    }

    @Test
    void hidesMissingOrShortValuesCompletely() {
        assertEquals("••••••••••••", ConnectionInfoFormatter.maskApiHash(null));
        assertEquals("••••••••••••", ConnectionInfoFormatter.maskApiHash("abc"));
    }

    @Test
    void trimsBeforeMasking() {
        assertEquals("••••••••••••CDEF", ConnectionInfoFormatter.maskApiHash(
                " 0123456789abcdef0123456789ABCDEF "));
    }

    @Test
    void providesOnlyTheVisibleSuffixForAccessibility() {
        assertEquals("cdef", ConnectionInfoFormatter.apiHashSuffix(
                "0123456789abcdef0123456789abcdef"));
        assertEquals("", ConnectionInfoFormatter.apiHashSuffix("abc"));
    }
}

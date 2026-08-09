package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UsernameNormalizerTest {
    @Test
    void stripsAtSignAndNormalizesCase() {
        assertEquals("sample_bot", UsernameNormalizer.normalize("  @@Sample_Bot  "));
    }

    @Test
    void acceptsTelegramLinks() {
        assertEquals("sample_bot", UsernameNormalizer.normalize("https://t.me/Sample_Bot?start=abc"));
        assertEquals("sample_bot", UsernameNormalizer.normalize("telegram.me/Sample_Bot/extra"));
    }

    @Test
    void validatesTelegramUsernameShape() {
        assertTrue(UsernameNormalizer.isValid("valid_bot123"));
        assertFalse(UsernameNormalizer.isValid("four"));
        assertFalse(UsernameNormalizer.isValid("has-hyphen"));
        assertFalse(UsernameNormalizer.isValid("a".repeat(33)));
    }
}

package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {
    @Test
    void acceptsValidRuntimeConfigurationAndNormalizesValues() {
        AppConfig.ValidationResult result = AppConfig.validate(
                " 123456 ", "0123456789abcdef0123456789ABCDEF",
                "+82 10-1234-5678", "https://t.me/My_Voice_Bot");

        assertTrue(result.isValid());
        assertEquals(123456, result.config().apiId());
        assertEquals("+821012345678", result.config().phoneNumber());
        assertEquals("my_voice_bot", result.config().botUsername());
    }

    @Test
    void rejectsMissingMalformedAndNonPositiveValues() {
        AppConfig.ValidationResult result = AppConfig.validate(
                "0", "not-a-hash", "010-1234-5678", "bad!");

        assertFalse(result.isValid());
        assertEquals(4, result.errors().size());
    }

    @Test
    void doesNotModelTransientAuthenticationSecrets() {
        assertThrows(IllegalStateException.class,
                () -> AppConfig.validate("bad", "", "", "").config());
    }
}

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
        assertEquals(java.util.List.of(
                AppConfig.ValidationError.INVALID_API_ID,
                AppConfig.ValidationError.INVALID_API_HASH,
                AppConfig.ValidationError.INVALID_PHONE_NUMBER,
                AppConfig.ValidationError.INVALID_BOT_USERNAME), result.errors());
    }

    @Test
    void validationErrorsAreLanguageNeutralCodes() {
        for (AppConfig.ValidationError error : AppConfig.ValidationError.values()) {
            assertTrue(error.name().matches("[A-Z_]+"));
        }
    }

    @Test
    void doesNotModelTransientAuthenticationSecrets() {
        assertThrows(IllegalStateException.class,
                () -> AppConfig.validate("bad", "", "", "").config());
    }
}

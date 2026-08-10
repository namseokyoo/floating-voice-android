package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {
    @Test
    void acceptsValidRuntimeConfigurationAndNormalizesValues() {
        AppConfig.ValidationResult result = AppConfig.validate(
                " 123456 ", "0123456789abcdef0123456789ABCDEF",
                "+1 202-555-0123", "https://t.me/My_Voice_Bot");

        assertTrue(result.isValid());
        assertEquals(123456, result.config().apiId());
        assertEquals("+12025550123", result.config().phoneNumber());
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
    void acceptsConnectionBeforeTargetSelection() {
        AppConfig.ValidationResult result = AppConfig.validateConnection(
                "123456", "0123456789abcdef0123456789abcdef", "+821012345678");

        assertTrue(result.isValid());
        assertFalse(result.config().hasBotUsername());
        assertEquals("", result.config().botUsername());
    }

    @Test
    void connectionIdentityExcludesTargetButIncludesAccountInputs() {
        AppConfig a = new AppConfig(1, "0123456789abcdef0123456789abcdef",
                "+821012345678", "first_bot");
        AppConfig sameConnection = new AppConfig(1, "0123456789ABCDEF0123456789ABCDEF",
                "+821012345678", "second_bot");
        AppConfig otherPhone = new AppConfig(1, "0123456789abcdef0123456789abcdef",
                "+821087654321", "first_bot");

        assertTrue(a.hasSameConnection(sameConnection));
        assertFalse(a.hasSameConnection(otherPhone));
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

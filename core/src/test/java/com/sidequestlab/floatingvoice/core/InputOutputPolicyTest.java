package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputOutputPolicyTest {
    @Test
    void exposesExactlyThreeInputModesWithTelegramDefaults() {
        assertEquals(List.of(
                InputMode.RAW_VOICE,
                InputMode.TYPED_TEXT,
                InputMode.SPEECH_TO_TEXT), List.of(InputMode.values()));
        assertEquals(OutputRoute.TELEGRAM_VOICE,
                InputOutputPolicy.defaultRoute(InputMode.RAW_VOICE));
        assertEquals(OutputRoute.TELEGRAM_TEXT,
                InputOutputPolicy.defaultRoute(InputMode.TYPED_TEXT));
        assertEquals(OutputRoute.TELEGRAM_TEXT,
                InputOutputPolicy.defaultRoute(InputMode.SPEECH_TO_TEXT));
    }

    @Test
    void rawVoiceAlternativesAreOnlyExistingAudioRoutes() {
        assertEquals(List.of(
                OutputRoute.TELEGRAM_VOICE,
                OutputRoute.SYSTEM_AUDIO_SHARE,
                OutputRoute.LOCAL_AUDIO_ARCHIVE),
                InputOutputPolicy.oneOperationRoutes(InputMode.RAW_VOICE));
        assertFalse(InputOutputPolicy.allows(
                InputMode.RAW_VOICE, OutputRoute.SYSTEM_TEXT_SHARE));
    }

    @Test
    void textModesAllowVerifiedTelegramOrAndroidTextShareOnly() {
        List<OutputRoute> expected = List.of(
                OutputRoute.TELEGRAM_TEXT,
                OutputRoute.SYSTEM_TEXT_SHARE);
        assertEquals(expected,
                InputOutputPolicy.oneOperationRoutes(InputMode.TYPED_TEXT));
        assertEquals(expected,
                InputOutputPolicy.oneOperationRoutes(InputMode.SPEECH_TO_TEXT));
        assertTrue(InputOutputPolicy.allows(
                InputMode.TYPED_TEXT, OutputRoute.SYSTEM_TEXT_SHARE));
        assertFalse(InputOutputPolicy.allows(
                InputMode.SPEECH_TO_TEXT, OutputRoute.LOCAL_AUDIO_ARCHIVE));
    }
}

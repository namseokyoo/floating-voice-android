package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureCapabilitiesTest {
    @Test
    void telegramUnavailableDoesNotBlockAvailableSttOrOverlay() {
        CaptureCapabilities capabilities = new CaptureCapabilities(false, true, false, false);

        assertTrue(capabilities.decide(CaptureCapabilities.Action.SHOW_OVERLAY).allowed());
        assertTrue(capabilities.decide(CaptureCapabilities.Action.START_STT).allowed());
        CaptureCapabilities.Decision telegram = capabilities.decide(
                CaptureCapabilities.Action.START_TELEGRAM_RECORDING);
        assertFalse(telegram.allowed());
        assertEquals(CaptureCapabilities.Action.START_TELEGRAM_RECORDING,
                telegram.requestedAction());
        assertEquals(CaptureCapabilities.UnavailableReason.TELEGRAM_UNAVAILABLE,
                telegram.unavailableReason());
    }

    @Test
    void selectedAvailableLocalOutputAllowsOnlyThatLocalCapture() {
        CaptureCapabilities capabilities = new CaptureCapabilities(false, false, true, true);

        assertTrue(capabilities.decide(CaptureCapabilities.Action.SHOW_OVERLAY).allowed());
        assertTrue(capabilities.decide(CaptureCapabilities.Action.START_LOCAL_CAPTURE).allowed());
        assertFalse(capabilities.decide(
                CaptureCapabilities.Action.START_TELEGRAM_RECORDING).allowed());
        assertFalse(capabilities.decide(CaptureCapabilities.Action.START_STT).allowed());
    }

    @Test
    void unavailableActionNeverFallsBackToAnotherAvailableRoute() {
        CaptureCapabilities capabilities = new CaptureCapabilities(false, true, true, true);

        CaptureCapabilities.Decision decision = capabilities.decide(
                CaptureCapabilities.Action.START_TELEGRAM_RECORDING);

        assertFalse(decision.allowed());
        assertEquals(CaptureCapabilities.Action.START_TELEGRAM_RECORDING,
                decision.requestedAction());
        assertEquals(CaptureCapabilities.Action.START_TELEGRAM_RECORDING,
                decision.effectiveAction());
        assertFalse(decision.fellBack());
    }

    @Test
    void unselectedOrUnavailableLocalOutputCannotCapture() {
        CaptureCapabilities unselected = new CaptureCapabilities(false, false, false, true);
        assertEquals(CaptureCapabilities.UnavailableReason.LOCAL_OUTPUT_NOT_SELECTED,
                unselected.decide(CaptureCapabilities.Action.START_LOCAL_CAPTURE)
                        .unavailableReason());

        CaptureCapabilities unavailable = new CaptureCapabilities(false, false, true, false);
        assertEquals(CaptureCapabilities.UnavailableReason.LOCAL_OUTPUT_UNAVAILABLE,
                unavailable.decide(CaptureCapabilities.Action.START_LOCAL_CAPTURE)
                        .unavailableReason());
        assertFalse(unavailable.decide(CaptureCapabilities.Action.SHOW_OVERLAY).allowed());
    }
}

package com.sidequestlab.floatingvoice;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OverlayCapabilityPolicyTest {
    @Test
    public void independentStartActionExposesSystemShareBeforeTelegramSetup() {
        assertTrue(OverlayCapabilityPolicy.independentStartActionVisible(false, false));
        assertFalse(OverlayCapabilityPolicy.independentStartActionVisible(false, true));
        assertFalse(OverlayCapabilityPolicy.independentStartActionVisible(true, false));
    }

    @Test
    public void permissionsAllowSystemTextShareWithoutTelegram() {
        OverlayCapabilityPolicy.Snapshot capabilities = OverlayCapabilityPolicy.evaluate(
                true, true, true, false, false, false);

        assertTrue(capabilities.serviceAvailable());
        assertTrue(capabilities.systemTextShareAvailable());
        assertFalse(capabilities.telegramOutputAvailable());
    }

    @Test
    public void missingGlobalPermissionBlocksEveryOverlayCapability() {
        OverlayCapabilityPolicy.Snapshot capabilities = OverlayCapabilityPolicy.evaluate(
                false, true, true, true, true, false);

        assertFalse(capabilities.serviceAvailable());
        assertFalse(capabilities.systemTextShareAvailable());
        assertFalse(capabilities.telegramOutputAvailable());
    }

    @Test
    public void telegramOutputRequiresAuthenticationAndResolvedRoute() {
        assertTrue(OverlayCapabilityPolicy.evaluate(
                true, true, true, true, true, false).telegramOutputAvailable());
        assertFalse(OverlayCapabilityPolicy.evaluate(
                true, true, true, true, false, true).telegramOutputAvailable());
        assertFalse(OverlayCapabilityPolicy.evaluate(
                true, true, true, true, false, false).telegramOutputAvailable());
    }
}

package com.sidequestlab.floatingvoice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SpeechTelegramHandoffRegistryTest {
    @Test
    public void terminalResultPublishedDuringReceiverGapCanBeReplayedAfterRotation() {
        SpeechTelegramHandoffRegistry registry = new SpeechTelegramHandoffRegistry();

        registry.publish(42L, SpeechTelegramHandoffRegistry.Status.DELIVERED);

        SpeechTelegramHandoffRegistry.Event replay = registry.latest(42L).orElseThrow();
        assertEquals(SpeechTelegramHandoffRegistry.Status.DELIVERED, replay.status());
        assertTrue(registry.latest(99L).isEmpty());
        registry.clear(42L);
        assertTrue(registry.latest(42L).isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidHandoffIdCannotPublishResult() {
        new SpeechTelegramHandoffRegistry().publish(
                0L, SpeechTelegramHandoffRegistry.Status.REJECTED);
    }
}

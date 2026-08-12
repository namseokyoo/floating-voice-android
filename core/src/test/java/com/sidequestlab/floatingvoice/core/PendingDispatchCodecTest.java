package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PendingDispatchCodecTest {
    @Test
    void roundTripPreservesEveryRoutingAndFailureField() {
        PendingDispatch source = PendingDispatch.prepare(
                "dispatch-encoded", snapshot(), "/private/voice.ogg", 9,
                1_700_000_500_000L)
                .queued(-55L)
                .failedRetained(429, "retry later", true, 45);

        PendingDispatch decoded = PendingDispatchCodec.decode(
                PendingDispatchCodec.encode(source));

        assertEquals(source, decoded);
        assertEquals(700L, decoded.target().chatId());
        assertEquals(900L, decoded.target().peerUserId());
        assertEquals(8L, decoded.target().verificationRevision());
        assertFalse(decoded.automaticRetryAllowed());
    }

    @Test
    void corruptOrFuturePayloadIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PendingDispatchCodec.decode(new byte[] {0, 0, 0, 99}));
        byte[] valid = PendingDispatchCodec.encode(PendingDispatch.prepare(
                "dispatch-valid", snapshot(), "/private/voice.ogg", 1,
                1_700_000_500_000L));
        valid[valid.length - 1] ^= 0x01;
        assertThrows(IllegalArgumentException.class,
                () -> PendingDispatchCodec.decode(valid));
    }

    private static DispatchTargetSnapshot snapshot() {
        return DispatchTargetSnapshot.restore(
                12L, "destination", 7L, 700L, 900L,
                "configured_bot", "resolved_bot", "Resolved title", "My alias",
                8L, 1_700_000_400_000L);
    }
}

package com.sidequestlab.floatingvoice;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientGenerationGateTest {
    @Test
    public void onlyCurrentClientGenerationMayDeliverUpdates() {
        assertTrue(ClientGenerationGate.current(4L, 4L));
        assertFalse(ClientGenerationGate.current(3L, 4L));
        assertFalse(ClientGenerationGate.current(4L, 5L));
    }
}
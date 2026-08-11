package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class PendingMessageKeyTest {
    @Test
    void sameTemporaryIdInDifferentChatsDoesNotCollide() {
        PendingMessageKey first = new PendingMessageKey(100L, -7L);
        PendingMessageKey second = new PendingMessageKey(200L, -7L);

        assertNotEquals(first, second);
        assertNotEquals(first.storageSuffix(), second.storageSuffix());
    }

    @Test
    void sameChatAndTemporaryIdAreStable() {
        PendingMessageKey first = new PendingMessageKey(-100L, -9L);
        PendingMessageKey second = new PendingMessageKey(-100L, -9L);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals("-100_-9", first.storageSuffix());
    }
}

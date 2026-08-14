package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioCaptureOwnershipTest {
    @Test
    void recordingAndSttAreMutuallyExclusive() {
        AudioCaptureOwnership ownership = new AudioCaptureOwnership();
        AudioCaptureOwnership.Lease recording = ownership.acquire(
                AudioCaptureOwnership.Owner.RECORDING).orElseThrow();

        assertTrue(ownership.acquire(AudioCaptureOwnership.Owner.STT).isEmpty());
        assertTrue(ownership.acquire(AudioCaptureOwnership.Owner.RECORDING).isEmpty());
        assertEquals(AudioCaptureOwnership.Owner.RECORDING, ownership.owner());
        assertTrue(ownership.release(recording));

        AudioCaptureOwnership.Lease stt = ownership.acquire(
                AudioCaptureOwnership.Owner.STT).orElseThrow();
        assertTrue(ownership.acquire(AudioCaptureOwnership.Owner.RECORDING).isEmpty());
        assertEquals(AudioCaptureOwnership.Owner.STT, ownership.owner());
        assertTrue(ownership.release(stt));
    }

    @Test
    void staleReleaseCannotReleaseNewGeneration() {
        AudioCaptureOwnership ownership = new AudioCaptureOwnership();
        AudioCaptureOwnership.Lease first = ownership.acquire(
                AudioCaptureOwnership.Owner.STT).orElseThrow();
        assertTrue(ownership.release(first));
        AudioCaptureOwnership.Lease second = ownership.acquire(
                AudioCaptureOwnership.Owner.STT).orElseThrow();

        assertNotEquals(first.generation(), second.generation());
        assertFalse(ownership.release(first));
        assertEquals(AudioCaptureOwnership.Owner.STT, ownership.owner());
        assertTrue(ownership.release(second));
    }

    @Test
    void releaseAllInvalidatesEveryOutstandingLease() {
        AudioCaptureOwnership ownership = new AudioCaptureOwnership();
        AudioCaptureOwnership.Lease lease = ownership.acquire(
                AudioCaptureOwnership.Owner.RECORDING).orElseThrow();

        ownership.releaseAll();

        assertEquals(AudioCaptureOwnership.Owner.NONE, ownership.owner());
        assertFalse(ownership.release(lease));
        AudioCaptureOwnership.Lease next = ownership.acquire(
                AudioCaptureOwnership.Owner.STT).orElseThrow();
        assertNotEquals(lease.generation(), next.generation());
    }
}

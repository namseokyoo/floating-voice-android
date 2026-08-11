package com.sidequestlab.floatingvoice;

import org.junit.Test;

import java.io.FileNotFoundException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class SecureSettingsStoreReadPolicyTest {
    @Test
    public void backupOnlyStateStillInvokesAtomicReaderAndReturnsRecoveredBytes() {
        AtomicBoolean opened = new AtomicBoolean(false);

        Optional<byte[]> result = SecureSettingsStore.readOptionalCatalogBlob(() -> {
            opened.set(true);
            return new byte[]{7, 6, 5};
        });

        assertTrue(opened.get());
        assertArrayEquals(new byte[]{7, 6, 5}, result.orElseThrow());
    }

    @Test
    public void actualFileNotFoundIsTheOnlyEmptyState() {
        Optional<byte[]> result = SecureSettingsStore.readOptionalCatalogBlob(() -> {
            throw new FileNotFoundException("no base or backup");
        });

        assertTrue(result.isEmpty());
    }

    @Test
    public void corruptOrUnreadableExistingBlobRequiresRecovery() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> SecureSettingsStore.readOptionalCatalogBlob(() -> {
                    throw new IllegalArgumentException("corrupt encrypted blob");
                }));

        assertTrue(error.getMessage().contains("cannot be decrypted"));
    }

    @Test
    public void wrongTypedLegacyPreferenceIsCorruptInsteadOfCrashingStartup() {
        SecureSettingsStore.LegacyValue value =
                SecureSettingsStore.readRawPreferencePreserving(() -> {
                    throw new ClassCastException("stored preference is not a String");
                });

        assertEquals(SecureSettingsStore.LegacyValueStatus.CORRUPT,
                value.status());
    }
}

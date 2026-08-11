package com.sidequestlab.floatingvoice;

import com.sidequestlab.floatingvoice.core.Destination;
import com.sidequestlab.floatingvoice.core.DestinationCatalog;
import com.sidequestlab.floatingvoice.core.DestinationCatalogCodec;
import com.sidequestlab.floatingvoice.core.DestinationCatalogPersistence;
import com.sidequestlab.floatingvoice.core.LegacyConfigSnapshot;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

public class DestinationStoreTest {
    @Test
    public void legacyTargetChatIsExplicitlyDeprecatedCompatibilityOnly() {
        assertNotNull(TargetChat.class.getAnnotation(Deprecated.class));
    }

    @Test
    public void validRawLegacySnapshotMigratesAfterReadBackThenMarksSchema() {
        List<String> events = new ArrayList<>();
        FakeBlobStore blobs = new FakeBlobStore(events);
        FakeLegacySource legacy = new FakeLegacySource(
                DestinationStore.LegacyInput.available(validSnapshot()), events);
        DestinationStore store = new DestinationStore(
                new DestinationCatalogPersistence(blobs), legacy);

        DestinationCatalogPersistence.LoadResult loaded = store.load();

        assertEquals(DestinationCatalogPersistence.LoadStatus.LOADED, loaded.status());
        DestinationCatalog catalog = loaded.catalog().orElseThrow();
        assertEquals(1, catalog.destinations().size());
        Destination migrated = catalog.destinations().get(0);
        assertEquals("legacy-chat:-10042", migrated.localId());
        assertEquals(-10042L, migrated.chatId());
        assertEquals(0L, migrated.accountUserId());
        assertEquals(0L, migrated.peerUserId());
        assertEquals("family_bot", migrated.configuredUsername());
        assertEquals(Destination.VerificationStatus.NEEDS_REVERIFY,
                migrated.verificationStatus());
        assertEquals(migrated.localId(), catalog.defaultLocalId().orElseThrow());
        assertEquals(List.of("read-blob", "read-legacy", "write-blob",
                "read-blob", "mark-schema", "read-blob"), events);
        assertTrue(legacy.committed);
        assertEquals("/private/pending/voice.ogg",
                legacy.input.snapshot().orElseThrow().pendingRecordingPath());
    }

    @Test
    public void corruptLegacySourceRequiresRecoveryAndPreservesSourceWithoutWriting() {
        List<String> events = new ArrayList<>();
        FakeBlobStore blobs = new FakeBlobStore(events);
        FakeLegacySource legacy = new FakeLegacySource(
                DestinationStore.LegacyInput.corrupt(), events);
        DestinationStore store = new DestinationStore(
                new DestinationCatalogPersistence(blobs), legacy);

        DestinationCatalogPersistence.LoadResult loaded = store.load();

        assertEquals(DestinationCatalogPersistence.LoadStatus.RECOVERY_REQUIRED,
                loaded.status());
        assertTrue(store.migrationFailed());
        assertEquals(0, blobs.writeCount);
        assertFalse(legacy.committed);
        assertEquals(List.of("read-blob", "read-legacy"), events);
    }

    @Test
    public void invalidAccountPartialTargetAndIdentityMismatchNeverCreateCatalog() {
        assertRejected(new LegacyConfigSnapshot(
                "bad", "bad", "bad", "family_bot",
                "-10042", "가족 봇", "family_bot", null));
        assertRejected(new LegacyConfigSnapshot(
                "123456", hash(), "+821055555678", "family_bot",
                "-10042", null, "family_bot", null));
        assertRejected(new LegacyConfigSnapshot(
                "123456", hash(), "+821055555678", "family_bot",
                "-10042", "가족 봇", "other_bot", null));
    }

    @Test
    public void existingCatalogWinsAndMissingMarkerIsRecoveredWithoutLegacyRead() {
        List<String> events = new ArrayList<>();
        FakeBlobStore blobs = new FakeBlobStore(events);
        Destination existing = verified("existing", -200L, 600L);
        blobs.bytes = DestinationCatalogCodec.encode(
                DestinationCatalog.restore(List.of(existing), "existing"));
        FakeLegacySource legacy = new FakeLegacySource(
                DestinationStore.LegacyInput.available(validSnapshot()), events);
        DestinationStore store = new DestinationStore(
                new DestinationCatalogPersistence(blobs), legacy);

        DestinationCatalogPersistence.LoadResult loaded = store.load();

        assertEquals(List.of(existing), loaded.catalog().orElseThrow().destinations());
        assertEquals(0, legacy.readCount);
        assertTrue(legacy.committed);
        assertEquals(0, blobs.writeCount);
        assertEquals(List.of("read-blob", "mark-schema"), events);
    }

    @Test
    public void reloadAfterMigrationDoesNotDuplicateOrReadLegacyAgain() {
        List<String> events = new ArrayList<>();
        FakeBlobStore blobs = new FakeBlobStore(events);
        FakeLegacySource legacy = new FakeLegacySource(
                DestinationStore.LegacyInput.available(validSnapshot()), events);
        new DestinationStore(new DestinationCatalogPersistence(blobs), legacy).load();

        DestinationStore restarted = new DestinationStore(
                new DestinationCatalogPersistence(blobs), legacy);
        DestinationCatalogPersistence.LoadResult loaded = restarted.load();

        assertEquals(1, loaded.catalog().orElseThrow().destinations().size());
        assertEquals(1, legacy.readCount);
        assertEquals(1, blobs.writeCount);
        assertEquals(1, legacy.markerWriteCount);
    }

    @Test
    public void migrationWriteFailureDoesNotCrashOrMarkSchema() {
        List<String> events = new ArrayList<>();
        FakeBlobStore blobs = new FakeBlobStore(events);
        blobs.failWrite = true;
        FakeLegacySource legacy = new FakeLegacySource(
                DestinationStore.LegacyInput.available(validSnapshot()), events);
        DestinationStore store = new DestinationStore(
                new DestinationCatalogPersistence(blobs), legacy);

        DestinationCatalogPersistence.LoadResult loaded = store.load();

        assertEquals(DestinationCatalogPersistence.LoadStatus.EMPTY, loaded.status());
        assertTrue(store.migrationFailed());
        assertFalse(legacy.committed);
        assertNull(blobs.bytes);
        assertEquals(0, legacy.markerWriteCount);
    }

    @Test
    public void markerFailureKeepsVerifiedCatalogAndReportsMigrationFailure() {
        List<String> events = new ArrayList<>();
        FakeBlobStore blobs = new FakeBlobStore(events);
        FakeLegacySource legacy = new FakeLegacySource(
                DestinationStore.LegacyInput.available(validSnapshot()), events);
        legacy.failMarker = true;
        DestinationStore store = new DestinationStore(
                new DestinationCatalogPersistence(blobs), legacy);

        DestinationCatalogPersistence.LoadResult loaded = store.load();

        assertEquals(DestinationCatalogPersistence.LoadStatus.LOADED, loaded.status());
        assertEquals(1, loaded.catalog().orElseThrow().destinations().size());
        assertTrue(store.migrationFailed());
        assertFalse(legacy.committed);
        assertEquals(1, legacy.markerWriteCount);
        assertEquals(1, blobs.writeCount);
    }

    @Test
    public void missingLegacyLeavesExplicitEmptyCatalogWithoutWritingOrMarker() {
        List<String> events = new ArrayList<>();
        FakeBlobStore blobs = new FakeBlobStore(events);
        FakeLegacySource legacy = new FakeLegacySource(
                DestinationStore.LegacyInput.missing(), events);
        DestinationStore store = new DestinationStore(
                new DestinationCatalogPersistence(blobs), legacy);

        DestinationCatalogPersistence.LoadResult loaded = store.load();

        assertEquals(DestinationCatalogPersistence.LoadStatus.EMPTY, loaded.status());
        assertTrue(loaded.catalog().orElseThrow().destinations().isEmpty());
        assertEquals(0, blobs.writeCount);
        assertFalse(legacy.committed);
    }

    @Test
    public void validAccountWithoutLegacyTargetStaysEmptyAndRetryable() {
        List<String> events = new ArrayList<>();
        FakeBlobStore blobs = new FakeBlobStore(events);
        LegacyConfigSnapshot accountOnly = new LegacyConfigSnapshot(
                "123456", hash(), "+821055555678", null,
                null, null, null, "/private/pending/voice.ogg");
        FakeLegacySource legacy = new FakeLegacySource(
                DestinationStore.LegacyInput.available(accountOnly), events);
        DestinationStore store = new DestinationStore(
                new DestinationCatalogPersistence(blobs), legacy);

        DestinationCatalogPersistence.LoadResult loaded = store.load();

        assertEquals(DestinationCatalogPersistence.LoadStatus.EMPTY, loaded.status());
        assertFalse(store.migrationFailed());
        assertEquals(0, blobs.writeCount);
        assertEquals(0, legacy.markerWriteCount);
        assertEquals("/private/pending/voice.ogg",
                legacy.input.snapshot().orElseThrow().pendingRecordingPath());
    }

    private static void assertRejected(LegacyConfigSnapshot snapshot) {
        List<String> events = new ArrayList<>();
        FakeBlobStore blobs = new FakeBlobStore(events);
        FakeLegacySource legacy = new FakeLegacySource(
                DestinationStore.LegacyInput.available(snapshot), events);
        DestinationStore store = new DestinationStore(
                new DestinationCatalogPersistence(blobs), legacy);

        DestinationCatalogPersistence.LoadResult loaded = store.load();

        assertEquals(DestinationCatalogPersistence.LoadStatus.RECOVERY_REQUIRED,
                loaded.status());
        assertTrue(store.migrationFailed());
        assertEquals(0, blobs.writeCount);
        assertFalse(legacy.committed);
    }

    private static LegacyConfigSnapshot validSnapshot() {
        return new LegacyConfigSnapshot(
                "123456", hash(), "+821055555678", "family_bot",
                "-10042", "가족 봇 😄", "Family_Bot",
                "/private/pending/voice.ogg");
    }

    private static String hash() {
        return "0123456789abcdef0123456789abcdef";
    }

    private static Destination verified(String id, long chatId, long peerId) {
        return new Destination(id, 7L, chatId, peerId,
                id + "_configured", id + "_resolved", id, id,
                Destination.VerificationStatus.VERIFIED,
                1L, 1_700_000_000_000L, true);
    }

    private static final class FakeLegacySource
            implements DestinationStore.LegacySnapshotSource {
        private final DestinationStore.LegacyInput input;
        private final List<String> events;
        private int readCount;
        private int markerWriteCount;
        private boolean committed;
        private boolean failMarker;

        private FakeLegacySource(DestinationStore.LegacyInput input,
                                 List<String> events) {
            this.input = input;
            this.events = events;
        }

        @Override public DestinationStore.LegacyInput read() {
            events.add("read-legacy");
            readCount++;
            return input;
        }

        @Override public boolean schemaVersionCommitted() {
            return committed;
        }

        @Override public void markSchemaVersionCommitted() {
            events.add("mark-schema");
            markerWriteCount++;
            if (failMarker) throw new IllegalStateException("simulated marker failure");
            committed = true;
        }
    }

    private static final class FakeBlobStore
            implements DestinationCatalogPersistence.BlobStore {
        private final List<String> events;
        private byte[] bytes;
        private int writeCount;
        private boolean failWrite;

        private FakeBlobStore(List<String> events) {
            this.events = events;
        }

        @Override public Optional<byte[]> read() {
            events.add("read-blob");
            return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
        }

        @Override public void writeAtomically(byte[] next) {
            events.add("write-blob");
            writeCount++;
            if (failWrite) throw new IllegalStateException("simulated write failure");
            bytes = next.clone();
        }
    }
}

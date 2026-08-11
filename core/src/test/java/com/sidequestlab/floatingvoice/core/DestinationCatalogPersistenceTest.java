package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DestinationCatalogPersistenceTest {
    @Test
    void successfulUpdateWritesReadsBackThenPublishes() {
        FakeBlobStore blobs = new FakeBlobStore();
        DestinationCatalogPersistence persistence =
                new DestinationCatalogPersistence(blobs);
        assertEquals(DestinationCatalogPersistence.LoadStatus.EMPTY,
                persistence.load().status());
        blobs.events.clear();

        DestinationCatalog saved = persistence.update(current ->
                current.withDestination(primary()).withDefault("primary", 7L));

        assertEquals(List.of("write", "read"), blobs.events);
        assertEquals(saved.destinations(), persistence.publishedCatalog()
                .orElseThrow().destinations());
        assertEquals("primary", persistence.publishedCatalog()
                .orElseThrow().defaultLocalId().orElseThrow());
        assertEquals(DestinationCatalogCodec.Status.LOADED,
                DestinationCatalogCodec.decode(blobs.bytes).status());
    }

    @Test
    void writeFailureLeavesPreviouslyPublishedCatalogUntouched() {
        FakeBlobStore blobs = new FakeBlobStore();
        blobs.bytes = DestinationCatalogCodec.encode(DestinationCatalog.restore(
                List.of(primary()), "primary"));
        DestinationCatalogPersistence persistence =
                new DestinationCatalogPersistence(blobs);
        assertEquals(DestinationCatalogPersistence.LoadStatus.LOADED,
                persistence.load().status());
        blobs.failWrite = true;

        assertThrows(IllegalStateException.class, () -> persistence.update(current ->
                current.withDestination(secondary())));

        assertEquals(List.of(primary()), persistence.publishedCatalog()
                .orElseThrow().destinations());
        assertEquals(List.of(primary()), DestinationCatalogCodec.decode(blobs.bytes)
                .catalog().orElseThrow().destinations());
    }

    @Test
    void corruptLoadRequiresRecoveryAndDoesNotWriteOrPublishEmptyCatalog() {
        FakeBlobStore blobs = new FakeBlobStore();
        blobs.bytes = new byte[]{1, 2, 3, 4, 5};
        byte[] original = blobs.bytes.clone();
        DestinationCatalogPersistence persistence =
                new DestinationCatalogPersistence(blobs);

        DestinationCatalogPersistence.LoadResult loaded = persistence.load();

        assertEquals(DestinationCatalogPersistence.LoadStatus.RECOVERY_REQUIRED,
                loaded.status());
        assertTrue(loaded.catalog().isEmpty());
        assertArrayEquals(original, loaded.recoveryBytes());
        assertTrue(persistence.publishedCatalog().isEmpty());
        assertEquals(0, blobs.writeCount);
        assertThrows(IllegalStateException.class, () ->
                persistence.update(current -> current.withDestination(primary())));
        assertArrayEquals(original, blobs.bytes);
    }

    @Test
    void unsupportedVersionAlsoRequiresRecoveryAndRetainsBytes() {
        FakeBlobStore blobs = new FakeBlobStore();
        blobs.bytes = DestinationCatalogCodec.encode(new DestinationCatalog(List.of(primary())));
        blobs.bytes[7] = 99;
        byte[] original = blobs.bytes.clone();
        DestinationCatalogPersistence persistence =
                new DestinationCatalogPersistence(blobs);

        DestinationCatalogPersistence.LoadResult loaded = persistence.load();

        assertEquals(DestinationCatalogPersistence.LoadStatus.RECOVERY_REQUIRED,
                loaded.status());
        assertArrayEquals(original, loaded.recoveryBytes());
        assertEquals(0, blobs.writeCount);
    }

    @Test
    void emptyStorePublishesExplicitEmptyCatalogWithoutWriting() {
        FakeBlobStore blobs = new FakeBlobStore();
        DestinationCatalogPersistence persistence =
                new DestinationCatalogPersistence(blobs);

        DestinationCatalogPersistence.LoadResult loaded = persistence.load();

        assertEquals(DestinationCatalogPersistence.LoadStatus.EMPTY, loaded.status());
        assertTrue(loaded.catalog().orElseThrow().destinations().isEmpty());
        assertTrue(persistence.publishedCatalog().orElseThrow().destinations().isEmpty());
        assertEquals(0, blobs.writeCount);
    }

    @Test
    void invalidReadBackIsNeverPublished() {
        FakeBlobStore blobs = new FakeBlobStore();
        DestinationCatalogPersistence persistence =
                new DestinationCatalogPersistence(blobs);
        persistence.load();
        blobs.corruptAfterWrite = true;

        assertThrows(IllegalStateException.class, () -> persistence.update(current ->
                current.withDestination(primary())));

        assertTrue(persistence.publishedCatalog().orElseThrow().destinations().isEmpty());
        assertThrows(IllegalStateException.class, () -> persistence.update(current ->
                current.withDestination(primary())));
    }

    @Test
    void invalidReadBackLatchesRecoveryButRetainsLastVerifiedPublication() {
        FakeBlobStore blobs = new FakeBlobStore();
        blobs.bytes = DestinationCatalogCodec.encode(DestinationCatalog.restore(
                List.of(primary()), "primary"));
        DestinationCatalogPersistence persistence =
                new DestinationCatalogPersistence(blobs);
        persistence.load();
        blobs.corruptAfterWrite = true;

        assertThrows(IllegalStateException.class, () -> persistence.update(current ->
                current.withDestination(secondary())));

        assertEquals(List.of(primary()), persistence.publishedCatalog()
                .orElseThrow().destinations());
        assertThrows(IllegalStateException.class, () -> persistence.update(current ->
                current.withDestination(secondary())));
    }

    @Test
    void explicitRecoveryNeverPublishesAnEmptyReplacement() {
        DestinationCatalogPersistence persistence =
                new DestinationCatalogPersistence(new FakeBlobStore());

        DestinationCatalogPersistence.LoadResult recovery =
                persistence.requireRecovery(new byte[]{9, 8, 7});

        assertEquals(DestinationCatalogPersistence.LoadStatus.RECOVERY_REQUIRED,
                recovery.status());
        assertArrayEquals(new byte[]{9, 8, 7}, recovery.recoveryBytes());
        assertTrue(persistence.publishedCatalog().isEmpty());
    }

    private static Destination primary() {
        return destination("primary", 100L, 500L);
    }

    private static Destination secondary() {
        return destination("secondary", 200L, 600L);
    }

    private static Destination destination(String id, long chatId, long peerId) {
        return new Destination(id, 7L, chatId, peerId,
                id + "_configured", id + "_resolved", id, id,
                Destination.VerificationStatus.VERIFIED,
                1L, 1_700_000_000_000L, true);
    }

    private static final class FakeBlobStore
            implements DestinationCatalogPersistence.BlobStore {
        private byte[] bytes;
        private boolean failWrite;
        private boolean corruptAfterWrite;
        private int writeCount;
        private final List<String> events = new ArrayList<>();

        @Override public Optional<byte[]> read() {
            events.add("read");
            return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
        }

        @Override public void writeAtomically(byte[] next) {
            events.add("write");
            writeCount++;
            if (failWrite) throw new IllegalStateException("simulated write failure");
            bytes = next.clone();
            if (corruptAfterWrite) bytes = Arrays.copyOf(bytes, bytes.length - 1);
        }
    }
}

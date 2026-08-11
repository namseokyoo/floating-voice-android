package com.sidequestlab.floatingvoice.core;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Coordinates decode, atomic blob persistence, read-back validation, and in-memory publish. */
public final class DestinationCatalogPersistence {
    public interface BlobStore {
        Optional<byte[]> read();
        void writeAtomically(byte[] next);
    }

    public enum LoadStatus {
        EMPTY,
        LOADED,
        RECOVERY_REQUIRED
    }

    public static final class LoadResult {
        private final LoadStatus status;
        private final DestinationCatalog catalog;
        private final byte[] recoveryBytes;

        private LoadResult(LoadStatus status, DestinationCatalog catalog,
                           byte[] recoveryBytes) {
            this.status = Objects.requireNonNull(status);
            this.catalog = catalog;
            this.recoveryBytes = recoveryBytes == null ? new byte[0] : recoveryBytes.clone();
        }

        public LoadStatus status() { return status; }
        public Optional<DestinationCatalog> catalog() { return Optional.ofNullable(catalog); }
        public byte[] recoveryBytes() { return recoveryBytes.clone(); }
    }

    private final BlobStore blobStore;
    private DestinationCatalog published;
    private boolean loaded;
    private boolean recoveryRequired;

    public DestinationCatalogPersistence(BlobStore blobStore) {
        this.blobStore = Objects.requireNonNull(blobStore);
    }

    public synchronized LoadResult load() {
        Optional<byte[]> source;
        try {
            source = blobStore.read();
        } catch (RuntimeException e) {
            loaded = true;
            recoveryRequired = true;
            published = null;
            return new LoadResult(LoadStatus.RECOVERY_REQUIRED, null, new byte[0]);
        }
        loaded = true;
        if (source.isEmpty()) {
            recoveryRequired = false;
            published = DestinationCatalog.empty();
            return new LoadResult(LoadStatus.EMPTY, published, null);
        }
        DestinationCatalogCodec.DecodeResult decoded =
                DestinationCatalogCodec.decode(source.get());
        if (decoded.status() != DestinationCatalogCodec.Status.LOADED) {
            recoveryRequired = true;
            published = null;
            return new LoadResult(LoadStatus.RECOVERY_REQUIRED, null,
                    decoded.sourceBytes());
        }
        recoveryRequired = false;
        published = decoded.catalog().orElseThrow();
        return new LoadResult(LoadStatus.LOADED, published, null);
    }

    public synchronized DestinationCatalog update(
            UnaryOperator<DestinationCatalog> mutation) {
        Objects.requireNonNull(mutation, "mutation");
        if (!loaded) throw new IllegalStateException("catalog must be loaded before update");
        if (recoveryRequired || published == null) {
            throw new IllegalStateException("catalog recovery is required before update");
        }
        DestinationCatalog candidate = Objects.requireNonNull(
                mutation.apply(published), "mutation result");
        byte[] encoded = DestinationCatalogCodec.encode(candidate);
        blobStore.writeAtomically(encoded);

        try {
            byte[] persisted = blobStore.read().orElseThrow(() ->
                    new IllegalStateException("catalog disappeared after write"));
            if (!Arrays.equals(encoded, persisted)) {
                throw new IllegalStateException("catalog read-back bytes mismatch");
            }
            DestinationCatalogCodec.DecodeResult decoded =
                    DestinationCatalogCodec.decode(persisted);
            if (decoded.status() != DestinationCatalogCodec.Status.LOADED) {
                throw new IllegalStateException("catalog read-back is invalid");
            }
            DestinationCatalog verified = decoded.catalog().orElseThrow();
            published = verified;
            return verified;
        } catch (RuntimeException error) {
            recoveryRequired = true;
            throw error;
        }
    }

    public synchronized LoadResult requireRecovery(byte[] recoveryBytes) {
        loaded = true;
        recoveryRequired = true;
        published = null;
        return new LoadResult(LoadStatus.RECOVERY_REQUIRED, null, recoveryBytes);
    }

    public synchronized Optional<DestinationCatalog> publishedCatalog() {
        return Optional.ofNullable(published);
    }
}

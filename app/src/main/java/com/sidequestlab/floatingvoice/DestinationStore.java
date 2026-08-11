package com.sidequestlab.floatingvoice;

import com.sidequestlab.floatingvoice.core.Destination;
import com.sidequestlab.floatingvoice.core.DestinationCatalog;
import com.sidequestlab.floatingvoice.core.DestinationCatalogPersistence;
import com.sidequestlab.floatingvoice.core.DestinationMigration;
import com.sidequestlab.floatingvoice.core.LegacyConfigSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Owns the encrypted destination catalog and additive V7-01 legacy migration. */
public final class DestinationStore {
    interface LegacySnapshotSource {
        LegacyInput read();
        boolean schemaVersionCommitted();
        void markSchemaVersionCommitted();
    }

    static final class LegacyInput {
        enum Status { MISSING, AVAILABLE, CORRUPT }

        private final Status status;
        private final LegacyConfigSnapshot snapshot;

        private LegacyInput(Status status, LegacyConfigSnapshot snapshot) {
            this.status = Objects.requireNonNull(status);
            this.snapshot = snapshot;
        }

        static LegacyInput missing() { return new LegacyInput(Status.MISSING, null); }
        static LegacyInput corrupt() { return new LegacyInput(Status.CORRUPT, null); }
        static LegacyInput available(LegacyConfigSnapshot snapshot) {
            return new LegacyInput(Status.AVAILABLE, Objects.requireNonNull(snapshot));
        }

        Status status() { return status; }
        Optional<LegacyConfigSnapshot> snapshot() { return Optional.ofNullable(snapshot); }
    }

    private final DestinationCatalogPersistence persistence;
    private final LegacySnapshotSource legacySource;
    private boolean migrationFailed;

    public DestinationStore(SecureSettingsStore settings) {
        this(new DestinationCatalogPersistence(settings.destinationCatalogBlobStore()),
                settings.destinationLegacySnapshotSource());
    }

    DestinationStore(DestinationCatalogPersistence persistence,
                     LegacySnapshotSource legacySource) {
        this.persistence = Objects.requireNonNull(persistence);
        this.legacySource = Objects.requireNonNull(legacySource);
    }

    public synchronized DestinationCatalogPersistence.LoadResult load() {
        migrationFailed = false;
        DestinationCatalogPersistence.LoadResult loaded = persistence.load();
        if (loaded.status() == DestinationCatalogPersistence.LoadStatus.RECOVERY_REQUIRED) {
            return loaded;
        }
        if (loaded.status() == DestinationCatalogPersistence.LoadStatus.LOADED) {
            ensureSchemaMarker();
            return loaded;
        }

        LegacyInput legacy = legacySource.read();
        if (legacy.status() == LegacyInput.Status.MISSING) return loaded;
        if (legacy.status() == LegacyInput.Status.CORRUPT) {
            migrationFailed = true;
            return persistence.requireRecovery(new byte[0]);
        }

        DestinationMigration.Result plan = DestinationMigration.migrate(
                legacy.snapshot().orElseThrow(), DestinationMigration.State.empty());
        if (!plan.isMigratable() || !plan.errors().isEmpty()) {
            migrationFailed = true;
            return persistence.requireRecovery(new byte[0]);
        }
        if (plan.state().candidates().isEmpty()) {
            return loaded;
        }
        if (plan.state().candidates().size() != 1) {
            migrationFailed = true;
            return persistence.requireRecovery(new byte[0]);
        }

        Destination candidate = fromLegacy(plan.state().candidates().get(0));
        try {
            persistence.update(ignored -> DestinationCatalog.restore(
                    List.of(candidate), candidate.localId()));
            legacySource.markSchemaVersionCommitted();
            return persistence.load();
        } catch (RuntimeException error) {
            migrationFailed = true;
            return persistence.load();
        }
    }

    public synchronized DestinationCatalog update(
            UnaryOperator<DestinationCatalog> mutation) {
        DestinationCatalog updated = persistence.update(mutation);
        ensureSchemaMarker();
        return updated;
    }

    public synchronized Optional<DestinationCatalog> publishedCatalog() {
        return persistence.publishedCatalog();
    }

    public synchronized boolean migrationFailed() {
        return migrationFailed;
    }

    private void ensureSchemaMarker() {
        try {
            if (!legacySource.schemaVersionCommitted()) {
                legacySource.markSchemaVersionCommitted();
            }
        } catch (RuntimeException error) {
            migrationFailed = true;
        }
    }

    private static Destination fromLegacy(
            DestinationMigration.LegacyDestinationCandidate candidate) {
        return new Destination(
                candidate.migrationKey(),
                0L,
                candidate.chatId(),
                0L,
                candidate.username(),
                candidate.username(),
                candidate.title(),
                candidate.title(),
                Destination.VerificationStatus.NEEDS_REVERIFY,
                0L,
                0L,
                true);
    }
}

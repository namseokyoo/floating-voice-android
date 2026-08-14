package com.sidequestlab.floatingvoice.core;

import java.util.Objects;
import java.util.Optional;

/** Owns one capture's output choice and immutable freeze boundary. */
public final class OutputRouteStateMachine {
    public enum Phase { SELECTING, FROZEN, COMPLETED }

    private final OutputRoute.ContentKind contentKind;
    private final long authenticatedAccountUserId;
    private DestinationCatalog catalog;
    private OutputRoute selectedRoute;
    private Destination selectedDestination;
    private Phase phase = Phase.SELECTING;
    private long outputAttemptId;
    private OutputSnapshot frozenSnapshot;

    public OutputRouteStateMachine(OutputRoute.ContentKind contentKind,
                                   long authenticatedAccountUserId,
                                   DestinationCatalog catalog,
                                   String defaultLocalId) {
        this.contentKind = Objects.requireNonNull(contentKind, "contentKind");
        if (authenticatedAccountUserId < 0L) {
            throw new IllegalArgumentException("authenticatedAccountUserId must not be negative");
        }
        this.authenticatedAccountUserId = authenticatedAccountUserId;
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.selectedRoute = contentKind == OutputRoute.ContentKind.TEXT
                ? OutputRoute.TELEGRAM_TEXT : OutputRoute.TELEGRAM_VOICE;
        this.selectedDestination = defaultLocalId == null || authenticatedAccountUserId == 0L
                ? null
                : catalog.selectable(defaultLocalId, authenticatedAccountUserId).orElse(null);
    }

    public synchronized Phase phase() { return phase; }
    public synchronized OutputRoute selectedRoute() { return selectedRoute; }
    public synchronized Optional<Destination> selectedDestination() {
        return Optional.ofNullable(selectedDestination);
    }

    public synchronized boolean selectRoute(OutputRoute route) {
        if (phase != Phase.SELECTING || route == null
                || route.contentKind() != contentKind) {
            return false;
        }
        selectedRoute = route;
        if (route.requiresTelegramDestination()) {
            selectedDestination = catalog.defaultLocalId()
                    .flatMap(localId -> catalog.selectable(
                            localId, authenticatedAccountUserId))
                    .orElse(null);
        } else {
            selectedDestination = null;
        }
        return true;
    }

    public synchronized boolean selectTelegramDestination(String localId) {
        if (phase != Phase.SELECTING || !selectedRoute.requiresTelegramDestination()
                || localId == null) {
            return false;
        }
        Destination selected = catalog.selectable(
                localId, authenticatedAccountUserId).orElse(null);
        if (selected == null) return false;
        selectedDestination = selected;
        return true;
    }

    public synchronized void replaceCatalog(DestinationCatalog replacement) {
        catalog = Objects.requireNonNull(replacement, "replacement");
    }

    public synchronized Optional<OutputSnapshot> freeze(String payload) {
        if (phase != Phase.SELECTING || payload == null || payload.isBlank()
                || selectedRoute.contentKind() != contentKind) {
            return Optional.empty();
        }
        DispatchTargetSnapshot telegramTarget = null;
        if (selectedRoute.requiresTelegramDestination()) {
            if (selectedDestination == null) return Optional.empty();
            Destination latest = catalog.selectable(
                    selectedDestination.localId(), authenticatedAccountUserId).orElse(null);
            if (latest == null || !sameVerifiedIdentity(selectedDestination, latest)) {
                return Optional.empty();
            }
            telegramTarget = DispatchTargetSnapshot.from(latest, outputAttemptId + 1L);
        }
        outputAttemptId++;
        OutputSnapshot frozen = new OutputSnapshot(
                outputAttemptId, selectedRoute, payload, telegramTarget);
        frozenSnapshot = frozen;
        phase = Phase.FROZEN;
        return Optional.of(frozen);
    }

    public synchronized Optional<OutputSnapshot> snapshotForRetry(long expectedOutputAttemptId) {
        if (phase != Phase.FROZEN || frozenSnapshot == null
                || frozenSnapshot.outputAttemptId() != expectedOutputAttemptId) {
            return Optional.empty();
        }
        return Optional.of(frozenSnapshot);
    }

    public synchronized boolean complete(long expectedOutputAttemptId) {
        if (phase != Phase.FROZEN || frozenSnapshot == null
                || frozenSnapshot.outputAttemptId() != expectedOutputAttemptId) {
            return false;
        }
        frozenSnapshot = null;
        selectedDestination = null;
        phase = Phase.COMPLETED;
        return true;
    }

    private static boolean sameVerifiedIdentity(Destination captured, Destination latest) {
        return captured.accountUserId() == latest.accountUserId()
                && captured.chatId() == latest.chatId()
                && captured.peerUserId() == latest.peerUserId()
                && captured.verificationRevision() == latest.verificationRevision();
    }
}

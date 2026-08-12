package com.sidequestlab.floatingvoice.core;

import java.util.Objects;
import java.util.Optional;

/** Owns only route lifetime and freezing; delivery truth lives in the snapshot. */
public final class RouteStateMachine {
    public enum Phase {
        IDLE,
        RECORDING,
        FREEZING,
        FROZEN
    }

    private final long authenticatedAccountUserId;
    private DestinationCatalog catalog;
    private String nextOneLocalId;
    private Destination currentDestination;
    private Phase phase = Phase.IDLE;
    private long routeAttemptId;

    public RouteStateMachine(long authenticatedAccountUserId,
                             DestinationCatalog catalog,
                             String defaultLocalId) {
        if (authenticatedAccountUserId <= 0) {
            throw new IllegalArgumentException("authenticatedAccountUserId must be positive");
        }
        this.authenticatedAccountUserId = authenticatedAccountUserId;
        DestinationCatalog supplied = Objects.requireNonNull(catalog, "catalog");
        this.catalog = defaultLocalId == null
                ? new DestinationCatalog(supplied.destinations())
                : supplied.withDefault(defaultLocalId, authenticatedAccountUserId);
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized Optional<String> defaultLocalId() {
        return catalog.defaultLocalId();
    }

    public synchronized Optional<String> nextOneLocalId() {
        return Optional.ofNullable(nextOneLocalId);
    }

    public synchronized Optional<Destination> currentDestination() {
        return Optional.ofNullable(currentDestination);
    }

    public synchronized long routeAttemptId() {
        return routeAttemptId;
    }

    /** Prevents a route session from surviving an authenticated-account change. */
    public synchronized boolean matchesAuthenticatedAccount(long accountUserId) {
        return accountUserId > 0 && authenticatedAccountUserId == accountUserId;
    }

    public synchronized void replaceCatalog(DestinationCatalog replacement) {
        Objects.requireNonNull(replacement, "replacement");
        for (Destination existing : catalog.destinations()) {
            replacement.find(existing.localId()).ifPresent(candidate -> {
                if (!compatibleCanonicalIdentity(existing, candidate)) {
                    throw new IllegalArgumentException(
                            "localId cannot be rebound: " + existing.localId());
                }
            });
        }
        catalog = replacement;
    }

    public synchronized boolean select(DestinationScope scope, String localId) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(localId, "localId");
        Optional<Destination> selectable =
                catalog.selectable(localId, authenticatedAccountUserId);
        if (selectable.isEmpty()) return false;

        return switch (scope) {
            case DEFAULT -> {
                catalog = catalog.withDefault(localId, authenticatedAccountUserId);
                yield true;
            }
            case NEXT_ONE -> {
                if (phase != Phase.IDLE) yield false;
                nextOneLocalId = localId;
                yield true;
            }
            case CURRENT_RECORDING -> {
                if (phase != Phase.RECORDING) yield false;
                currentDestination = selectable.get();
                yield true;
            }
        };
    }

    public synchronized Optional<Destination> startRecording() {
        if (phase != Phase.IDLE) return Optional.empty();
        String requestedLocalId;
        if (nextOneLocalId != null) {
            requestedLocalId = nextOneLocalId;
        } else {
            requestedLocalId = catalog.defaultLocalId().orElse(null);
        }
        if (requestedLocalId == null) return Optional.empty();
        Optional<Destination> selected =
                catalog.selectable(requestedLocalId, authenticatedAccountUserId);
        if (selected.isEmpty()) {
            currentDestination = null;
            return Optional.empty();
        }
        if (nextOneLocalId != null) nextOneLocalId = null;
        routeAttemptId++;
        currentDestination = selected.get();
        phase = Phase.RECORDING;
        return Optional.of(currentDestination);
    }

    public synchronized boolean cancelRecording() {
        if (phase != Phase.RECORDING) return false;
        currentDestination = null;
        phase = Phase.IDLE;
        return true;
    }

    public synchronized boolean beginFreezing() {
        if (phase != Phase.RECORDING || currentDestination == null) return false;
        phase = Phase.FREEZING;
        return true;
    }

    public synchronized Optional<DispatchTargetSnapshot> freeze() {
        if (phase != Phase.FREEZING || currentDestination == null) {
            return Optional.empty();
        }
        Optional<Destination> latest = catalog.selectable(
                currentDestination.localId(), authenticatedAccountUserId);
        if (latest.isEmpty() || !sameVerifiedIdentity(currentDestination, latest.get())) {
            return Optional.empty();
        }
        DispatchTargetSnapshot snapshot =
                DispatchTargetSnapshot.from(currentDestination, routeAttemptId);
        phase = Phase.FROZEN;
        return Optional.of(snapshot);
    }

    public synchronized boolean completeDispatch(long expectedRouteAttemptId) {
        if (phase != Phase.FROZEN || expectedRouteAttemptId != routeAttemptId) return false;
        currentDestination = null;
        phase = Phase.IDLE;
        return true;
    }

    public synchronized boolean abortFreezing(long expectedRouteAttemptId) {
        if (phase != Phase.FREEZING || expectedRouteAttemptId != routeAttemptId) return false;
        currentDestination = null;
        phase = Phase.IDLE;
        return true;
    }

    private static boolean sameVerifiedIdentity(Destination captured, Destination latest) {
        return sameCanonicalIdentity(captured, latest)
                && captured.verificationRevision() == latest.verificationRevision();
    }

    private static boolean sameCanonicalIdentity(Destination first, Destination second) {
        return first.accountUserId() == second.accountUserId()
                && first.chatId() == second.chatId()
                && first.peerUserId() == second.peerUserId();
    }

    private static boolean compatibleCanonicalIdentity(Destination existing,
                                                       Destination replacement) {
        return (existing.accountUserId() == 0
                || existing.accountUserId() == replacement.accountUserId())
                && (existing.chatId() == 0 || existing.chatId() == replacement.chatId())
                && (existing.peerUserId() == 0
                || existing.peerUserId() == replacement.peerUserId());
    }
}

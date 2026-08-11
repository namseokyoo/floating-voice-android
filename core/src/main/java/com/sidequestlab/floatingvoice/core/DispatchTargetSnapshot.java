package com.sidequestlab.floatingvoice.core;

import java.util.Objects;

/** Immutable scalar delivery truth captured only by RouteStateMachine. */
public final class DispatchTargetSnapshot {
    private final long routeAttemptId;
    private final String localId;
    private final long accountUserId;
    private final long chatId;
    private final long peerUserId;
    private final String configuredUsername;
    private final String resolvedUsername;
    private final String resolvedTitle;
    private final String userAlias;
    private final long verificationRevision;
    private final long verifiedAtEpochMillis;

    private DispatchTargetSnapshot(long routeAttemptId, Destination destination) {
        if (routeAttemptId <= 0) {
            throw new IllegalArgumentException("routeAttemptId must be positive");
        }
        this.routeAttemptId = routeAttemptId;
        this.localId = Objects.requireNonNull(destination.localId());
        this.accountUserId = destination.accountUserId();
        this.chatId = destination.chatId();
        this.peerUserId = destination.peerUserId();
        this.configuredUsername = destination.configuredUsername();
        this.resolvedUsername = destination.resolvedUsername();
        this.resolvedTitle = destination.resolvedTitle();
        this.userAlias = destination.userAlias();
        this.verificationRevision = destination.verificationRevision();
        this.verifiedAtEpochMillis = destination.verifiedAtEpochMillis();
    }

    static DispatchTargetSnapshot from(Destination destination, long routeAttemptId) {
        Objects.requireNonNull(destination, "destination");
        if (!destination.enabled()
                || destination.verificationStatus()
                != Destination.VerificationStatus.VERIFIED) {
            throw new IllegalArgumentException("destination is not dispatchable");
        }
        return new DispatchTargetSnapshot(routeAttemptId, destination);
    }

    public long routeAttemptId() { return routeAttemptId; }
    public String localId() { return localId; }
    public long accountUserId() { return accountUserId; }
    public long chatId() { return chatId; }
    public long peerUserId() { return peerUserId; }
    public String configuredUsername() { return configuredUsername; }
    public String resolvedUsername() { return resolvedUsername; }
    public String resolvedTitle() { return resolvedTitle; }
    public String userAlias() { return userAlias; }
    public long verificationRevision() { return verificationRevision; }
    public long verifiedAtEpochMillis() { return verifiedAtEpochMillis; }
}

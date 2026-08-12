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

    /** Restores a previously persisted immutable dispatch truth. */
    public static DispatchTargetSnapshot restore(
            long routeAttemptId, String localId, long accountUserId, long chatId,
            long peerUserId, String configuredUsername, String resolvedUsername,
            String resolvedTitle, String userAlias, long verificationRevision,
            long verifiedAtEpochMillis) {
        Destination destination = new Destination(
                localId, accountUserId, chatId, peerUserId,
                configuredUsername, resolvedUsername, resolvedTitle, userAlias,
                Destination.VerificationStatus.VERIFIED,
                verificationRevision, verifiedAtEpochMillis, true);
        return new DispatchTargetSnapshot(routeAttemptId, destination);
    }

    /**
     * Builds the conservative truth for the legacy single-target send path that predates the
     * verified destination catalog. Identity stays incomplete on purpose.
     */
    public static DispatchTargetSnapshot legacy(
            long routeAttemptId, String username, long accountUserId, long chatId, String title) {
        Destination destination = new Destination(
                "legacy-single", accountUserId, chatId, 0L,
                username, username, title, title,
                Destination.VerificationStatus.NEEDS_REVERIFY, 0L, 0L, true);
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

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DispatchTargetSnapshot value)) return false;
        return routeAttemptId == value.routeAttemptId
                && accountUserId == value.accountUserId
                && chatId == value.chatId
                && peerUserId == value.peerUserId
                && verificationRevision == value.verificationRevision
                && verifiedAtEpochMillis == value.verifiedAtEpochMillis
                && localId.equals(value.localId)
                && configuredUsername.equals(value.configuredUsername)
                && resolvedUsername.equals(value.resolvedUsername)
                && resolvedTitle.equals(value.resolvedTitle)
                && userAlias.equals(value.userAlias);
    }

    @Override public int hashCode() {
        return Objects.hash(routeAttemptId, localId, accountUserId, chatId, peerUserId,
                configuredUsername, resolvedUsername, resolvedTitle, userAlias,
                verificationRevision, verifiedAtEpochMillis);
    }

    @Override public String toString() {
        return "DispatchTargetSnapshot{" + localId + ", chatId=" + chatId
                + ", accountUserId=" + accountUserId + ", revision="
                + verificationRevision + '}';
    }
}

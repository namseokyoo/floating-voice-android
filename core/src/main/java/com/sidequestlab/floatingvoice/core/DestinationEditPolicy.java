package com.sidequestlab.floatingvoice.core;

import java.util.Objects;

/** Pure destination-management edits that never rebind canonical identity. */
public final class DestinationEditPolicy {

    private DestinationEditPolicy() { }

    public static Destination withAlias(Destination destination, String userAlias) {
        Objects.requireNonNull(destination, "destination");
        if (userAlias == null || userAlias.isBlank()) {
            throw new IllegalArgumentException("alias must not be blank");
        }
        return new Destination(
                destination.localId(), destination.accountUserId(), destination.chatId(),
                destination.peerUserId(), destination.configuredUsername(),
                destination.resolvedUsername(), destination.resolvedTitle(),
                userAlias.trim(), destination.verificationStatus(),
                destination.verificationRevision(), destination.verifiedAtEpochMillis(),
                destination.enabled());
    }

    public static Destination disable(Destination destination, long updatedAtEpochMillis) {
        Objects.requireNonNull(destination, "destination");
        if (!destination.enabled()
                || destination.verificationStatus() != Destination.VerificationStatus.VERIFIED) {
            throw new IllegalArgumentException("only a verified destination can be disabled");
        }
        return new Destination(
                destination.localId(), destination.accountUserId(), destination.chatId(),
                destination.peerUserId(), destination.configuredUsername(),
                destination.resolvedUsername(), destination.resolvedTitle(),
                destination.userAlias(), Destination.VerificationStatus.DISABLED,
                destination.verificationRevision() + 1, updatedAtEpochMillis, false);
    }

    public static Destination enable(Destination destination, long updatedAtEpochMillis) {
        Objects.requireNonNull(destination, "destination");
        if (destination.verificationStatus() != Destination.VerificationStatus.DISABLED) {
            throw new IllegalArgumentException("only a DISABLED destination can be enabled");
        }
        Destination restored = new Destination(
                destination.localId(), destination.accountUserId(), destination.chatId(),
                destination.peerUserId(), destination.configuredUsername(),
                destination.resolvedUsername(), destination.resolvedTitle(),
                destination.userAlias(), Destination.VerificationStatus.VERIFIED,
                destination.verificationRevision() + 1, updatedAtEpochMillis, true);
        if (!restored.selectableBy(destination.accountUserId())) {
            throw new IllegalArgumentException("disabled identity is incomplete; re-verify instead");
        }
        return restored;
    }

    /** Refuses a delayed re-verification commit if the catalog row changed during preview. */
    public static void requirePreviewCurrent(Destination previewed, Destination current) {
        Objects.requireNonNull(previewed, "previewed");
        if (current == null
                || !previewed.localId().equals(current.localId())
                || previewed.accountUserId() != current.accountUserId()
                || previewed.chatId() != current.chatId()
                || previewed.peerUserId() != current.peerUserId()
                || !previewed.configuredUsername().equals(current.configuredUsername())
                || !previewed.resolvedUsername().equals(current.resolvedUsername())
                || !previewed.resolvedTitle().equals(current.resolvedTitle())
                || !previewed.userAlias().equals(current.userAlias())
                || previewed.verificationStatus() != current.verificationStatus()
                || previewed.verificationRevision() != current.verificationRevision()
                || previewed.verifiedAtEpochMillis() != current.verifiedAtEpochMillis()
                || previewed.enabled() != current.enabled()) {
            throw new IllegalStateException("destination changed while verification preview was open");
        }
    }
}

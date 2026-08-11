package com.sidequestlab.floatingvoice.core;

import java.util.Objects;

/** Immutable verified routing identity; UI labels never define delivery identity. */
public record Destination(
        String localId,
        long accountUserId,
        long chatId,
        long peerUserId,
        String configuredUsername,
        String resolvedUsername,
        String resolvedTitle,
        String userAlias,
        VerificationStatus verificationStatus,
        long verificationRevision,
        long verifiedAtEpochMillis,
        boolean enabled) {

    public enum VerificationStatus {
        NEEDS_REVERIFY,
        VERIFYING,
        VERIFIED,
        INVALID,
        DISABLED
    }

    public Destination {
        localId = requireText(localId, "localId");
        if (accountUserId < 0) throw new IllegalArgumentException("accountUserId must not be negative");
        if (peerUserId < 0) throw new IllegalArgumentException("peerUserId must not be negative");
        configuredUsername = Objects.requireNonNull(configuredUsername, "configuredUsername");
        resolvedUsername = Objects.requireNonNull(resolvedUsername, "resolvedUsername");
        resolvedTitle = Objects.requireNonNull(resolvedTitle, "resolvedTitle");
        userAlias = Objects.requireNonNull(userAlias, "userAlias");
        verificationStatus = Objects.requireNonNull(verificationStatus, "verificationStatus");
        if (verificationRevision < 0) {
            throw new IllegalArgumentException("verificationRevision must not be negative");
        }
        if (verifiedAtEpochMillis < 0) {
            throw new IllegalArgumentException("verifiedAtEpochMillis must not be negative");
        }
        if ((verificationStatus == VerificationStatus.DISABLED) == enabled) {
            throw new IllegalArgumentException("DISABLED status must match enabled=false");
        }
        if (verificationStatus == VerificationStatus.VERIFIED
                && (accountUserId == 0 || chatId == 0 || peerUserId == 0
                || verificationRevision == 0 || verifiedAtEpochMillis == 0)) {
            throw new IllegalArgumentException("VERIFIED destination requires complete identity");
        }
    }

    public boolean selectableBy(long authenticatedAccountUserId) {
        return enabled
                && verificationStatus == VerificationStatus.VERIFIED
                && accountUserId != 0 && chatId != 0 && peerUserId != 0
                && accountUserId == authenticatedAccountUserId;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

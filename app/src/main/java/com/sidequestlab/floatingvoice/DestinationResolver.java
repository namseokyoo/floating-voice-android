package com.sidequestlab.floatingvoice;

import com.sidequestlab.floatingvoice.core.Destination;
import com.sidequestlab.floatingvoice.core.DestinationCatalog;
import com.sidequestlab.floatingvoice.core.UsernameNormalizer;

import java.util.Objects;

/** Pure request/identity decision module for asynchronous private-bot resolution. */
public final class DestinationResolver {
    public enum Mode { ADD, REVERIFY }

    public enum Rejection {
        INVALID_USERNAME,
        ACCOUNT_REQUIRED,
        STALE_REQUEST,
        SEARCH_FAILED,
        USER_LOOKUP_FAILED,
        UNEXPECTED_RESPONSE,
        ACCOUNT_MISMATCH,
        NOT_PRIVATE_CHAT,
        NOT_BOT,
        INCOMPLETE_IDENTITY,
        MISSING_CANONICAL_USERNAME,
        DESTINATION_MISSING,
        DUPLICATE_DESTINATION,
        IDENTITY_CHANGED
    }

    public record Request(
            long token,
            Mode mode,
            long accountUserId,
            String localId,
            String configuredUsername,
            String userAlias) {}

    public record StartResult(Request request, Rejection rejection) {
        public boolean started() { return request != null; }
    }

    public record ResolvedPeer(
            boolean privateChat,
            boolean bot,
            long chatId,
            long peerUserId,
            String primaryUsername,
            String title) {}

    public record Resolution(Destination destination, Rejection rejection) {
        public boolean accepted() { return destination != null; }
    }

    private long nextToken;
    private long activeToken;

    public synchronized StartResult startAdd(
            long accountUserId, String rawUsername, String userAlias) {
        activeToken = 0L;
        if (accountUserId <= 0) return new StartResult(null, Rejection.ACCOUNT_REQUIRED);
        String username = UsernameNormalizer.normalize(rawUsername);
        if (!UsernameNormalizer.isValid(username)) {
            return new StartResult(null, Rejection.INVALID_USERNAME);
        }
        Request request = new Request(++nextToken, Mode.ADD, accountUserId,
                null, username, normalizeAlias(userAlias));
        activeToken = request.token();
        return new StartResult(request, null);
    }

    public synchronized StartResult startReverify(
            long accountUserId,
            String localId,
            String rawUsername,
            String userAlias) {
        activeToken = 0L;
        if (accountUserId <= 0) return new StartResult(null, Rejection.ACCOUNT_REQUIRED);
        if (localId == null || localId.isBlank()) {
            return new StartResult(null, Rejection.DESTINATION_MISSING);
        }
        String username = UsernameNormalizer.normalize(rawUsername);
        if (!UsernameNormalizer.isValid(username)) {
            return new StartResult(null, Rejection.INVALID_USERNAME);
        }
        Request request = new Request(++nextToken, Mode.REVERIFY, accountUserId,
                localId, username, normalizeAlias(userAlias));
        activeToken = request.token();
        return new StartResult(request, null);
    }

    public synchronized boolean isCurrent(Request request) {
        return request != null && request.token() == activeToken;
    }

    public synchronized Resolution reject(Request request, Rejection rejection) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(rejection, "rejection");
        if (request.token() != activeToken) {
            return new Resolution(null, Rejection.STALE_REQUEST);
        }
        activeToken = 0L;
        return new Resolution(null, rejection);
    }

    public synchronized void cancelCurrent() {
        activeToken = 0L;
    }

    public synchronized Resolution complete(
            Request request,
            long currentAccountUserId,
            DestinationCatalog catalog,
            ResolvedPeer peer,
            long verifiedAtEpochMillis) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(peer, "peer");
        if (request.token() != activeToken) {
            return new Resolution(null, Rejection.STALE_REQUEST);
        }
        activeToken = 0L;
        if (request.accountUserId() != currentAccountUserId) {
            return new Resolution(null, Rejection.ACCOUNT_MISMATCH);
        }
        if (!peer.privateChat()) {
            return new Resolution(null, Rejection.NOT_PRIVATE_CHAT);
        }
        if (!peer.bot()) {
            return new Resolution(null, Rejection.NOT_BOT);
        }
        if (peer.chatId() == 0 || peer.peerUserId() <= 0 || verifiedAtEpochMillis <= 0) {
            return new Resolution(null, Rejection.INCOMPLETE_IDENTITY);
        }
        String resolvedUsername = UsernameNormalizer.normalize(peer.primaryUsername());
        if (!UsernameNormalizer.isValid(resolvedUsername)) {
            return new Resolution(null, Rejection.MISSING_CANONICAL_USERNAME);
        }
        Destination existing = request.mode() == Mode.REVERIFY
                ? catalog.find(request.localId()).orElse(null)
                : null;
        if (request.mode() == Mode.REVERIFY && existing == null) {
            return new Resolution(null, Rejection.DESTINATION_MISSING);
        }
        if (existing != null && !identityCompatible(existing, request.accountUserId(), peer)) {
            return new Resolution(null, Rejection.IDENTITY_CHANGED);
        }
        if (conflictsWithAnother(catalog, existing, request.accountUserId(), peer)) {
            return new Resolution(null, Rejection.DUPLICATE_DESTINATION);
        }
        String localId = existing == null
                ? "bot:" + request.accountUserId() + ":" + peer.peerUserId()
                : existing.localId();
        long revision = existing == null ? 1L : existing.verificationRevision() + 1L;
        boolean enabled = existing == null || existing.enabled();
        Destination.VerificationStatus status = enabled
                ? Destination.VerificationStatus.VERIFIED
                : Destination.VerificationStatus.DISABLED;
        Destination destination = new Destination(
                localId,
                request.accountUserId(),
                peer.chatId(),
                peer.peerUserId(),
                request.configuredUsername(),
                resolvedUsername,
                Objects.requireNonNull(peer.title(), "title"),
                request.userAlias().isEmpty() ? peer.title() : request.userAlias(),
                status,
                revision,
                verifiedAtEpochMillis,
                enabled);
        return new Resolution(destination, null);
    }

    private static String normalizeAlias(String alias) {
        return alias == null ? "" : alias.trim();
    }

    private static boolean identityCompatible(
            Destination existing, long accountUserId, ResolvedPeer peer) {
        return (existing.accountUserId() == 0 || existing.accountUserId() == accountUserId)
                && (existing.chatId() == 0 || existing.chatId() == peer.chatId())
                && (existing.peerUserId() == 0 || existing.peerUserId() == peer.peerUserId());
    }

    private static boolean conflictsWithAnother(
            DestinationCatalog catalog,
            Destination existing,
            long accountUserId,
            ResolvedPeer peer) {
        for (Destination candidate : catalog.destinations()) {
            if (existing != null && candidate.localId().equals(existing.localId())) continue;
            boolean sameOwner = candidate.accountUserId() == 0
                    || candidate.accountUserId() == accountUserId;
            boolean sameChat = candidate.chatId() != 0 && candidate.chatId() == peer.chatId();
            boolean samePeer = candidate.peerUserId() != 0
                    && candidate.peerUserId() == peer.peerUserId();
            if (sameOwner && (sameChat || samePeer)) return true;
        }
        return false;
    }
}

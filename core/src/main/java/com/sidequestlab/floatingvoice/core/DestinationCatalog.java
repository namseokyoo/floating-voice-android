package com.sidequestlab.floatingvoice.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

/** Immutable validated destination collection. It never substitutes a fallback route. */
public final class DestinationCatalog {
    private final List<Destination> destinations;
    private final Map<String, Destination> byLocalId;
    private final String defaultLocalId;

    public DestinationCatalog(List<Destination> destinations) {
        this(destinations, null);
    }

    private DestinationCatalog(List<Destination> destinations, String defaultLocalId) {
        Objects.requireNonNull(destinations, "destinations");
        List<Destination> copy = new ArrayList<>(destinations.size());
        Map<String, Destination> localIds = new LinkedHashMap<>();
        Set<AccountIdentity> chatIds = new HashSet<>();
        Set<AccountIdentity> canonicalPeers = new HashSet<>();
        for (Destination destination : destinations) {
            Destination value = Objects.requireNonNull(destination, "destination");
            if (localIds.putIfAbsent(value.localId(), value) != null) {
                throw new IllegalArgumentException("duplicate localId: " + value.localId());
            }
            if (value.chatId() != 0
                    && !chatIds.add(new AccountIdentity(
                    value.accountUserId(), value.chatId()))) {
                throw new IllegalArgumentException("duplicate chatId: " + value.chatId());
            }
            if (value.peerUserId() != 0
                    && !canonicalPeers.add(new AccountIdentity(
                    value.accountUserId(), value.peerUserId()))) {
                throw new IllegalArgumentException("duplicate canonical peer: " + value.peerUserId());
            }
            copy.add(value);
        }
        this.destinations = Collections.unmodifiableList(copy);
        this.byLocalId = Collections.unmodifiableMap(localIds);
        if (defaultLocalId != null && !localIds.containsKey(defaultLocalId)) {
            throw new IllegalArgumentException("default destination is missing: " + defaultLocalId);
        }
        this.defaultLocalId = defaultLocalId;
    }

    public static DestinationCatalog empty() {
        return new DestinationCatalog(List.of());
    }

    public static DestinationCatalog restore(List<Destination> destinations,
                                             String defaultLocalId) {
        return new DestinationCatalog(destinations, defaultLocalId);
    }

    public List<Destination> destinations() {
        return destinations;
    }

    public Optional<Destination> find(String localId) {
        return Optional.ofNullable(byLocalId.get(localId));
    }

    public Optional<Destination> selectable(String localId, long authenticatedAccountUserId) {
        Destination destination = byLocalId.get(localId);
        return destination != null && destination.selectableBy(authenticatedAccountUserId)
                ? Optional.of(destination)
                : Optional.empty();
    }

    public Optional<String> defaultLocalId() {
        return Optional.ofNullable(defaultLocalId);
    }

    public Optional<Destination> defaultSelectable(long authenticatedAccountUserId) {
        return defaultLocalId == null
                ? Optional.empty()
                : selectable(defaultLocalId, authenticatedAccountUserId);
    }

    public DestinationCatalog withDefault(String localId, long authenticatedAccountUserId) {
        if (selectable(localId, authenticatedAccountUserId).isEmpty()) {
            throw new IllegalArgumentException("default destination is not selectable: " + localId);
        }
        return new DestinationCatalog(destinations, localId);
    }

    public DestinationCatalog withDestination(Destination destination) {
        Destination replacement = Objects.requireNonNull(destination, "destination");
        Destination existing = byLocalId.get(replacement.localId());
        List<Destination> updated = new ArrayList<>(destinations);
        if (existing == null) {
            updated.add(replacement);
        } else {
            requireCompatibleIdentity(existing, replacement);
            if (replacement.verificationRevision() < existing.verificationRevision()) {
                throw new IllegalArgumentException("verification revision cannot move backward");
            }
            updated.set(updated.indexOf(existing), replacement);
        }
        return new DestinationCatalog(updated, defaultLocalId);
    }

    public DestinationCatalog withoutDestination(String localId) {
        Destination existing = byLocalId.get(Objects.requireNonNull(localId, "localId"));
        if (existing == null) {
            throw new IllegalArgumentException("destination is missing: " + localId);
        }
        if (localId.equals(defaultLocalId)) {
            throw new IllegalArgumentException(
                    "default destination deletion requires an explicit recovery choice");
        }
        List<Destination> updated = new ArrayList<>(destinations);
        updated.remove(existing);
        return new DestinationCatalog(updated, defaultLocalId);
    }

    /** Atomically replaces the default and removes the previous default. */
    public DestinationCatalog replacingDefaultAndRemoving(
            String removedLocalId, String replacementLocalId,
            long authenticatedAccountUserId) {
        requireCurrentDefault(removedLocalId);
        if (Objects.equals(removedLocalId, replacementLocalId)) {
            throw new IllegalArgumentException("replacement must differ from removed destination");
        }
        if (selectable(replacementLocalId, authenticatedAccountUserId).isEmpty()) {
            throw new IllegalArgumentException(
                    "replacement default is not selectable: " + replacementLocalId);
        }
        List<Destination> updated = new ArrayList<>(destinations);
        updated.remove(byLocalId.get(removedLocalId));
        return new DestinationCatalog(updated, replacementLocalId);
    }

    /** Removes the default only when no selectable recovery destination exists. */
    public DestinationCatalog withoutDefaultDestination(
            String removedLocalId, long authenticatedAccountUserId) {
        requireCurrentDefault(removedLocalId);
        for (Destination destination : destinations) {
            if (!destination.localId().equals(removedLocalId)
                    && destination.selectableBy(authenticatedAccountUserId)) {
                throw new IllegalArgumentException(
                        "selectable replacement exists: " + destination.localId());
            }
        }
        List<Destination> updated = new ArrayList<>(destinations);
        updated.remove(byLocalId.get(removedLocalId));
        return new DestinationCatalog(updated, null);
    }

    private void requireCurrentDefault(String localId) {
        Objects.requireNonNull(localId, "localId");
        if (!localId.equals(defaultLocalId) || !byLocalId.containsKey(localId)) {
            throw new IllegalArgumentException("destination is not the current default: " + localId);
        }
    }

    public DestinationCatalog reordered(List<String> orderedLocalIds) {
        Objects.requireNonNull(orderedLocalIds, "orderedLocalIds");
        if (orderedLocalIds.size() != destinations.size()) {
            throw new IllegalArgumentException("reorder must contain every destination");
        }
        List<Destination> reordered = new ArrayList<>(destinations.size());
        Set<String> seen = new HashSet<>();
        for (String localId : orderedLocalIds) {
            Destination destination = byLocalId.get(localId);
            if (destination == null || !seen.add(localId)) {
                throw new IllegalArgumentException("invalid reorder destination: " + localId);
            }
            reordered.add(destination);
        }
        return new DestinationCatalog(reordered, defaultLocalId);
    }

    private static void requireCompatibleIdentity(Destination existing,
                                                  Destination replacement) {
        if (!identityCanBeFilled(existing.accountUserId(), replacement.accountUserId())
                || !identityCanBeFilled(existing.chatId(), replacement.chatId())
                || !identityCanBeFilled(existing.peerUserId(), replacement.peerUserId())) {
            throw new IllegalArgumentException("destination identity cannot be rebound: "
                    + existing.localId());
        }
    }

    private static boolean identityCanBeFilled(long previous, long next) {
        return previous == 0 || previous == next;
    }

    private record AccountIdentity(long accountUserId, long identityId) {}
}

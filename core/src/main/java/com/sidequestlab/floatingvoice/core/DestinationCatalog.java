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

    private record AccountIdentity(long accountUserId, long identityId) {}
}

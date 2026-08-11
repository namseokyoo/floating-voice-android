package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DestinationCatalogTest {
    @Test
    void verifiedEnabledDestinationIsSelectableOnlyByItsAuthenticatedAccount() {
        Destination destination = destination(
                "primary", 7L, 100L, 500L,
                Destination.VerificationStatus.VERIFIED, true);
        DestinationCatalog catalog = new DestinationCatalog(List.of(destination));

        assertEquals(destination, catalog.selectable("primary", 7L).orElseThrow());
        assertTrue(catalog.selectable("primary", 8L).isEmpty());
        assertTrue(catalog.selectable("missing", 7L).isEmpty());
    }

    @Test
    void unverifiedInvalidAndDisabledDestinationsAreNeverSelectable() {
        for (Destination.VerificationStatus status : List.of(
                Destination.VerificationStatus.NEEDS_REVERIFY,
                Destination.VerificationStatus.VERIFYING,
                Destination.VerificationStatus.INVALID)) {
            DestinationCatalog catalog = new DestinationCatalog(List.of(
                    destination("candidate", 7L, 100L, 500L, status, true)));
            assertTrue(catalog.selectable("candidate", 7L).isEmpty(), status.toString());
        }
        DestinationCatalog disabled = new DestinationCatalog(List.of(
                destination("candidate", 7L, 100L, 500L,
                        Destination.VerificationStatus.DISABLED, false)));
        assertTrue(disabled.selectable("candidate", 7L).isEmpty());
    }

    @Test
    void unverifiedLegacyCandidateMayCarryUnknownCanonicalIdentity() {
        Destination candidate = new Destination(
                "legacy", 0L, 4242L, 0L,
                "legacy_bot", "legacy_bot", "Legacy Bot", "",
                Destination.VerificationStatus.NEEDS_REVERIFY, 0L, 0L, true);

        assertFalse(candidate.selectableBy(7L));
        assertThrows(IllegalArgumentException.class, () -> new Destination(
                "broken-verified", 0L, 4242L, 0L,
                "legacy_bot", "legacy_bot", "Legacy Bot", "",
                Destination.VerificationStatus.VERIFIED, 0L, 0L, true));
    }

    @Test
    void duplicateLocalIdChatIdOrCanonicalPeerIsRejected() {
        Destination first = destination(
                "first", 7L, 100L, 500L,
                Destination.VerificationStatus.VERIFIED, true);

        assertThrows(IllegalArgumentException.class, () -> new DestinationCatalog(List.of(
                first,
                destination("first", 7L, 101L, 501L,
                        Destination.VerificationStatus.VERIFIED, true))));
        assertThrows(IllegalArgumentException.class, () -> new DestinationCatalog(List.of(
                first,
                destination("second", 7L, 100L, 501L,
                        Destination.VerificationStatus.VERIFIED, true))));
        assertThrows(IllegalArgumentException.class, () -> new DestinationCatalog(List.of(
                first,
                destination("second", 7L, 101L, 500L,
                        Destination.VerificationStatus.VERIFIED, true))));

        assertDoesNotThrow(() -> new DestinationCatalog(List.of(
                first,
                destination("other-account", 8L, 100L, 500L,
                        Destination.VerificationStatus.VERIFIED, true))));
    }

    @Test
    void defaultIsAnImmutableCatalogValueAndNeverAutoFallsBack() {
        Destination primary = destination(
                "primary", 7L, 100L, 500L,
                Destination.VerificationStatus.VERIFIED, true);
        Destination secondary = destination(
                "secondary", 7L, 200L, 600L,
                Destination.VerificationStatus.VERIFIED, true);
        DestinationCatalog original = new DestinationCatalog(List.of(primary, secondary));

        DestinationCatalog changed = original.withDefault("secondary", 7L);

        assertTrue(original.defaultLocalId().isEmpty());
        assertEquals("secondary", changed.defaultLocalId().orElseThrow());
        assertEquals(secondary, changed.defaultSelectable(7L).orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> original.withDefault("missing", 7L));
    }

    static Destination destination(String localId, long accountUserId, long chatId,
                                   long peerUserId,
                                   Destination.VerificationStatus status,
                                   boolean enabled) {
        return new Destination(
                localId, accountUserId, chatId, peerUserId,
                "configured_bot", "resolved_bot", "Resolved Bot", "Family",
                status, 3L, 1_700_000_000_000L, enabled);
    }
}

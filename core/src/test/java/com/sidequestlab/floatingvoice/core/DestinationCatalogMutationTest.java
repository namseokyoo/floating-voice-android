package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DestinationCatalogMutationTest {
    @Test
    void addUpdateAndReorderReturnValidatedCopiesWithoutMutatingOriginal() {
        Destination primary = verified("primary", 100L, 500L, "Primary", 1L);
        Destination secondary = verified("secondary", 200L, 600L, "Secondary", 1L);
        DestinationCatalog original = DestinationCatalog.restore(List.of(primary), "primary");

        DestinationCatalog added = original.withDestination(secondary);
        Destination updatedSecondary = verified(
                "secondary", 200L, 600L, "업무 봇 😄", 2L);
        DestinationCatalog updated = added.withDestination(updatedSecondary);
        DestinationCatalog reordered = updated.reordered(List.of("secondary", "primary"));

        assertEquals(List.of(primary), original.destinations());
        assertEquals(List.of(primary, secondary), added.destinations());
        assertEquals(List.of(primary, updatedSecondary), updated.destinations());
        assertEquals(List.of(updatedSecondary, primary), reordered.destinations());
        assertEquals("primary", reordered.defaultLocalId().orElseThrow());
    }

    @Test
    void updateCannotRebindKnownIdentityOrMoveRevisionBackward() {
        Destination primary = verified("primary", 100L, 500L, "Primary", 3L);
        DestinationCatalog catalog = DestinationCatalog.restore(List.of(primary), "primary");

        assertThrows(IllegalArgumentException.class, () -> catalog.withDestination(
                verified("primary", 999L, 500L, "Wrong chat", 4L)));
        assertThrows(IllegalArgumentException.class, () -> catalog.withDestination(
                verified("primary", 100L, 777L, "Wrong peer", 4L)));
        assertThrows(IllegalArgumentException.class, () -> catalog.withDestination(
                verified("primary", 100L, 500L, "Old revision", 2L)));
    }

    @Test
    void legacyUnknownIdentityMayBeFilledOnceByVerification() {
        Destination legacy = new Destination(
                "legacy", 0L, 4242L, 0L,
                "legacy_bot", "legacy_bot", "Legacy", "",
                Destination.VerificationStatus.NEEDS_REVERIFY, 0L, 0L, true);
        DestinationCatalog catalog = new DestinationCatalog(List.of(legacy));
        Destination verified = new Destination(
                "legacy", 7L, 4242L, 700L,
                "legacy_bot", "legacy_bot", "Legacy", "Legacy",
                Destination.VerificationStatus.VERIFIED, 1L, 1_700_000_000_000L, true);

        DestinationCatalog updated = catalog.withDestination(verified);

        assertEquals(verified, updated.find("legacy").orElseThrow());
    }

    @Test
    void unknownChatMayBeFilledWithNegativeTdlibChatId() {
        Destination unknown = new Destination(
                "legacy", 0L, 0L, 0L,
                "legacy_bot", "legacy_bot", "Legacy", "",
                Destination.VerificationStatus.NEEDS_REVERIFY, 0L, 0L, true);
        Destination verified = new Destination(
                "legacy", 7L, -10042L, 700L,
                "legacy_bot", "legacy_bot", "Legacy", "Legacy",
                Destination.VerificationStatus.VERIFIED, 1L,
                1_700_000_000_000L, true);

        DestinationCatalog updated = new DestinationCatalog(List.of(unknown))
                .withDestination(verified);

        assertEquals(-10042L, updated.find("legacy").orElseThrow().chatId());
    }

    @Test
    void disablingDefaultKeepsItsIdButBlocksSelectionWithoutFallback() {
        Destination primary = verified("primary", 100L, 500L, "Primary", 1L);
        Destination secondary = verified("secondary", 200L, 600L, "Secondary", 1L);
        DestinationCatalog catalog = DestinationCatalog.restore(
                List.of(primary, secondary), "primary");
        Destination disabledPrimary = new Destination(
                "primary", 7L, 100L, 500L,
                "primary_configured", "primary_resolved", "Primary", "Primary",
                Destination.VerificationStatus.DISABLED,
                2L, 1_700_000_100_000L, false);

        DestinationCatalog disabled = catalog.withDestination(disabledPrimary);

        assertEquals("primary", disabled.defaultLocalId().orElseThrow());
        assertTrue(disabled.defaultSelectable(7L).isEmpty());
        assertEquals(secondary, disabled.selectable("secondary", 7L).orElseThrow());
    }

    @Test
    void deletingDefaultRequiresAnExplicitRecoveryChoice() {
        DestinationCatalog catalog = DestinationCatalog.restore(List.of(
                verified("primary", 100L, 500L, "Primary", 1L),
                verified("secondary", 200L, 600L, "Secondary", 1L)), "primary");

        assertThrows(IllegalArgumentException.class,
                () -> catalog.withoutDestination("primary"));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.withoutDefaultDestination("primary", 7L));

        DestinationCatalog removed = catalog.replacingDefaultAndRemoving(
                "primary", "secondary", 7L);

        assertEquals("secondary", removed.defaultLocalId().orElseThrow());
        assertEquals(List.of("secondary"), removed.destinations().stream()
                .map(Destination::localId).toList());
    }

    @Test
    void deletingDefaultWithoutSelectableReplacementExplicitlyLeavesNoDefault() {
        Destination primary = verified("primary", 100L, 500L, "Primary", 1L);
        Destination needsReverify = new Destination(
                "recovery", 7L, 200L, 600L,
                "recovery_bot", "recovery_bot", "Recovery", "Recovery",
                Destination.VerificationStatus.NEEDS_REVERIFY,
                2L, 1_700_000_100_000L, true);
        DestinationCatalog catalog = DestinationCatalog.restore(
                List.of(primary, needsReverify), "primary");

        DestinationCatalog removed = catalog.withoutDefaultDestination("primary", 7L);

        assertTrue(removed.defaultLocalId().isEmpty());
        assertEquals(needsReverify, removed.find("recovery").orElseThrow());
    }

    @Test
    void replacementMustBeAnotherSelectableDestination() {
        Destination primary = verified("primary", 100L, 500L, "Primary", 1L);
        Destination disabled = new Destination(
                "disabled", 7L, 200L, 600L,
                "disabled_bot", "disabled_bot", "Disabled", "Disabled",
                Destination.VerificationStatus.DISABLED,
                2L, 1_700_000_100_000L, false);
        DestinationCatalog catalog = DestinationCatalog.restore(
                List.of(primary, disabled), "primary");

        assertThrows(IllegalArgumentException.class,
                () -> catalog.replacingDefaultAndRemoving("primary", "primary", 7L));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.replacingDefaultAndRemoving("primary", "disabled", 7L));
    }

    @Test
    void reorderMustContainEveryIdExactlyOnce() {
        DestinationCatalog catalog = new DestinationCatalog(List.of(
                verified("primary", 100L, 500L, "Primary", 1L),
                verified("secondary", 200L, 600L, "Secondary", 1L)));

        assertThrows(IllegalArgumentException.class,
                () -> catalog.reordered(List.of("primary")));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.reordered(List.of("primary", "primary")));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.reordered(List.of("primary", "missing")));
    }

    private static Destination verified(
            String id, long chatId, long peerId, String title, long revision) {
        return new Destination(id, 7L, chatId, peerId,
                id + "_configured", id + "_resolved", title, title,
                Destination.VerificationStatus.VERIFIED,
                revision, 1_700_000_000_000L + revision, true);
    }
}

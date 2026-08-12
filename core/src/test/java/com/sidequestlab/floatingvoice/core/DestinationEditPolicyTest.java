package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DestinationEditPolicyTest {
    @Test
    void aliasEditKeepsIdentityAndRevision() {
        Destination updated = DestinationEditPolicy.withAlias(base(), "업무 봇");

        assertEquals("업무 봇", updated.userAlias());
        assertEquals(base().localId(), updated.localId());
        assertEquals(base().chatId(), updated.chatId());
        assertEquals(base().verificationRevision(), updated.verificationRevision());
    }

    @Test
    void blankAliasIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DestinationEditPolicy.withAlias(base(), "   "));
    }

    @Test
    void disableMarksStatusAndBumpsRevision() {
        Destination disabled = DestinationEditPolicy.disable(base(), 1_700_000_900_000L);

        assertFalse(disabled.enabled());
        assertEquals(Destination.VerificationStatus.DISABLED, disabled.verificationStatus());
        assertEquals(base().verificationRevision() + 1, disabled.verificationRevision());
        assertEquals(1_700_000_900_000L, disabled.verifiedAtEpochMillis());
        assertFalse(disabled.selectableBy(7L));
    }

    @Test
    void enableRestoresVerifiedSelectableState() {
        Destination disabled = DestinationEditPolicy.disable(base(), 1_700_000_900_000L);
        Destination enabled = DestinationEditPolicy.enable(disabled, 1_700_000_950_000L);

        assertTrue(enabled.enabled());
        assertEquals(Destination.VerificationStatus.VERIFIED, enabled.verificationStatus());
        assertEquals(disabled.verificationRevision() + 1, enabled.verificationRevision());
        assertTrue(enabled.selectableBy(7L));
    }

    @Test
    void enableRefusesIncompleteIdentity() {
        Destination disabled = new Destination(
                "legacy", 0L, 1234L, 0L,
                "legacy_bot", "legacy_bot", "Legacy", "",
                Destination.VerificationStatus.DISABLED, 1L, 1L, false);

        assertThrows(IllegalArgumentException.class,
                () -> DestinationEditPolicy.enable(disabled, 2L));
    }

    @Test
    void disableRefusesInvalidOrNeedsReverifyStateToPreventVerifiedEscalation() {
        for (Destination.VerificationStatus status : List.of(
                Destination.VerificationStatus.INVALID,
                Destination.VerificationStatus.NEEDS_REVERIFY,
                Destination.VerificationStatus.VERIFYING)) {
            Destination unsafe = new Destination(
                    "unsafe", 7L, 100L, 500L,
                    "configured_bot", "resolved_bot", "Resolved", "Unsafe",
                    status, 3L, 1L, true);

            assertThrows(IllegalArgumentException.class,
                    () -> DestinationEditPolicy.disable(unsafe, 2L));
        }
    }

    @Test
    void enableRefusesNonDisabledStateEvenWithCompleteIdentity() {
        Destination invalid = new Destination(
                "unsafe", 7L, 100L, 500L,
                "configured_bot", "resolved_bot", "Resolved", "Unsafe",
                Destination.VerificationStatus.INVALID, 3L, 1L, true);

        assertThrows(IllegalArgumentException.class,
                () -> DestinationEditPolicy.enable(invalid, 2L));
    }

    @Test
    void enableOnlyWorksFromDisabledState() {
        assertThrows(IllegalArgumentException.class,
                () -> DestinationEditPolicy.enable(base(), 2L));
    }

    @Test
    void catalogAppliesEditWithoutIdentityRebind() {
        DestinationCatalog catalog = DestinationCatalog.restore(List.of(base()), "base");

        DestinationCatalog disabled = catalog.withDestination(
                DestinationEditPolicy.disable(base(), 1_700_000_900_000L));
        assertEquals(Destination.VerificationStatus.DISABLED,
                disabled.find("base").orElseThrow().verificationStatus());

        DestinationCatalog enabled = disabled.withDestination(
                DestinationEditPolicy.enable(disabled.find("base").orElseThrow(),
                        1_700_000_950_000L));
        assertTrue(enabled.selectable("base", 7L).isPresent());
    }

    @Test
    void previewCommitAcceptsUnchangedCatalogEntry() {
        assertDoesNotThrow(() -> DestinationEditPolicy.requirePreviewCurrent(base(), base()));
    }

    @Test
    void previewCommitRejectsConcurrentAliasEditEvenWhenRevisionDidNotChange() {
        Destination editedWhilePreviewOpen = DestinationEditPolicy.withAlias(base(), "new alias");

        assertThrows(IllegalStateException.class,
                () -> DestinationEditPolicy.requirePreviewCurrent(base(), editedWhilePreviewOpen));
    }

    @Test
    void previewCommitRejectsConcurrentRevisionChange() {
        Destination disabledWhilePreviewOpen = DestinationEditPolicy.disable(base(), 4L);

        assertThrows(IllegalStateException.class,
                () -> DestinationEditPolicy.requirePreviewCurrent(base(), disabledWhilePreviewOpen));
    }

    private static Destination base() {
        return new Destination(
                "base", 7L, 100L, 500L,
                "configured_bot", "resolved_bot", "Resolved title", "Old alias",
                Destination.VerificationStatus.VERIFIED, 3L, 1_700_000_800_000L, true);
    }
}

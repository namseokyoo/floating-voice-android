package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouteStateMachineTest {
    @Test
    void routeSessionIsBoundToOneAuthenticatedAccount() {
        RouteStateMachine machine = machine(catalog(primary(), secondary()), "primary");

        assertTrue(machine.matchesAuthenticatedAccount(7L));
        assertFalse(machine.matchesAuthenticatedAccount(8L));
        assertFalse(machine.matchesAuthenticatedAccount(0L));
    }

    @Test
    void rebuildingRouteAfterServiceRestartDropsEphemeralSelection() {
        DestinationCatalog stored = catalog(primary(), secondary())
                .withDefault("primary", 7L);
        RouteStateMachine beforeRestart = machine(stored, "primary");
        assertTrue(beforeRestart.select(DestinationScope.NEXT_ONE, "secondary"));

        RouteStateMachine afterRestart = machine(stored, "primary");

        assertTrue(afterRestart.nextOneLocalId().isEmpty());
        assertEquals("primary", afterRestart.startRecording().orElseThrow().localId());
    }

    @Test
    void missingDefaultRequiresExplicitNextOneWithoutImplicitFallback() {
        RouteStateMachine machine = machine(catalog(primary(), secondary()), null);

        assertTrue(machine.startRecording().isEmpty());
        assertTrue(machine.select(DestinationScope.NEXT_ONE, "secondary"));
        assertEquals("secondary", machine.startRecording().orElseThrow().localId());
    }

    @Test
    void nullDefaultExplicitlyClearsRestoredCatalogDefault() {
        DestinationCatalog stored = catalog(primary(), secondary())
                .withDefault("primary", 7L);
        RouteStateMachine machine = machine(stored, null);

        assertTrue(machine.defaultLocalId().isEmpty());
        assertTrue(machine.startRecording().isEmpty());
    }

    @Test
    void defaultChangeDuringRecordingAppliesOnlyToFutureRecording() {
        RouteStateMachine machine = machine(catalog(primary(), secondary()), "primary");

        assertEquals("primary", machine.startRecording().orElseThrow().localId());
        assertTrue(machine.select(DestinationScope.DEFAULT, "secondary"));
        assertEquals("primary", machine.currentDestination().orElseThrow().localId());

        assertTrue(machine.cancelRecording());
        assertEquals("secondary", machine.startRecording().orElseThrow().localId());
    }

    @Test
    void idleDefaultSelectionPersistsAcrossRecordingsWithoutCreatingNextOne() {
        RouteStateMachine machine = machine(catalog(primary(), secondary()), "primary");

        assertTrue(machine.select(DestinationScope.DEFAULT, "secondary"));
        assertEquals("secondary", machine.defaultLocalId().orElseThrow());
        assertTrue(machine.nextOneLocalId().isEmpty());

        assertEquals("secondary", machine.startRecording().orElseThrow().localId());
        assertTrue(machine.cancelRecording());
        assertEquals("secondary", machine.startRecording().orElseThrow().localId());
    }

    @Test
    void idleNextOneIsConsumedExactlyOnceAtRecordingStart() {
        RouteStateMachine machine = machine(catalog(primary(), secondary()), "primary");

        assertTrue(machine.select(DestinationScope.NEXT_ONE, "secondary"));
        assertEquals("secondary", machine.startRecording().orElseThrow().localId());
        assertTrue(machine.nextOneLocalId().isEmpty());

        assertTrue(machine.cancelRecording());
        assertEquals("primary", machine.startRecording().orElseThrow().localId());
    }

    @Test
    void abandonedOneShotBeforeCaptureRestoresConfiguredDefault() {
        RouteStateMachine machine = machine(catalog(primary(), secondary()), "primary");

        assertTrue(machine.select(DestinationScope.NEXT_ONE, "secondary"));
        assertTrue(machine.clearNextOne());
        assertTrue(machine.nextOneLocalId().isEmpty());
        assertEquals("primary", machine.startRecording().orElseThrow().localId());
        assertFalse(machine.clearNextOne());
    }

    @Test
    void canceledNextOneRecordingNeverResurrects() {
        RouteStateMachine machine = machine(catalog(primary(), secondary()), "primary");
        machine.select(DestinationScope.NEXT_ONE, "secondary");
        machine.startRecording();

        assertTrue(machine.cancelRecording());
        assertEquals("primary", machine.startRecording().orElseThrow().localId());
    }

    @Test
    void recordingTimeSelectionChangesCurrentRecordingOnly() {
        RouteStateMachine machine = machine(catalog(primary(), secondary()), "primary");
        machine.startRecording();

        assertTrue(machine.select(DestinationScope.CURRENT_RECORDING, "secondary"));
        assertEquals("secondary", machine.currentDestination().orElseThrow().localId());
        assertEquals("primary", machine.defaultLocalId().orElseThrow());

        machine.cancelRecording();
        assertEquals("primary", machine.startRecording().orElseThrow().localId());
    }

    @Test
    void freezingRejectsCurrentTargetChangesAndCreatesImmutableSnapshot() {
        RouteStateMachine machine = machine(catalog(primary(), secondary()), "primary");
        machine.startRecording();
        assertTrue(machine.beginFreezing());

        assertFalse(machine.select(DestinationScope.CURRENT_RECORDING, "secondary"));
        DispatchTargetSnapshot snapshot = machine.freeze().orElseThrow();
        assertEquals("primary", snapshot.localId());
        assertEquals(7L, snapshot.accountUserId());
        assertEquals(100L, snapshot.chatId());
        assertEquals(500L, snapshot.peerUserId());
        assertEquals(4L, snapshot.verificationRevision());

        Destination changedPrimary = destination(
                "primary", 7L, 100L, 500L, "Changed title", "Changed alias",
                Destination.VerificationStatus.VERIFIED, true);
        machine.replaceCatalog(catalog(changedPrimary, secondary()));
        assertEquals("Resolved Primary", snapshot.resolvedTitle());
        assertEquals("Primary alias", snapshot.userAlias());
    }

    @Test
    void staleNextOneBlocksStartWithoutFallingBackAndRemainsUntilAStartSucceeds() {
        RouteStateMachine machine = machine(catalog(primary(), secondary()), "primary");
        machine.select(DestinationScope.NEXT_ONE, "secondary");
        machine.replaceCatalog(catalog(primary()));

        assertTrue(machine.startRecording().isEmpty());
        assertEquals(RouteStateMachine.Phase.IDLE, machine.phase());
        assertTrue(machine.currentDestination().isEmpty());
        assertEquals("secondary", machine.nextOneLocalId().orElseThrow());

        machine.replaceCatalog(catalog(primary(), secondary()));
        assertEquals("secondary", machine.startRecording().orElseThrow().localId());
        assertTrue(machine.nextOneLocalId().isEmpty());
    }

    @Test
    void invalidDisabledOrAccountMismatchedSelectionIsRejectedWithoutFallback() {
        Destination invalid = destination(
                "invalid", 7L, 300L, 700L, "Invalid", "Invalid",
                Destination.VerificationStatus.INVALID, true);
        Destination disabled = destination(
                "disabled", 7L, 400L, 800L, "Disabled", "Disabled",
                Destination.VerificationStatus.DISABLED, false);
        Destination otherAccount = destination(
                "other", 8L, 500L, 900L, "Other", "Other",
                Destination.VerificationStatus.VERIFIED, true);
        RouteStateMachine machine = machine(
                catalog(primary(), invalid, disabled, otherAccount), "primary");

        assertFalse(machine.select(DestinationScope.NEXT_ONE, "invalid"));
        assertFalse(machine.select(DestinationScope.NEXT_ONE, "disabled"));
        assertFalse(machine.select(DestinationScope.NEXT_ONE, "other"));
        assertTrue(machine.nextOneLocalId().isEmpty());
    }

    @Test
    void missingDefaultBlocksStartWithoutInventingAnotherRoute() {
        RouteStateMachine machine = machine(catalog(primary(), secondary()), "primary");
        machine.replaceCatalog(catalog(secondary()));

        assertTrue(machine.startRecording().isEmpty());
        assertEquals(RouteStateMachine.Phase.IDLE, machine.phase());
        assertTrue(machine.currentDestination().isEmpty());
    }

    @Test
    void illegalPhaseOperationsAreInert() {
        RouteStateMachine machine = machine(catalog(primary(), secondary()), "primary");

        assertFalse(machine.select(DestinationScope.CURRENT_RECORDING, "secondary"));
        assertTrue(machine.freeze().isEmpty());
        assertFalse(machine.cancelRecording());

        machine.startRecording();
        assertFalse(machine.select(DestinationScope.NEXT_ONE, "secondary"));
        assertTrue(machine.startRecording().isEmpty());
        assertTrue(machine.beginFreezing());
        assertFalse(machine.beginFreezing());
        assertFalse(machine.select(DestinationScope.CURRENT_RECORDING, "secondary"));
        assertFalse(machine.cancelRecording());

        DispatchTargetSnapshot snapshot = machine.freeze().orElseThrow();
        assertFalse(machine.cancelRecording());
        assertTrue(machine.completeDispatch(snapshot.routeAttemptId()));
        assertFalse(machine.completeDispatch(snapshot.routeAttemptId()));
        assertEquals(RouteStateMachine.Phase.IDLE, machine.phase());
    }

    @Test
    void staleDispatchCompletionCannotFinishANewerFrozenAttempt() {
        RouteStateMachine machine = machine(catalog(primary()), "primary");
        machine.startRecording();
        machine.beginFreezing();
        DispatchTargetSnapshot first = machine.freeze().orElseThrow();
        assertTrue(machine.completeDispatch(first.routeAttemptId()));

        machine.startRecording();
        machine.beginFreezing();
        DispatchTargetSnapshot second = machine.freeze().orElseThrow();

        assertNotEquals(first.routeAttemptId(), second.routeAttemptId());
        assertFalse(machine.completeDispatch(first.routeAttemptId()));
        assertEquals(RouteStateMachine.Phase.FROZEN, machine.phase());
        assertTrue(machine.completeDispatch(second.routeAttemptId()));
    }

    @Test
    void catalogReplacementCannotRebindAnExistingLocalIdToAnotherIdentity() {
        RouteStateMachine machine = machine(catalog(primary()), "primary");
        Destination rebound = new Destination(
                "primary", 7L, 999L, 777L,
                "other_configured", "other_resolved", "Other", "Other",
                Destination.VerificationStatus.VERIFIED,
                5L, 1_700_000_200_000L, true);

        assertThrows(IllegalArgumentException.class,
                () -> machine.replaceCatalog(catalog(rebound)));
        Destination reboundAccount = new Destination(
                "primary", 8L, 100L, 500L,
                "primary_configured", "primary_resolved", "Primary", "Primary",
                Destination.VerificationStatus.VERIFIED,
                5L, 1_700_000_200_000L, true);
        assertThrows(IllegalArgumentException.class,
                () -> machine.replaceCatalog(catalog(reboundAccount)));
        assertEquals(100L, machine.startRecording().orElseThrow().chatId());

        Destination legacy = new Destination(
                "legacy", 0L, 300L, 0L,
                "legacy_bot", "legacy_bot", "Legacy", "",
                Destination.VerificationStatus.NEEDS_REVERIFY, 0L, 0L, true);
        RouteStateMachine verifying = machine(catalog(primary(), legacy), "primary");
        Destination verifiedLegacy = new Destination(
                "legacy", 7L, 300L, 700L,
                "legacy_bot", "legacy_bot", "Legacy", "Legacy",
                Destination.VerificationStatus.VERIFIED,
                1L, 1_700_000_300_000L, true);
        assertDoesNotThrow(() ->
                verifying.replaceCatalog(catalog(primary(), verifiedLegacy)));
    }

    @Test
    void freezeRevalidatesDeletionDisableAndIdentityRevisionWithoutFallback() {
        for (DestinationCatalog changed : List.of(
                catalog(secondary()),
                catalog(destination(
                        "primary", 7L, 100L, 500L, "Disabled", "Disabled",
                        Destination.VerificationStatus.DISABLED, false), secondary()),
                catalog(new Destination(
                        "primary", 7L, 100L, 500L,
                        "primary_configured", "primary_resolved",
                        "Changed identity revision", "Changed",
                        Destination.VerificationStatus.VERIFIED,
                        5L, 1_700_000_200_000L, true), secondary()))) {
            RouteStateMachine machine = machine(catalog(primary(), secondary()), "primary");
            machine.startRecording();
            machine.replaceCatalog(changed);
            assertTrue(machine.beginFreezing());

            assertTrue(machine.freeze().isEmpty());
            assertEquals(RouteStateMachine.Phase.FREEZING, machine.phase());
            assertEquals("primary", machine.currentDestination().orElseThrow().localId());
            assertFalse(machine.abortFreezing(machine.routeAttemptId() - 1));
            assertTrue(machine.abortFreezing(machine.routeAttemptId()));
            assertEquals(RouteStateMachine.Phase.IDLE, machine.phase());
        }
    }

    private static RouteStateMachine machine(DestinationCatalog catalog, String defaultId) {
        return new RouteStateMachine(7L, catalog, defaultId);
    }

    private static DestinationCatalog catalog(Destination... destinations) {
        return new DestinationCatalog(List.of(destinations));
    }

    private static Destination primary() {
        return destination(
                "primary", 7L, 100L, 500L, "Resolved Primary", "Primary alias",
                Destination.VerificationStatus.VERIFIED, true);
    }

    private static Destination secondary() {
        return destination(
                "secondary", 7L, 200L, 600L, "Resolved Secondary", "Secondary alias",
                Destination.VerificationStatus.VERIFIED, true);
    }

    private static Destination destination(
            String localId, long accountUserId, long chatId, long peerUserId,
            String title, String alias,
            Destination.VerificationStatus status, boolean enabled) {
        return new Destination(
                localId, accountUserId, chatId, peerUserId,
                localId + "_configured", localId + "_resolved", title, alias,
                status, 4L, 1_700_000_100_000L, enabled);
    }
}

package com.sidequestlab.floatingvoice.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class DashboardReadinessTest {
    @Test void missingConfigurationStartsTelegramConnection() {
        assertEquals(DashboardReadiness.State.CONNECT_TELEGRAM,
                DashboardReadiness.evaluate(false, false, false, false, false));
    }

    @Test void configuredAccountResumesAuthentication() {
        assertEquals(DashboardReadiness.State.AUTHENTICATING,
                DashboardReadiness.evaluate(false, true, false, false, false));
    }

    @Test void authenticatedAccountRequiresTargetBeforePermissions() {
        assertEquals(DashboardReadiness.State.SELECT_TARGET,
                DashboardReadiness.evaluate(false, true, true, false, false));
    }

    @Test void confirmedTargetRequiresPermissions() {
        assertEquals(DashboardReadiness.State.GRANT_PERMISSIONS,
                DashboardReadiness.evaluate(false, true, true, true, false));
    }

    @Test void readyAndRunningRequireEveryPrerequisite() {
        assertEquals(DashboardReadiness.State.READY,
                DashboardReadiness.evaluate(false, true, true, true, true));
        assertEquals(DashboardReadiness.State.RUNNING,
                DashboardReadiness.evaluate(true, true, true, true, true));
    }

    @Test void prerequisiteLossOverridesRunningFlag() {
        assertEquals(DashboardReadiness.State.CONNECT_TELEGRAM,
                DashboardReadiness.evaluate(true, false, false, false, false));
        assertEquals(DashboardReadiness.State.AUTHENTICATING,
                DashboardReadiness.evaluate(true, true, false, false, false));
        assertEquals(DashboardReadiness.State.SELECT_TARGET,
                DashboardReadiness.evaluate(true, true, true, false, false));
        assertEquals(DashboardReadiness.State.GRANT_PERMISSIONS,
                DashboardReadiness.evaluate(true, true, true, true, false));
    }
}

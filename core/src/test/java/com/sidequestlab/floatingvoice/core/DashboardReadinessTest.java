package com.sidequestlab.floatingvoice.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class DashboardReadinessTest {
    @Test void missingPrerequisiteOverridesRunningFlag() {
        assertEquals(DashboardReadiness.State.SETUP_REQUIRED,
                DashboardReadiness.evaluate(true, false, false));
        assertEquals(DashboardReadiness.State.SETUP_REQUIRED,
                DashboardReadiness.evaluate(true, true, false));
        assertEquals(DashboardReadiness.State.RUNNING,
                DashboardReadiness.evaluate(true, true, true));
    }

    @Test void readyRequiresTelegramAndPermissions() {
        assertEquals(DashboardReadiness.State.READY,
                DashboardReadiness.evaluate(false, true, true));
        assertEquals(DashboardReadiness.State.SETUP_REQUIRED,
                DashboardReadiness.evaluate(false, true, false));
        assertEquals(DashboardReadiness.State.SETUP_REQUIRED,
                DashboardReadiness.evaluate(false, false, true));
    }
}

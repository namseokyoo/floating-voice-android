package com.sidequestlab.floatingvoice.core;

/** Pure readiness decision for the MainActivity status dashboard. */
public final class DashboardReadiness {
    public enum State { SETUP_REQUIRED, READY, RUNNING }

    private DashboardReadiness() { }

    public static State evaluate(boolean running, boolean telegramReady,
                                 boolean permissionsGranted) {
        if (!telegramReady || !permissionsGranted) return State.SETUP_REQUIRED;
        return running ? State.RUNNING : State.READY;
    }
}

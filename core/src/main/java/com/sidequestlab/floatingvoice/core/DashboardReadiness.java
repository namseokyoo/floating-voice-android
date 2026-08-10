package com.sidequestlab.floatingvoice.core;

/** Pure state decision for the MainActivity Quiet Recorder home. */
public final class DashboardReadiness {
    public enum State {
        CONNECT_TELEGRAM,
        AUTHENTICATING,
        SELECT_TARGET,
        GRANT_PERMISSIONS,
        READY,
        RUNNING
    }

    private DashboardReadiness() { }

    public static State evaluate(boolean running, boolean hasConfiguration,
                                 boolean authenticationComplete, boolean targetConfirmed,
                                 boolean permissionsGranted) {
        if (!hasConfiguration) return State.CONNECT_TELEGRAM;
        if (!authenticationComplete) return State.AUTHENTICATING;
        if (!targetConfirmed) return State.SELECT_TARGET;
        if (!permissionsGranted) return State.GRANT_PERMISSIONS;
        return running ? State.RUNNING : State.READY;
    }
}

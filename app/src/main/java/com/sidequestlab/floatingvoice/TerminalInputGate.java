package com.sidequestlab.floatingvoice;

final class TerminalInputGate {
    private long suppressThroughUptimeMs = Long.MIN_VALUE;

    void suppressTouchesThrough(long uptimeMs) {
        suppressThroughUptimeMs = Math.max(suppressThroughUptimeMs, uptimeMs);
    }

    boolean shouldSuppress(long touchDownUptimeMs) {
        return touchDownUptimeMs <= suppressThroughUptimeMs;
    }
}

package com.sidequestlab.floatingvoice.core;

import java.util.Objects;
import java.util.Optional;

/** Immutable in-memory content/output truth for one explicit user action. */
public final class OutputSnapshot {
    private final long outputAttemptId;
    private final OutputRoute route;
    private final String payload;
    private final DispatchTargetSnapshot telegramTarget;

    OutputSnapshot(long outputAttemptId, OutputRoute route, String payload,
                   DispatchTargetSnapshot telegramTarget) {
        if (outputAttemptId <= 0L) {
            throw new IllegalArgumentException("outputAttemptId must be positive");
        }
        this.outputAttemptId = outputAttemptId;
        this.route = Objects.requireNonNull(route, "route");
        this.payload = requirePayload(payload);
        this.telegramTarget = telegramTarget;
        if (route.requiresTelegramDestination() != (telegramTarget != null)) {
            throw new IllegalArgumentException("Telegram target presence must match route");
        }
    }

    public long outputAttemptId() { return outputAttemptId; }
    public OutputRoute route() { return route; }
    public String payload() { return payload; }
    public Optional<DispatchTargetSnapshot> telegramTarget() {
        return Optional.ofNullable(telegramTarget);
    }

    private static String requirePayload(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("payload must not be blank");
        }
        return value;
    }
}

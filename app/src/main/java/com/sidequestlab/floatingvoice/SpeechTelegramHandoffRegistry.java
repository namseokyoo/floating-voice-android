package com.sidequestlab.floatingvoice;

import java.util.Objects;
import java.util.Optional;

/** Process-memory mailbox for Telegram handoff status; never stores transcript text. */
final class SpeechTelegramHandoffRegistry {
    enum Status { QUEUED, DELIVERED, REJECTED }

    record Event(long handoffId, Status status) { }

    private Event latest;

    synchronized void publish(long handoffId, Status status) {
        if (handoffId <= 0L) throw new IllegalArgumentException("handoffId must be positive");
        latest = new Event(handoffId, Objects.requireNonNull(status, "status"));
    }

    synchronized Optional<Event> latest(long expectedHandoffId) {
        return latest != null && latest.handoffId() == expectedHandoffId
                ? Optional.of(latest) : Optional.empty();
    }

    synchronized void clear(long expectedHandoffId) {
        if (latest != null && latest.handoffId() == expectedHandoffId) latest = null;
    }
}

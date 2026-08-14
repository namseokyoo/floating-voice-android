package com.sidequestlab.floatingvoice.core;

import java.util.Objects;
import java.util.Optional;

/** Generation-bound, process-local ownership for the single microphone capture resource. */
public final class AudioCaptureOwnership {
    public enum Owner {
        NONE,
        RECORDING,
        STT
    }

    public record Lease(Owner owner, long generation) {
        public Lease {
            Objects.requireNonNull(owner, "owner");
            if (owner == Owner.NONE) throw new IllegalArgumentException("NONE cannot own a lease");
            if (generation <= 0L) throw new IllegalArgumentException("generation must be positive");
        }
    }

    private Owner owner = Owner.NONE;
    private long generation;

    public synchronized Owner owner() {
        return owner;
    }

    public synchronized long generation() {
        return generation;
    }

    public synchronized Optional<Lease> acquire(Owner requestedOwner) {
        Objects.requireNonNull(requestedOwner, "requestedOwner");
        if (requestedOwner == Owner.NONE) {
            throw new IllegalArgumentException("NONE cannot acquire ownership");
        }
        if (owner != Owner.NONE) return Optional.empty();
        generation++;
        owner = requestedOwner;
        return Optional.of(new Lease(owner, generation));
    }

    public synchronized boolean release(Lease lease) {
        Objects.requireNonNull(lease, "lease");
        if (owner != lease.owner() || generation != lease.generation()) return false;
        owner = Owner.NONE;
        return true;
    }

    public synchronized void releaseAll() {
        generation++;
        owner = Owner.NONE;
    }
}

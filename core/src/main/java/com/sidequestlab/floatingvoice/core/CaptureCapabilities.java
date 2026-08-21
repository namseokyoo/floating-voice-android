package com.sidequestlab.floatingvoice.core;

import java.util.Objects;

/** Independent route capability decisions; a denied action never changes routes. */
public final class CaptureCapabilities {
    public enum Action {
        SHOW_OVERLAY,
        START_TELEGRAM_RECORDING,
        START_STT,
        START_LOCAL_CAPTURE,
        START_SYSTEM_AUDIO_SHARE
    }

    public enum UnavailableReason {
        NONE,
        NO_CAPTURE_ACTION_AVAILABLE,
        TELEGRAM_UNAVAILABLE,
        STT_UNAVAILABLE,
        LOCAL_OUTPUT_NOT_SELECTED,
        LOCAL_OUTPUT_UNAVAILABLE,
        SYSTEM_AUDIO_SHARE_UNAVAILABLE
    }

    public record Decision(Action requestedAction, Action effectiveAction,
                           boolean allowed, UnavailableReason unavailableReason) {
        public Decision {
            Objects.requireNonNull(requestedAction, "requestedAction");
            Objects.requireNonNull(effectiveAction, "effectiveAction");
            Objects.requireNonNull(unavailableReason, "unavailableReason");
        }

        public boolean fellBack() {
            return requestedAction != effectiveAction;
        }
    }

    private final boolean telegramAvailable;
    private final boolean sttAvailable;
    private final boolean localOutputSelected;
    private final boolean localOutputAvailable;
    private final boolean systemAudioShareAvailable;

    public CaptureCapabilities(boolean telegramAvailable, boolean sttAvailable,
                               boolean localOutputSelected, boolean localOutputAvailable) {
        this(telegramAvailable, sttAvailable, localOutputSelected, localOutputAvailable, false);
    }

    public CaptureCapabilities(boolean telegramAvailable, boolean sttAvailable,
                               boolean localOutputSelected, boolean localOutputAvailable,
                               boolean systemAudioShareAvailable) {
        this.telegramAvailable = telegramAvailable;
        this.sttAvailable = sttAvailable;
        this.localOutputSelected = localOutputSelected;
        this.localOutputAvailable = localOutputAvailable;
        this.systemAudioShareAvailable = systemAudioShareAvailable;
    }

    public Decision decide(Action action) {
        Objects.requireNonNull(action, "action");
        return switch (action) {
            case SHOW_OVERLAY -> decision(action,
                    telegramAvailable || sttAvailable
                            || (localOutputSelected && localOutputAvailable)
                            || systemAudioShareAvailable,
                    UnavailableReason.NO_CAPTURE_ACTION_AVAILABLE);
            case START_TELEGRAM_RECORDING -> decision(action, telegramAvailable,
                    UnavailableReason.TELEGRAM_UNAVAILABLE);
            case START_STT -> decision(action, sttAvailable,
                    UnavailableReason.STT_UNAVAILABLE);
            case START_LOCAL_CAPTURE -> {
                if (!localOutputSelected) {
                    yield decision(action, false,
                            UnavailableReason.LOCAL_OUTPUT_NOT_SELECTED);
                }
                yield decision(action, localOutputAvailable,
                        UnavailableReason.LOCAL_OUTPUT_UNAVAILABLE);
            }
            case START_SYSTEM_AUDIO_SHARE -> decision(action, systemAudioShareAvailable,
                    UnavailableReason.SYSTEM_AUDIO_SHARE_UNAVAILABLE);
        };
    }

    private static Decision decision(Action action, boolean allowed,
                                     UnavailableReason deniedReason) {
        return new Decision(action, action, allowed,
                allowed ? UnavailableReason.NONE : deniedReason);
    }
}

package com.sidequestlab.floatingvoice.core;

import java.util.List;
import java.util.Objects;

public final class OverlayStateMachine {
    public enum State {
        IDLE,
        MENU_OPEN,
        VOICE_STARTING,
        RECORDING,
        VOICE_STOPPING,
        VOICE_CANCELING,
        VOICE_QUEUEING,
        VOICE_PENDING,
        TEXT_COMPOSING,
        TEXT_QUEUEING,
        TEXT_PENDING,
        TEARING_DOWN
    }

    public enum Effect {
        START_VOICE,
        STOP_VOICE,
        CANCEL_VOICE,
        SEND_VOICE,
        SEND_TEXT,
        SHOW_MENU,
        HIDE_MENU,
        OPEN_TEXT_COMPOSER
    }

    public record Transition(State previousState, State nextState, List<Effect> effects) {
        public Transition {
            effects = List.copyOf(effects);
        }
    }

    private State state = State.IDLE;
    private long attemptId;

    public synchronized State state() {
        return state;
    }

    public synchronized long attemptId() {
        return attemptId;
    }

    public synchronized Transition accept(OverlayEvent event) {
        Objects.requireNonNull(event, "event");
        return acceptCurrent(event);
    }

    public synchronized Transition accept(OverlayEvent event, long expectedAttemptId) {
        Objects.requireNonNull(event, "event");
        if (expectedAttemptId != attemptId) {
            return new Transition(state, state, List.of());
        }
        return acceptCurrent(event);
    }

    private Transition acceptCurrent(OverlayEvent event) {
        State previousState = state;
        List<Effect> effects = List.of();

        if (state == State.TEARING_DOWN) {
            return new Transition(previousState, state, effects);
        }
        if (event == OverlayEvent.TEARDOWN) {
            state = State.TEARING_DOWN;
            return new Transition(previousState, state, effects);
        }

        switch (state) {
            case IDLE -> {
                if (event == OverlayEvent.TAP) {
                    attemptId++;
                    state = State.VOICE_STARTING;
                    effects = List.of(Effect.START_VOICE);
                } else if (event == OverlayEvent.LONG_PRESS) {
                    state = State.MENU_OPEN;
                    effects = List.of(Effect.SHOW_MENU);
                }
            }
            case MENU_OPEN -> {
                if (event == OverlayEvent.TAP || event == OverlayEvent.GESTURE_CANCELED) {
                    state = State.IDLE;
                    effects = List.of(Effect.HIDE_MENU);
                } else if (event == OverlayEvent.COMPOSE_TEXT) {
                    state = State.TEXT_COMPOSING;
                    effects = List.of(Effect.HIDE_MENU, Effect.OPEN_TEXT_COMPOSER);
                }
            }
            case VOICE_STARTING -> {
                if (event == OverlayEvent.VOICE_START_SUCCEEDED) {
                    state = State.RECORDING;
                } else if (event == OverlayEvent.VOICE_START_FAILED) {
                    state = State.IDLE;
                }
            }
            case RECORDING -> {
                if (event == OverlayEvent.TAP) {
                    state = State.VOICE_STOPPING;
                    effects = List.of(Effect.STOP_VOICE);
                } else if (event == OverlayEvent.CANCEL_VOICE_REQUESTED) {
                    state = State.VOICE_CANCELING;
                    effects = List.of(Effect.CANCEL_VOICE);
                }
            }
            case VOICE_STOPPING -> {
                if (event == OverlayEvent.VOICE_STOP_SUCCEEDED) {
                    state = State.VOICE_QUEUEING;
                    effects = List.of(Effect.SEND_VOICE);
                } else if (event == OverlayEvent.VOICE_STOP_FAILED) {
                    state = State.IDLE;
                }
            }
            case VOICE_QUEUEING -> {
                if (event == OverlayEvent.VOICE_QUEUED) {
                    state = State.VOICE_PENDING;
                } else if (event == OverlayEvent.VOICE_REJECTED) {
                    state = State.IDLE;
                } else if (event == OverlayEvent.TAP) {
                    attemptId++;
                    state = State.VOICE_STARTING;
                    effects = List.of(Effect.START_VOICE);
                }
            }
            case VOICE_PENDING -> {
                if (event == OverlayEvent.VOICE_REJECTED) {
                    state = State.IDLE;
                } else if (event == OverlayEvent.TAP) {
                    attemptId++;
                    state = State.VOICE_STARTING;
                    effects = List.of(Effect.START_VOICE);
                }
            }
            case VOICE_CANCELING -> {
                if (event == OverlayEvent.VOICE_CANCEL_SUCCEEDED
                        || event == OverlayEvent.VOICE_CANCEL_FAILED) {
                    state = State.IDLE;
                }
            }
            case TEXT_COMPOSING -> {
                if (event == OverlayEvent.SUBMIT_TEXT) {
                    state = State.TEXT_QUEUEING;
                    effects = List.of(Effect.SEND_TEXT);
                }
            }
            case TEXT_QUEUEING -> {
                if (event == OverlayEvent.TEXT_QUEUED) {
                    state = State.TEXT_PENDING;
                } else if (event == OverlayEvent.TEXT_REJECTED) {
                    state = State.TEXT_COMPOSING;
                }
            }
            case TEXT_PENDING -> {
                if (event == OverlayEvent.TEXT_DELIVERED) {
                    state = State.IDLE;
                } else if (event == OverlayEvent.TEXT_REJECTED) {
                    state = State.TEXT_COMPOSING;
                }
            }
            case TEARING_DOWN -> {
                // Handled before the state switch.
            }
        }
        return new Transition(previousState, state, effects);
    }
}

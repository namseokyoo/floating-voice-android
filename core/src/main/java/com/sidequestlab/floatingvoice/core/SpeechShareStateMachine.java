package com.sidequestlab.floatingvoice.core;

import java.util.List;
import java.util.Objects;

/** Pure reducer for an explicit speech-to-review-to-share flow. */
public final class SpeechShareStateMachine {
    public enum State {
        IDLE,
        STT_CHECKING_SUPPORT,
        STT_LISTENING,
        STT_PROCESSING,
        STT_REVIEW,
        SHARE_CHOOSER_LAUNCHED,
        STT_FAILED,
        STT_CANCELED,
        TEARING_DOWN
    }

    public enum EffectType {
        CHECK_SUPPORT,
        START_LISTENING,
        DRAFT_PREVIEW,
        SHOW_REVIEW,
        OFFER_RETRY,
        LAUNCH_SHARE_CHOOSER
    }

    public record Effect(EffectType type, long generation, String text) {
        public Effect {
            Objects.requireNonNull(type, "type");
        }

        public static Effect checkSupport(long generation) {
            return new Effect(EffectType.CHECK_SUPPORT, generation, null);
        }

        public static Effect startListening(long generation) {
            return new Effect(EffectType.START_LISTENING, generation, null);
        }

        public static Effect draftPreview(String text) {
            return new Effect(EffectType.DRAFT_PREVIEW, 0L, text);
        }

        public static Effect review(String text) {
            return new Effect(EffectType.SHOW_REVIEW, 0L, text);
        }

        public static Effect retry() {
            return new Effect(EffectType.OFFER_RETRY, 0L, null);
        }

        public static Effect shareChooser(String text) {
            return new Effect(EffectType.LAUNCH_SHARE_CHOOSER, 0L, text);
        }
    }

    public record Transition(State previousState, State nextState, List<Effect> effects) {
        public Transition {
            effects = List.copyOf(effects);
        }
    }

    private State state = State.IDLE;
    private long generation;
    private String reviewedText;

    public synchronized State state() {
        return state;
    }

    public synchronized long generation() {
        return generation;
    }

    public synchronized Transition accept(SpeechShareEvent event) {
        Objects.requireNonNull(event, "event");
        State previous = state;
        if (state == State.TEARING_DOWN) return inert(previous);
        if (event.type() == SpeechShareEvent.Type.TEARDOWN) {
            reviewedText = null;
            state = State.TEARING_DOWN;
            return inert(previous);
        }
        if (event.isGenerationScoped()
                && (event.generation() <= 0L || event.generation() != generation)) {
            return inert(previous);
        }

        List<Effect> effects = List.of();
        switch (state) {
            case IDLE -> {
                if (event.type() == SpeechShareEvent.Type.START) {
                    effects = beginAttempt();
                }
            }
            case STT_CHECKING_SUPPORT -> {
                if (event.type() == SpeechShareEvent.Type.SUPPORT_AVAILABLE) {
                    state = State.STT_LISTENING;
                    effects = List.of(Effect.startListening(generation));
                } else if (event.type() == SpeechShareEvent.Type.SUPPORT_UNAVAILABLE
                        || event.type() == SpeechShareEvent.Type.ERROR) {
                    state = State.STT_FAILED;
                    effects = List.of(Effect.retry());
                } else if (event.type() == SpeechShareEvent.Type.CANCEL) {
                    state = State.STT_CANCELED;
                }
            }
            case STT_LISTENING, STT_PROCESSING -> {
                if (event.type() == SpeechShareEvent.Type.PARTIAL_RESULT) {
                    String draft = normalize(event.text());
                    if (draft != null) effects = List.of(Effect.draftPreview(draft));
                } else if (event.type() == SpeechShareEvent.Type.PROCESSING) {
                    state = State.STT_PROCESSING;
                } else if (event.type() == SpeechShareEvent.Type.FINAL_RESULT) {
                    String result = normalize(event.text());
                    if (result == null) {
                        state = State.STT_FAILED;
                        effects = List.of(Effect.retry());
                    } else {
                        reviewedText = result;
                        state = State.STT_REVIEW;
                        effects = List.of(Effect.review(result));
                    }
                } else if (event.type() == SpeechShareEvent.Type.ERROR) {
                    state = State.STT_FAILED;
                    effects = List.of(Effect.retry());
                } else if (event.type() == SpeechShareEvent.Type.CANCEL) {
                    state = State.STT_CANCELED;
                }
            }
            case STT_REVIEW -> {
                if (event.type() == SpeechShareEvent.Type.SHARE && reviewedText != null) {
                    state = State.SHARE_CHOOSER_LAUNCHED;
                    effects = List.of(Effect.shareChooser(reviewedText));
                } else if (event.type() == SpeechShareEvent.Type.COMPLETE) {
                    completeInteraction();
                }
            }
            case STT_FAILED, STT_CANCELED -> {
                if (event.type() == SpeechShareEvent.Type.RETRY) {
                    effects = beginAttempt();
                } else if (event.type() == SpeechShareEvent.Type.COMPLETE) {
                    completeInteraction();
                }
            }
            case SHARE_CHOOSER_LAUNCHED -> {
                if (event.type() == SpeechShareEvent.Type.COMPLETE) completeInteraction();
            }
            case TEARING_DOWN -> {
                // Process-lifetime terminal state.
            }
        }
        return new Transition(previous, state, effects);
    }

    private List<Effect> beginAttempt() {
        generation++;
        reviewedText = null;
        state = State.STT_CHECKING_SUPPORT;
        return List.of(Effect.checkSupport(generation));
    }

    private void completeInteraction() {
        reviewedText = null;
        state = State.IDLE;
    }

    private Transition inert(State previous) {
        return new Transition(previous, state, List.of());
    }

    private static String normalize(String text) {
        if (text == null) return null;
        String normalized = text.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}

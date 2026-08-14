package com.sidequestlab.floatingvoice.core;

import java.util.Objects;

/** An input event for one speech/review/share attempt. */
public record SpeechShareEvent(Type type, long generation, String text) {
    public enum Type {
        START,
        SUPPORT_AVAILABLE,
        SUPPORT_UNAVAILABLE,
        PARTIAL_RESULT,
        PROCESSING,
        LISTENING_CYCLE_STARTED,
        FINAL_RESULT,
        ERROR,
        CANCEL,
        RETRY,
        KEYBOARD_FALLBACK,
        SHARE,
        SHARE_LAUNCH_FAILED,
        CHOOSER_RETURNED,
        COMPLETE,
        TEARDOWN
    }

    public SpeechShareEvent {
        Objects.requireNonNull(type, "type");
    }

    public static SpeechShareEvent start() {
        return new SpeechShareEvent(Type.START, 0L, null);
    }

    public static SpeechShareEvent supportAvailable(long generation) {
        return new SpeechShareEvent(Type.SUPPORT_AVAILABLE, generation, null);
    }

    public static SpeechShareEvent supportUnavailable(long generation) {
        return new SpeechShareEvent(Type.SUPPORT_UNAVAILABLE, generation, null);
    }

    public static SpeechShareEvent partialResult(long generation, String text) {
        return new SpeechShareEvent(Type.PARTIAL_RESULT, generation, text);
    }

    public static SpeechShareEvent processing(long generation) {
        return new SpeechShareEvent(Type.PROCESSING, generation, null);
    }

    public static SpeechShareEvent listeningCycleStarted(long generation) {
        return new SpeechShareEvent(Type.LISTENING_CYCLE_STARTED, generation, null);
    }

    public static SpeechShareEvent finalResult(long generation, String text) {
        return new SpeechShareEvent(Type.FINAL_RESULT, generation, text);
    }

    public static SpeechShareEvent error(long generation) {
        return new SpeechShareEvent(Type.ERROR, generation, null);
    }

    public static SpeechShareEvent cancel(long generation) {
        return new SpeechShareEvent(Type.CANCEL, generation, null);
    }

    public static SpeechShareEvent retryRequest() {
        return new SpeechShareEvent(Type.RETRY, 0L, null);
    }

    public static SpeechShareEvent keyboardFallback(long generation) {
        return new SpeechShareEvent(Type.KEYBOARD_FALLBACK, generation, null);
    }

    public static SpeechShareEvent share(long generation, String editedText) {
        return new SpeechShareEvent(Type.SHARE, generation, editedText);
    }

    public static SpeechShareEvent shareLaunchFailed(long generation) {
        return new SpeechShareEvent(Type.SHARE_LAUNCH_FAILED, generation, null);
    }

    public static SpeechShareEvent chooserReturned(long generation) {
        return new SpeechShareEvent(Type.CHOOSER_RETURNED, generation, null);
    }

    public static SpeechShareEvent complete(long generation) {
        return new SpeechShareEvent(Type.COMPLETE, generation, null);
    }

    public static SpeechShareEvent teardown() {
        return new SpeechShareEvent(Type.TEARDOWN, 0L, null);
    }

    public boolean isGenerationScoped() {
        return switch (type) {
            case SUPPORT_AVAILABLE, SUPPORT_UNAVAILABLE, PARTIAL_RESULT, PROCESSING,
                    LISTENING_CYCLE_STARTED,
                    FINAL_RESULT, ERROR, CANCEL, KEYBOARD_FALLBACK, SHARE,
                    SHARE_LAUNCH_FAILED, CHOOSER_RETURNED, COMPLETE -> true;
            default -> false;
        };
    }
}

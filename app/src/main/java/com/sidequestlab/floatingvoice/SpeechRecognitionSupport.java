package com.sidequestlab.floatingvoice;

import java.util.Objects;

/** Immutable, UI-safe description of system speech-recognition support. */
public record SpeechRecognitionSupport(
        Availability availability,
        Route route,
        ModelState modelState,
        FallbackReason fallbackReason,
        boolean offlinePreferenceRequested) {

    public enum Availability {
        AVAILABLE,
        UNAVAILABLE,
        KEYBOARD_REQUIRED
    }

    public enum Route {
        NONE,
        STANDARD,
        ON_DEVICE
    }

    public enum ModelState {
        NOT_APPLICABLE,
        CHECKING,
        READY,
        DOWNLOAD_REQUIRED,
        UNSUPPORTED,
        ERROR
    }

    public enum FallbackReason {
        NONE,
        ON_DEVICE_UNAVAILABLE,
        ON_DEVICE_CREATION_FAILED,
        ON_DEVICE_MODEL_DOWNLOAD_REQUIRED,
        ON_DEVICE_UNSUPPORTED,
        ON_DEVICE_SUPPORT_ERROR,
        ON_DEVICE_LANGUAGE_ERROR,
        STANDARD_CREATION_FAILED
    }

    public SpeechRecognitionSupport {
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(modelState, "modelState");
        Objects.requireNonNull(fallbackReason, "fallbackReason");
    }

    public static SpeechRecognitionSupport available(
            Route route, ModelState modelState, FallbackReason fallbackReason) {
        return new SpeechRecognitionSupport(
                Availability.AVAILABLE, route, modelState, fallbackReason, true);
    }

    public static SpeechRecognitionSupport unavailable(ModelState modelState) {
        return new SpeechRecognitionSupport(
                Availability.UNAVAILABLE, Route.NONE, modelState, FallbackReason.NONE, true);
    }

    public static SpeechRecognitionSupport keyboardRequired(FallbackReason reason) {
        return new SpeechRecognitionSupport(
                Availability.KEYBOARD_REQUIRED, Route.NONE, ModelState.UNSUPPORTED,
                reason, true);
    }
}

package com.sidequestlab.floatingvoice.core;

import java.util.List;
import java.util.Objects;

/** Existing output routes available to each one-operation input choice. */
public final class InputOutputPolicy {
    private static final List<OutputRoute> AUDIO_ROUTES = List.of(
            OutputRoute.TELEGRAM_VOICE,
            OutputRoute.SYSTEM_AUDIO_SHARE,
            OutputRoute.LOCAL_AUDIO_ARCHIVE);
    private static final List<OutputRoute> TEXT_ROUTES = List.of(
            OutputRoute.TELEGRAM_TEXT,
            OutputRoute.SYSTEM_TEXT_SHARE);

    private InputOutputPolicy() { }

    public static OutputRoute defaultRoute(InputMode inputMode) {
        return switch (Objects.requireNonNull(inputMode, "inputMode")) {
            case RAW_VOICE -> OutputRoute.TELEGRAM_VOICE;
            case TYPED_TEXT, SPEECH_TO_TEXT -> OutputRoute.TELEGRAM_TEXT;
        };
    }

    public static List<OutputRoute> oneOperationRoutes(InputMode inputMode) {
        return switch (Objects.requireNonNull(inputMode, "inputMode")) {
            case RAW_VOICE -> AUDIO_ROUTES;
            case TYPED_TEXT, SPEECH_TO_TEXT -> TEXT_ROUTES;
        };
    }

    public static boolean allows(InputMode inputMode, OutputRoute outputRoute) {
        return outputRoute != null && oneOperationRoutes(inputMode).contains(outputRoute);
    }
}

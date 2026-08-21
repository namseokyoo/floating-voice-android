package com.sidequestlab.floatingvoice;

import com.sidequestlab.floatingvoice.core.OutputRoute;

/** Pure policy seam keeping Telegram lifecycle events isolated from local recordings. */
final class RecordingRoutePolicy {
    private RecordingRoutePolicy() { }

    static boolean stopForTelegramRouteLoss(OutputRoute activeRoute, boolean recording) {
        return recording && activeRoute == OutputRoute.TELEGRAM_VOICE;
    }

    static boolean retainPrivateSourceOnTeardown(
            OutputRoute activeRoute, boolean interruptedSource, boolean readySource) {
        return (activeRoute == OutputRoute.LOCAL_AUDIO_ARCHIVE
                || activeRoute == OutputRoute.SYSTEM_AUDIO_SHARE)
                && (interruptedSource || readySource);
    }

    static boolean releaseShareStoreOwnership(OutputRoute activeRoute,
                                              boolean recorderReleased) {
        return recorderReleased && activeRoute == OutputRoute.SYSTEM_AUDIO_SHARE;
    }
}
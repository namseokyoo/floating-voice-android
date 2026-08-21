package com.sidequestlab.floatingvoice;

/** Separates global overlay availability from Telegram-only outputs. */
final class OverlayCapabilityPolicy {
    record Snapshot(boolean serviceAvailable,
                    boolean telegramOutputAvailable,
                    boolean systemTextShareAvailable,
                    boolean systemAudioShareAvailable) { }

    private OverlayCapabilityPolicy() { }

    static boolean independentStartActionVisible(boolean running,
                                                 boolean telegramOutputConfigured) {
        return !running && !telegramOutputConfigured;
    }

    static Snapshot evaluate(boolean microphonePermission,
                             boolean notificationPermission,
                             boolean overlayPermission,
                             boolean telegramAuthenticated,
                             boolean telegramRouteReady,
                             boolean accountStillResolving) {
        boolean serviceAvailable = microphonePermission
                && notificationPermission && overlayPermission;
        boolean telegramOutputAvailable = serviceAvailable
                && telegramAuthenticated
                && telegramRouteReady;
        return new Snapshot(serviceAvailable, telegramOutputAvailable,
                serviceAvailable, serviceAvailable);
    }
}

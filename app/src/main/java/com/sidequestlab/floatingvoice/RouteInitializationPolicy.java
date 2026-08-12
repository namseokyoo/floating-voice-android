package com.sidequestlab.floatingvoice;

/** Fail-closed gate for rebuilding account-bound route state. */
final class RouteInitializationPolicy {
    private RouteInitializationPolicy() { }

    static boolean allowed(boolean authReady, long accountUserId) {
        return authReady && accountUserId > 0L;
    }
}
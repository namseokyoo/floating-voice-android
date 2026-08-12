package com.sidequestlab.floatingvoice;

/** Rejects callbacks emitted by a TDLib client generation that is no longer current. */
final class ClientGenerationGate {
    private ClientGenerationGate() { }

    static boolean current(long callbackGeneration, long activeGeneration) {
        return callbackGeneration > 0L && callbackGeneration == activeGeneration;
    }
}
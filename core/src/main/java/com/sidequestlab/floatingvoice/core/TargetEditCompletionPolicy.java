package com.sidequestlab.floatingvoice.core;

public final class TargetEditCompletionPolicy {
    private TargetEditCompletionPolicy() {}

    public static boolean shouldComplete(boolean editingTarget,
                                         String pendingUsername,
                                         String deliveredUsername,
                                         boolean operationCommitted) {
        return editingTarget
                && operationCommitted
                && pendingUsername != null
                && deliveredUsername != null
                && deliveredUsername.equals(pendingUsername);
    }
}

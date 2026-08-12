package com.sidequestlab.floatingvoice.core;

import java.util.Objects;

/** Immutable durable voice dispatch. All transitions retain the frozen destination and file path. */
public record PendingDispatch(
        String dispatchId,
        DispatchTargetSnapshot target,
        String absolutePath,
        int durationSeconds,
        long createdAtEpochMillis,
        DispatchState state,
        long temporaryMessageId,
        int errorCode,
        String errorMessage,
        boolean serverRetryable,
        int retryAfterSeconds) {

    public PendingDispatch {
        if (dispatchId == null || dispatchId.isBlank()) {
            throw new IllegalArgumentException("dispatchId must not be blank");
        }
        target = Objects.requireNonNull(target, "target");
        if (absolutePath == null || absolutePath.isBlank()) {
            throw new IllegalArgumentException("absolutePath must not be blank");
        }
        if (durationSeconds <= 0) {
            throw new IllegalArgumentException("durationSeconds must be positive");
        }
        if (createdAtEpochMillis <= 0) {
            throw new IllegalArgumentException("createdAtEpochMillis must be positive");
        }
        state = Objects.requireNonNull(state, "state");
        errorMessage = Objects.requireNonNull(errorMessage, "errorMessage");
        if (retryAfterSeconds < 0) {
            throw new IllegalArgumentException("retryAfterSeconds must not be negative");
        }
    }

    public static PendingDispatch prepare(String dispatchId, DispatchTargetSnapshot target,
                                          String absolutePath, int durationSeconds,
                                          long createdAtEpochMillis) {
        return new PendingDispatch(dispatchId, target, absolutePath, durationSeconds,
                createdAtEpochMillis, DispatchState.PREPARED, 0L, 0, "", false, 0);
    }

    public PendingDispatch queued(long temporaryMessageId) {
        if (state != DispatchState.PREPARED) return this;
        if (temporaryMessageId == 0L) {
            throw new IllegalArgumentException("temporaryMessageId must not be zero");
        }
        return copy(DispatchState.QUEUED, temporaryMessageId, 0, "", false, 0);
    }

    public PendingDispatch failedRetained(int errorCode, String errorMessage,
                                          boolean canRetry, int retryAfterSeconds) {
        if (terminal()) return this;
        return copy(DispatchState.FAILED_RETAINED, temporaryMessageId, errorCode,
                Objects.requireNonNull(errorMessage), canRetry, retryAfterSeconds);
    }

    public PendingDispatch recoveredAfterRestart() {
        if (state != DispatchState.PREPARED && state != DispatchState.QUEUED) return this;
        return copy(DispatchState.UNKNOWN_RETAINED, temporaryMessageId, errorCode,
                errorMessage, serverRetryable, retryAfterSeconds);
    }

    public PendingDispatch completed(boolean fileDeleted) {
        if (state == DispatchState.COMPLETED || state == DispatchState.COMPLETED_FILE_RETAINED) {
            return this;
        }
        return copy(fileDeleted ? DispatchState.COMPLETED
                        : DispatchState.COMPLETED_FILE_RETAINED,
                temporaryMessageId, errorCode, errorMessage, false, 0);
    }

    public PendingDispatch fileDeletedAfterCompletion() {
        if (state != DispatchState.COMPLETED_FILE_RETAINED) return this;
        return copy(DispatchState.COMPLETED, temporaryMessageId,
                errorCode, errorMessage, false, 0);
    }

    /** Live spike is intentionally required before any automatic retry can be enabled. */
    public boolean automaticRetryAllowed() {
        return false;
    }

    private boolean terminal() {
        return state == DispatchState.COMPLETED
                || state == DispatchState.COMPLETED_FILE_RETAINED;
    }

    private PendingDispatch copy(DispatchState nextState, long nextTemporaryMessageId,
                                 int nextErrorCode, String nextErrorMessage,
                                 boolean nextServerRetryable, int nextRetryAfterSeconds) {
        return new PendingDispatch(dispatchId, target, absolutePath, durationSeconds,
                createdAtEpochMillis, nextState, nextTemporaryMessageId, nextErrorCode,
                nextErrorMessage, nextServerRetryable, nextRetryAfterSeconds);
    }
}

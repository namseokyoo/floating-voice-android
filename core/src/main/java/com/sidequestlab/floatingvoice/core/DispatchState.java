package com.sidequestlab.floatingvoice.core;

/** Durable application-owned delivery state. UNKNOWN is never retried automatically. */
public enum DispatchState {
    PREPARED,
    QUEUED,
    FAILED_RETAINED,
    UNKNOWN_RETAINED,
    COMPLETED,
    COMPLETED_FILE_RETAINED
}

package com.sidequestlab.floatingvoice;

import java.util.Objects;

/** Pure lifecycle decision for the excluded-from-recents speech review Activity. */
public final class SpeechReviewLifecyclePolicy {
    private SpeechReviewLifecyclePolicy() { }

    public static boolean shouldFinishOnStop(
            boolean changingConfigurations,
            boolean localChooserAwaitingReturn,
            SpeechReviewSession.Stage retainedStage) {
        Objects.requireNonNull(retainedStage, "retainedStage");
        return !changingConfigurations
                && !localChooserAwaitingReturn
                && retainedStage != SpeechReviewSession.Stage.SHARE_CONFIRMATION_REQUIRED;
    }
}

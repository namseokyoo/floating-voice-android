package com.sidequestlab.floatingvoice;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpeechReviewLifecyclePolicyTest {
    @Test
    public void retainedChooserStagePreventsFinishAfterRotation() {
        assertFalse(SpeechReviewLifecyclePolicy.shouldFinishOnStop(
                false,
                false,
                SpeechReviewSession.Stage.SHARE_CONFIRMATION_REQUIRED));
    }

    @Test
    public void ordinaryBackgroundingFinishesExcludedReviewActivity() {
        assertTrue(SpeechReviewLifecyclePolicy.shouldFinishOnStop(
                false,
                false,
                SpeechReviewSession.Stage.EDITING));
    }

    @Test
    public void backgroundingCancelsEveryActiveRecognitionStageOnly() {
        assertTrue(SpeechReviewLifecyclePolicy.shouldCancelRecognitionOnStop(
                SpeechReviewSession.Stage.CHECKING));
        assertTrue(SpeechReviewLifecyclePolicy.shouldCancelRecognitionOnStop(
                SpeechReviewSession.Stage.LISTENING));
        assertTrue(SpeechReviewLifecyclePolicy.shouldCancelRecognitionOnStop(
                SpeechReviewSession.Stage.PROCESSING));
        assertFalse(SpeechReviewLifecyclePolicy.shouldCancelRecognitionOnStop(
                SpeechReviewSession.Stage.EDITING));
        assertFalse(SpeechReviewLifecyclePolicy.shouldCancelRecognitionOnStop(
                SpeechReviewSession.Stage.SHARE_CONFIRMATION_REQUIRED));
    }

    @Test
    public void configurationChangeNeverFinishesReviewActivity() {
        assertFalse(SpeechReviewLifecyclePolicy.shouldFinishOnStop(
                true,
                false,
                SpeechReviewSession.Stage.EDITING));
    }
}

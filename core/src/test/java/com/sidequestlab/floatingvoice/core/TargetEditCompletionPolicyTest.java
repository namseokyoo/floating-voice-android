package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class TargetEditCompletionPolicyTest {
    @Test public void currentTargetReplayCannotCompleteUncommittedEdit() {
        assertFalse(TargetEditCompletionPolicy.shouldComplete(
                true, "existing_bot", "existing_bot", false));
    }

    @Test public void onboardingReplayCannotCompleteDestinationEdit() {
        assertFalse(TargetEditCompletionPolicy.shouldComplete(
                false, "candidate_bot", "candidate_bot", true));
    }

    @Test public void committedMatchingOperationCompletesEdit() {
        assertTrue(TargetEditCompletionPolicy.shouldComplete(
                true, "candidate_bot", "candidate_bot", true));
    }

    @Test public void differentCommittedTargetCannotConsumeOperation() {
        assertFalse(TargetEditCompletionPolicy.shouldComplete(
                true, "candidate_bot", "other_bot", true));
    }
}

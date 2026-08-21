package com.sidequestlab.floatingvoice;

import com.sidequestlab.floatingvoice.core.OutputRoute;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RecordingRoutePolicyTest {
    @Test public void telegramLossStopsOnlyActiveTelegramVoice() {
        assertTrue(RecordingRoutePolicy.stopForTelegramRouteLoss(
                OutputRoute.TELEGRAM_VOICE, true));
        assertFalse(RecordingRoutePolicy.stopForTelegramRouteLoss(
                OutputRoute.LOCAL_AUDIO_ARCHIVE, true));
        assertFalse(RecordingRoutePolicy.stopForTelegramRouteLoss(
                OutputRoute.SYSTEM_AUDIO_SHARE, true));
        assertFalse(RecordingRoutePolicy.stopForTelegramRouteLoss(
                OutputRoute.TELEGRAM_VOICE, false));
    }

    @Test public void teardownRetainsInterruptedAndReadyLocalSources() {
        assertTrue(RecordingRoutePolicy.retainPrivateSourceOnTeardown(
                OutputRoute.LOCAL_AUDIO_ARCHIVE, true, false));
        assertTrue(RecordingRoutePolicy.retainPrivateSourceOnTeardown(
                OutputRoute.LOCAL_AUDIO_ARCHIVE, false, true));
        assertTrue(RecordingRoutePolicy.retainPrivateSourceOnTeardown(
                OutputRoute.SYSTEM_AUDIO_SHARE, true, false));
        assertFalse(RecordingRoutePolicy.retainPrivateSourceOnTeardown(
                OutputRoute.LOCAL_AUDIO_ARCHIVE, false, false));
    }

    @Test public void shareStoreOwnershipReleasesOnlyAfterRecorderRelease() {
        assertFalse(RecordingRoutePolicy.releaseShareStoreOwnership(
                OutputRoute.SYSTEM_AUDIO_SHARE, false));
        assertTrue(RecordingRoutePolicy.releaseShareStoreOwnership(
                OutputRoute.SYSTEM_AUDIO_SHARE, true));
        assertFalse(RecordingRoutePolicy.releaseShareStoreOwnership(
                OutputRoute.LOCAL_AUDIO_ARCHIVE, true));
    }
}
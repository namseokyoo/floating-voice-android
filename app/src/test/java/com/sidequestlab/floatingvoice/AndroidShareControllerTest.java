package com.sidequestlab.floatingvoice;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AndroidShareControllerTest {
    @Test
    public void blankTextIsRejectedBeforePlatformAccess() {
        FakePlatform platform = new FakePlatform();
        AndroidShareController controller = new AndroidShareController(platform, "Share text");

        assertEquals(AndroidShareController.Result.BLANK_REJECTED, controller.shareText(" \n\t"));
        assertEquals(0, platform.launches);
    }

    @Test
    public void explicitTapPreservesLongUnicodeKoreanPlainTextPayload() {
        FakePlatform platform = new FakePlatform();
        AndroidShareController controller = new AndroidShareController(platform, "Share text");
        String payload = "회의 메모 " + '\0'
                + " 제거 안 함? 👨‍👩‍👧‍👦 café 漢字\n" + "가나다라마바사".repeat(2_000);

        assertEquals(AndroidShareController.Result.CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED,
                controller.shareText(payload));

        assertEquals(1, platform.launches);
        AndroidShareController.TextShareRequest request = platform.requests.get(0);
        assertEquals("android.intent.action.SEND", request.action());
        assertEquals("text/plain", request.mimeType());
        assertEquals(payload, request.text());
        assertEquals("Share text", request.chooserTitle());
    }

    @Test
    public void duplicateRapidShareTapLaunchesOnlyOneChooser() {
        FakePlatform platform = new FakePlatform();
        AndroidShareController controller = new AndroidShareController(platform, "Share text");

        assertEquals(AndroidShareController.Result.CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED,
                controller.shareText("edited"));
        assertEquals(AndroidShareController.Result.DUPLICATE_IGNORED,
                controller.shareText("edited"));
        assertEquals(1, platform.launches);
    }

    @Test
    public void noHandlerIsExplicitFailureAndAllowsSafeRetry() {
        FakePlatform platform = new FakePlatform();
        platform.next = AndroidShareController.PlatformResult.NO_HANDLER;
        AndroidShareController controller = new AndroidShareController(platform, "Share text");

        assertEquals(AndroidShareController.Result.NO_HANDLER, controller.shareText("draft"));
        platform.next = AndroidShareController.PlatformResult.OPENED;
        assertEquals(AndroidShareController.Result.CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED,
                controller.shareText("draft"));
        assertEquals(2, platform.launches);
    }

    @Test
    public void launchExceptionIsExplicitFailureAndAllowsSafeRetry() {
        FakePlatform platform = new FakePlatform();
        platform.throwNext = true;
        AndroidShareController controller = new AndroidShareController(platform, "Share text");

        assertEquals(AndroidShareController.Result.LAUNCH_FAILED, controller.shareText("draft"));
        assertEquals(AndroidShareController.Result.CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED,
                controller.shareText("draft"));
        assertEquals(2, platform.launches);
        assertFalse(controller.resultMeansSentOrSharedSuccess(
                AndroidShareController.Result.CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED));
    }

    @Test
    public void audioShareUsesOnlyScopedContentUriWithReadGrant() {
        FakePlatform platform = new FakePlatform();
        AndroidShareController controller = new AndroidShareController(platform, "Share audio");

        assertEquals(AndroidShareController.Result.CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED,
                controller.shareAudio("content://com.sidequestlab.floatingvoice.fileprovider/voice.ogg"));

        assertEquals(1, platform.audioRequests.size());
        AndroidShareController.AudioShareRequest request = platform.audioRequests.get(0);
        assertEquals("android.intent.action.SEND", request.action());
        assertEquals("audio/ogg", request.mimeType());
        assertTrue(request.contentUri().startsWith("content://"));
        assertTrue(request.grantReadPermission());
        assertEquals("Share audio", request.chooserTitle());
    }

    @Test
    public void rawFileUriIsRejectedBeforePlatformAccess() {
        FakePlatform platform = new FakePlatform();
        AndroidShareController controller = new AndroidShareController(platform, "Share audio");

        assertEquals(AndroidShareController.Result.UNSAFE_URI_REJECTED,
                controller.shareAudio("file:///private/voice.ogg"));
        assertEquals(0, platform.launches);
    }

    @Test
    public void audioNoHandlerIsExplicitFailureAndAllowsRetryWithoutChangingUri() {
        FakePlatform platform = new FakePlatform();
        platform.next = AndroidShareController.PlatformResult.NO_HANDLER;
        AndroidShareController controller = new AndroidShareController(platform, "Share audio");
        String uri = "content://com.sidequestlab.floatingvoice.fileprovider/voice.ogg";

        assertEquals(AndroidShareController.Result.NO_HANDLER, controller.shareAudio(uri));
        platform.next = AndroidShareController.PlatformResult.OPENED;
        assertEquals(AndroidShareController.Result.CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED,
                controller.shareAudio(uri));
        assertEquals(2, platform.audioRequests.size());
        assertFalse(controller.resultMeansSentOrSharedSuccess(
                AndroidShareController.Result.CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED));
    }

    private static final class FakePlatform implements AndroidShareController.Platform {
        int launches;
        boolean throwNext;
        AndroidShareController.PlatformResult next = AndroidShareController.PlatformResult.OPENED;
        final List<AndroidShareController.TextShareRequest> requests = new ArrayList<>();
        final List<AndroidShareController.AudioShareRequest> audioRequests = new ArrayList<>();

        @Override public AndroidShareController.PlatformResult openTextChooser(
                AndroidShareController.TextShareRequest request) {
            launches++;
            requests.add(request);
            if (throwNext) {
                throwNext = false;
                throw new IllegalStateException("launch failed");
            }
            return next;
        }

        @Override public AndroidShareController.PlatformResult openAudioChooser(
                AndroidShareController.AudioShareRequest request) {
            launches++;
            audioRequests.add(request);
            if (throwNext) {
                throwNext = false;
                throw new IllegalStateException("launch failed");
            }
            return next;
        }
    }
}

package com.sidequestlab.floatingvoice;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.Objects;

/** Opens Android's Sharesheet only after an explicit reviewed-text or recorded-audio request. */
public final class AndroidShareController {
    public enum Result {
        CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED,
        BLANK_REJECTED,
        DUPLICATE_IGNORED,
        UNSAFE_URI_REJECTED,
        NO_HANDLER,
        LAUNCH_FAILED
    }

    public enum PlatformResult { OPENED, NO_HANDLER }

    public record TextShareRequest(
            String action,
            String mimeType,
            String text,
            String chooserTitle) {
        public TextShareRequest {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(mimeType, "mimeType");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(chooserTitle, "chooserTitle");
        }
    }

    public record AudioShareRequest(
            String action,
            String mimeType,
            String contentUri,
            String chooserTitle,
            boolean grantReadPermission) {
        public AudioShareRequest {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(mimeType, "mimeType");
            Objects.requireNonNull(contentUri, "contentUri");
            Objects.requireNonNull(chooserTitle, "chooserTitle");
        }
    }

    /** Fakeable boundary around Android intent construction, resolution, and launch. */
    public interface Platform {
        PlatformResult openTextChooser(TextShareRequest request);
        default PlatformResult openAudioChooser(AudioShareRequest request) {
            return PlatformResult.NO_HANDLER;
        }
    }

    private final Platform platform;
    private final String chooserTitle;
    private boolean chooserOpen;

    public AndroidShareController(Platform platform, String chooserTitle) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.chooserTitle = Objects.requireNonNull(chooserTitle, "chooserTitle");
    }

    public static AndroidShareController create(Context activityContext, String chooserTitle) {
        return new AndroidShareController(new AndroidPlatform(activityContext), chooserTitle);
    }

    public synchronized Result shareText(String reviewedText) {
        if (reviewedText == null || reviewedText.isBlank()) return Result.BLANK_REJECTED;
        if (chooserOpen) return Result.DUPLICATE_IGNORED;
        TextShareRequest request = new TextShareRequest(
                Intent.ACTION_SEND, "text/plain", reviewedText, chooserTitle);
        return open(() -> platform.openTextChooser(request));
    }

    public synchronized Result shareAudio(String contentUri) {
        if (contentUri == null || !contentUri.startsWith("content://")) {
            return Result.UNSAFE_URI_REJECTED;
        }
        if (chooserOpen) return Result.DUPLICATE_IGNORED;
        AudioShareRequest request = new AudioShareRequest(
                Intent.ACTION_SEND, "audio/ogg", contentUri, chooserTitle, true);
        return open(() -> platform.openAudioChooser(request));
    }

    private Result open(ChooserLaunch launch) {
        try {
            PlatformResult platformResult = launch.open();
            if (platformResult == PlatformResult.NO_HANDLER) return Result.NO_HANDLER;
            chooserOpen = true;
            return Result.CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED;
        } catch (RuntimeException launchFailure) {
            return Result.LAUNCH_FAILED;
        }
    }

    @FunctionalInterface
    private interface ChooserLaunch { PlatformResult open(); }

    /** Called after Android returns focus from the chooser so the user may explicitly retry. */
    public synchronized void chooserReturned() { chooserOpen = false; }

    public boolean resultMeansSentOrSharedSuccess(Result result) { return false; }

    private static final class AndroidPlatform implements Platform {
        private final Context activityContext;

        AndroidPlatform(Context activityContext) {
            this.activityContext = Objects.requireNonNull(activityContext, "activityContext");
        }

        @Override public PlatformResult openTextChooser(TextShareRequest request) {
            Intent send = new Intent(request.action())
                    .setType(request.mimeType())
                    .putExtra(Intent.EXTRA_TEXT, request.text());
            return launch(Intent.createChooser(send, request.chooserTitle()));
        }

        @Override public PlatformResult openAudioChooser(AudioShareRequest request) {
            Uri stream = Uri.parse(request.contentUri());
            Intent send = new Intent(request.action())
                    .setType(request.mimeType())
                    .putExtra(Intent.EXTRA_STREAM, stream);
            send.setClipData(ClipData.newRawUri("Floating Voice audio", stream));
            if (request.grantReadPermission()) {
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            if (send.resolveActivity(activityContext.getPackageManager()) == null) {
                return PlatformResult.NO_HANDLER;
            }
            return launch(Intent.createChooser(send, request.chooserTitle()));
        }

        private PlatformResult launch(Intent chooser) {
            if (!(activityContext instanceof android.app.Activity)) {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            try {
                activityContext.startActivity(chooser);
                return PlatformResult.OPENED;
            } catch (ActivityNotFoundException noHandler) {
                return PlatformResult.NO_HANDLER;
            }
        }
    }
}

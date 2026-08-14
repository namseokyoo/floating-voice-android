package com.sidequestlab.floatingvoice;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;

import java.util.Objects;

/** Opens Android's text Sharesheet only after an explicit reviewed-text request. */
public final class AndroidShareController {
    public enum Result {
        CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED,
        BLANK_REJECTED,
        DUPLICATE_IGNORED,
        NO_HANDLER,
        LAUNCH_FAILED
    }

    public enum PlatformResult {
        OPENED,
        NO_HANDLER
    }

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

    /** Fakeable boundary around Android intent construction, resolution, and launch. */
    public interface Platform {
        PlatformResult openTextChooser(TextShareRequest request);
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
        try {
            PlatformResult platformResult = platform.openTextChooser(request);
            if (platformResult == PlatformResult.NO_HANDLER) return Result.NO_HANDLER;
            chooserOpen = true;
            return Result.CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED;
        } catch (RuntimeException launchFailure) {
            return Result.LAUNCH_FAILED;
        }
    }

    /** Called after Android returns focus from the chooser so the user may explicitly retry. */
    public synchronized void chooserReturned() {
        chooserOpen = false;
    }

    public boolean resultMeansSentOrSharedSuccess(Result result) {
        return false;
    }

    private static final class AndroidPlatform implements Platform {
        private final Context activityContext;

        AndroidPlatform(Context activityContext) {
            this.activityContext = Objects.requireNonNull(activityContext, "activityContext");
        }

        @Override public PlatformResult openTextChooser(TextShareRequest request) {
            Intent send = new Intent(request.action())
                    .setType(request.mimeType())
                    .putExtra(Intent.EXTRA_TEXT, request.text());
            Intent chooser = Intent.createChooser(send, request.chooserTitle());
            try {
                activityContext.startActivity(chooser);
                return PlatformResult.OPENED;
            } catch (ActivityNotFoundException noHandler) {
                return PlatformResult.NO_HANDLER;
            }
        }
    }
}

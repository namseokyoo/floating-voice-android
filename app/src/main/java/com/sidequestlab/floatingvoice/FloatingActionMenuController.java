package com.sidequestlab.floatingvoice;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Objects;

final class FloatingActionMenuController {
    interface Listener {
        void onComposeText();
        void onSpeechShare();
        default void onAudioShareRecording() { }
        void onLocalArchiveRecording();
        void onChooseDestination();
        void onDismissRequested();
    }

    private final Context serviceContext;
    private final OverlayWindowRegistry<View, WindowManager.LayoutParams> registry;
    private final Listener listener;
    private View palette;
    private boolean interactive;
    private String destinationSummary;
    private int removalRetries;
    private static final int MAX_REMOVAL_RETRIES = 3;

    FloatingActionMenuController(
            Context serviceContext,
            OverlayWindowRegistry<View, WindowManager.LayoutParams> registry,
            Listener listener) {
        this.serviceContext = Objects.requireNonNull(serviceContext);
        this.registry = Objects.requireNonNull(registry);
        this.listener = Objects.requireNonNull(listener);
    }

    @SuppressLint({"InflateParams", "ClickableViewAccessibility", "RtlHardcoded"})
    boolean showAt(int x, int y, int width, int height) {
        if (palette != null) {
            if (interactive) return true;
            if (!registry.remove(palette)) return false;
            palette = null;
        }
        Context localizedContext = LocalizedStrings.context(serviceContext);
        Context themed = new ContextThemeWrapper(
                localizedContext, R.style.Theme_FloatingVoice_OverlayMaterial3);
        View next = LayoutInflater.from(themed)
                .inflate(R.layout.overlay_action_palette, null, false);
        View textAction = next.findViewById(R.id.overlay_text_action);
        textAction.setOnClickListener(view -> listener.onComposeText());
        View speechShareAction = next.findViewById(R.id.overlay_speech_share_action);
        speechShareAction.setOnClickListener(view -> listener.onSpeechShare());
        View audioShareAction = next.findViewById(R.id.overlay_audio_share_action);
        audioShareAction.setOnClickListener(view -> listener.onAudioShareRecording());
        View localArchiveAction = next.findViewById(R.id.overlay_local_archive_action);
        localArchiveAction.setOnClickListener(view -> listener.onLocalArchiveRecording());
        View destinationAction = next.findViewById(R.id.overlay_destination_action);
        destinationAction.setOnClickListener(view -> listener.onChooseDestination());
        if (destinationSummary != null && !destinationSummary.isBlank()) {
            ((TextView) next.findViewById(R.id.overlay_destination_action_supporting))
                    .setText(destinationSummary);
        }
        applyFontScalePolicy(next);
        next.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                listener.onDismissRequested();
                return true;
            }
            return false;
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        params.x = x;
        params.y = y;
        try {
            registry.add(next, params);
            palette = next;
            interactive = true;
            next.setAlpha(0f);
            next.setScaleX(0.92f);
            next.setScaleY(0.92f);
            next.post(() -> next.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(180L).start());
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    boolean dismiss() {
        View current = palette;
        if (current == null) return true;
        current.animate().cancel();
        current.findViewById(R.id.overlay_text_action).setOnClickListener(null);
        current.findViewById(R.id.overlay_speech_share_action).setOnClickListener(null);
        current.findViewById(R.id.overlay_audio_share_action).setOnClickListener(null);
        current.findViewById(R.id.overlay_local_archive_action).setOnClickListener(null);
        current.findViewById(R.id.overlay_destination_action).setOnClickListener(null);
        current.setOnTouchListener(null);
        interactive = false;
        if (!registry.remove(current)) {
            scheduleRemovalRetry(current);
            return false;
        }
        palette = null;
        removalRetries = 0;
        return true;
    }

    boolean isShowing() { return palette != null; }

    void setDestinationSummary(String summary) {
        destinationSummary = summary;
        View current = palette;
        if (current != null) {
            ((TextView) current.findViewById(R.id.overlay_destination_action_supporting))
                    .setText(summary == null || summary.isBlank()
                            ? text(R.string.overlay_destination_action_supporting) : summary);
        }
    }

    void refreshStrings() {
        View current = palette;
        if (current == null) return;
        ((TextView) current.findViewById(R.id.overlay_text_action_title))
                .setText(text(R.string.overlay_text_action_title));
        ((TextView) current.findViewById(R.id.overlay_text_action_supporting))
                .setText(text(R.string.overlay_text_action_supporting));
        current.findViewById(R.id.overlay_text_action)
                .setContentDescription(text(R.string.content_description_open_text_composer));
        ((TextView) current.findViewById(R.id.overlay_speech_share_action_title))
                .setText(text(R.string.overlay_speech_share_action_title));
        ((TextView) current.findViewById(R.id.overlay_speech_share_action_supporting))
                .setText(text(R.string.overlay_speech_share_action_supporting));
        current.findViewById(R.id.overlay_speech_share_action)
                .setContentDescription(text(R.string.content_description_open_speech_share));
        ((TextView) current.findViewById(R.id.overlay_audio_share_action_title))
                .setText(text(R.string.overlay_audio_share_action_title));
        ((TextView) current.findViewById(R.id.overlay_audio_share_action_supporting))
                .setText(text(R.string.overlay_audio_share_action_supporting));
        current.findViewById(R.id.overlay_audio_share_action)
                .setContentDescription(text(R.string.content_description_audio_share_recording));
        ((TextView) current.findViewById(R.id.overlay_local_archive_action_title))
                .setText(text(R.string.overlay_local_archive_action_title));
        ((TextView) current.findViewById(R.id.overlay_local_archive_action_supporting))
                .setText(text(R.string.overlay_local_archive_action_supporting));
        current.findViewById(R.id.overlay_local_archive_action)
                .setContentDescription(text(R.string.content_description_local_archive_recording));
        ((TextView) current.findViewById(R.id.overlay_destination_action_title))
                .setText(text(R.string.overlay_destination_action_title));
        ((TextView) current.findViewById(R.id.overlay_destination_action_supporting))
                .setText(destinationSummary == null || destinationSummary.isBlank()
                        ? text(R.string.overlay_destination_action_supporting)
                        : destinationSummary);
        current.findViewById(R.id.overlay_destination_action)
                .setContentDescription(text(R.string.content_description_open_destination_picker));
        applyFontScalePolicy(current);
    }

    void destroy() {
        removalRetries = 0;
        dismiss();
    }

    private void scheduleRemovalRetry(View expected) {
        if (removalRetries >= MAX_REMOVAL_RETRIES) return;
        removalRetries++;
        expected.postDelayed(() -> {
            if (palette != expected || interactive) return;
            if (registry.remove(expected)) {
                palette = null;
                removalRetries = 0;
            } else {
                scheduleRemovalRetry(expected);
            }
        }, 100L);
    }

    private String text(int resourceId, Object... arguments) {
        return LocalizedStrings.get(serviceContext, resourceId, arguments);
    }

    private void applyFontScalePolicy(View root) {
        boolean largeFont = serviceContext.getResources()
                .getConfiguration().fontScale >= 1.5f;
        root.findViewById(R.id.overlay_text_action_supporting)
                .setVisibility(largeFont ? View.GONE : View.VISIBLE);
        root.findViewById(R.id.overlay_speech_share_action_supporting)
                .setVisibility(largeFont ? View.GONE : View.VISIBLE);
        root.findViewById(R.id.overlay_audio_share_action_supporting)
                .setVisibility(largeFont ? View.GONE : View.VISIBLE);
        root.findViewById(R.id.overlay_local_archive_action_supporting)
                .setVisibility(largeFont ? View.GONE : View.VISIBLE);
        root.findViewById(R.id.overlay_destination_action_supporting)
                .setVisibility(largeFont ? View.GONE : View.VISIBLE);
    }
}

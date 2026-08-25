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
        void onSendVoice();
        void onChooseVoiceOutput();
        void onComposeText();
        void onChooseTextOutput();
        void onSpeechText();
        void onChooseSpeechTextOutput();
        void onDismissRequested();
    }

    private final Context serviceContext;
    private final OverlayWindowRegistry<View, WindowManager.LayoutParams> registry;
    private final Listener listener;
    private View palette;
    private boolean interactive;
    private String defaultOutputSummary;
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
        next.findViewById(R.id.overlay_voice_action)
                .setOnClickListener(view -> listener.onSendVoice());
        next.findViewById(R.id.overlay_voice_alternative)
                .setOnClickListener(view -> listener.onChooseVoiceOutput());
        next.findViewById(R.id.overlay_text_action)
                .setOnClickListener(view -> listener.onComposeText());
        next.findViewById(R.id.overlay_text_alternative)
                .setOnClickListener(view -> listener.onChooseTextOutput());
        next.findViewById(R.id.overlay_speech_text_action)
                .setOnClickListener(view -> listener.onSpeechText());
        next.findViewById(R.id.overlay_speech_text_alternative)
                .setOnClickListener(view -> listener.onChooseSpeechTextOutput());
        bindDefaultOutputSummary(next);
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
        clearClick(current, R.id.overlay_voice_action);
        clearClick(current, R.id.overlay_voice_alternative);
        clearClick(current, R.id.overlay_text_action);
        clearClick(current, R.id.overlay_text_alternative);
        clearClick(current, R.id.overlay_speech_text_action);
        clearClick(current, R.id.overlay_speech_text_alternative);
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

    void setDefaultOutputSummary(String summary) {
        defaultOutputSummary = summary;
        if (palette != null) bindDefaultOutputSummary(palette);
    }

    void refreshStrings() {
        View current = palette;
        if (current == null) return;
        bindText(current, R.id.overlay_voice_action_title,
                R.string.overlay_voice_action_title);
        bindText(current, R.id.overlay_text_action_title,
                R.string.overlay_text_action_title);
        bindText(current, R.id.overlay_speech_text_action_title,
                R.string.overlay_speech_text_action_title);
        bindText(current, R.id.overlay_voice_alternative,
                R.string.overlay_alternative_output);
        bindText(current, R.id.overlay_text_alternative,
                R.string.overlay_alternative_output);
        bindText(current, R.id.overlay_speech_text_alternative,
                R.string.overlay_alternative_output);
        current.findViewById(R.id.overlay_voice_action)
                .setContentDescription(text(R.string.content_description_send_voice_default));
        current.findViewById(R.id.overlay_text_action)
                .setContentDescription(text(R.string.content_description_send_text_default));
        current.findViewById(R.id.overlay_speech_text_action)
                .setContentDescription(text(R.string.content_description_send_speech_text_default));
        bindDefaultOutputSummary(current);
        applyFontScalePolicy(current);
    }

    void destroy() {
        removalRetries = 0;
        dismiss();
    }

    private void bindDefaultOutputSummary(View root) {
        String summary = defaultOutputSummary == null || defaultOutputSummary.isBlank()
                ? text(R.string.overlay_default_output_unavailable) : defaultOutputSummary;
        ((TextView) root.findViewById(R.id.overlay_voice_action_supporting)).setText(summary);
        ((TextView) root.findViewById(R.id.overlay_text_action_supporting)).setText(summary);
        ((TextView) root.findViewById(R.id.overlay_speech_text_action_supporting)).setText(summary);
    }

    private static void clearClick(View root, int id) {
        root.findViewById(id).setOnClickListener(null);
    }

    private void bindText(View root, int viewId, int stringId) {
        ((TextView) root.findViewById(viewId)).setText(text(stringId));
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
        root.findViewById(R.id.overlay_voice_icon)
                .setVisibility(largeFont ? View.GONE : View.VISIBLE);
        root.findViewById(R.id.overlay_text_icon)
                .setVisibility(largeFont ? View.GONE : View.VISIBLE);
        root.findViewById(R.id.overlay_speech_text_icon)
                .setVisibility(largeFont ? View.GONE : View.VISIBLE);
    }
}

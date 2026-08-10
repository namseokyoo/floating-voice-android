package com.sidequestlab.floatingvoice;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.sidequestlab.floatingvoice.core.RecordingElapsedFormatter;

import java.util.Objects;

final class FloatingOverlayViewController {
    interface Listener {
        void onStopAndSend();
        void onCancel();
    }

    private static final long TIMER_REFRESH_MS = 250L;
    private static final long CONTAINER_TRANSITION_MS = 180L;

    private final Context serviceContext;
    private final View root;
    private final View idleAction;
    private final View recordingDock;
    private final View recordingDragRegion;
    private final View stopAndSend;
    private final View cancel;
    private final TextView recordingStatus;
    private final TextView recordingTimer;
    private long recordingStartedAt;
    private boolean timerRunning;
    private boolean visualInitialized;

    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            if (!timerRunning) return;
            updateTimer(android.os.SystemClock.elapsedRealtime());
            root.postDelayed(this, TIMER_REFRESH_MS);
        }
    };

    @SuppressLint("InflateParams")
    FloatingOverlayViewController(Context serviceContext, Listener listener) {
        this.serviceContext = Objects.requireNonNull(serviceContext);
        Context localizedContext = LocalizedStrings.context(serviceContext);
        Context themed = new ContextThemeWrapper(
                localizedContext, R.style.Theme_FloatingVoice_OverlayMaterial3);
        root = LayoutInflater.from(themed).inflate(R.layout.overlay_primary_control, null, false);
        idleAction = root.findViewById(R.id.overlay_idle_action);
        recordingDock = root.findViewById(R.id.overlay_recording_dock);
        recordingDragRegion = root.findViewById(R.id.overlay_drag_region);
        stopAndSend = root.findViewById(R.id.overlay_stop_send);
        cancel = root.findViewById(R.id.overlay_cancel);
        recordingStatus = root.findViewById(R.id.overlay_recording_status);
        recordingTimer = root.findViewById(R.id.overlay_recording_timer);

        idleAction.setClickable(false);
        idleAction.setFocusable(false);
        stopAndSend.setOnClickListener(view -> listener.onStopAndSend());
        cancel.setOnClickListener(view -> listener.onCancel());
        showIdle();
    }

    View root() { return root; }

    View recordingDragRegion() { return recordingDragRegion; }

    void setIdleClickListener(View.OnClickListener listener) {
        root.setOnClickListener(listener);
    }

    void setIdleTouchListener(View.OnTouchListener listener) {
        root.setOnTouchListener(listener);
    }

    void setRecordingDragTouchListener(View.OnTouchListener listener) {
        recordingDragRegion.setOnTouchListener(listener);
    }

    void showIdle() {
        stopTimer();
        recordingDock.animate().cancel();
        idleAction.animate().cancel();
        recordingDock.setVisibility(View.GONE);
        idleAction.setVisibility(View.VISIBLE);
        if (visualInitialized) {
            idleAction.setAlpha(0f);
            idleAction.setScaleX(0.92f);
            idleAction.setScaleY(0.92f);
            idleAction.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(CONTAINER_TRANSITION_MS).start();
        } else {
            idleAction.setAlpha(1f);
            idleAction.setScaleX(1f);
            idleAction.setScaleY(1f);
            visualInitialized = true;
        }
        root.setClickable(true);
        root.setFocusable(true);
        root.setContentDescription(text(R.string.content_description_start_recording));
    }

    void showRecording(long startedAt) {
        recordingStartedAt = startedAt;
        idleAction.animate().cancel();
        recordingDock.animate().cancel();
        idleAction.setVisibility(View.GONE);
        recordingDock.setVisibility(View.VISIBLE);
        recordingDock.setAlpha(0f);
        recordingDock.setScaleX(0.92f);
        recordingDock.setScaleY(0.92f);
        recordingDock.post(() -> recordingDock.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(CONTAINER_TRANSITION_MS).start());
        root.setClickable(false);
        root.setFocusable(false);
        root.setContentDescription(text(R.string.content_description_recording_dock));
        stopAndSend.setContentDescription(text(R.string.content_description_stop_and_send));
        cancel.setContentDescription(text(R.string.content_description_cancel_recording));
        recordingStatus.setText(text(R.string.overlay_recording_status));
        startTimer();
    }

    void setIdlePressed(boolean pressed) {
        if (idleAction.getVisibility() != View.VISIBLE) return;
        idleAction.animate().cancel();
        float scale = pressed ? 0.94f : 1f;
        idleAction.animate().scaleX(scale).scaleY(scale)
                .setDuration(pressed ? 80L : CONTAINER_TRANSITION_MS).start();
    }

    void refreshStrings() {
        if (recordingDock.getVisibility() == View.VISIBLE) {
            root.setContentDescription(text(R.string.content_description_recording_dock));
            stopAndSend.setContentDescription(text(R.string.content_description_stop_and_send));
            cancel.setContentDescription(text(R.string.content_description_cancel_recording));
            recordingStatus.setText(text(R.string.overlay_recording_status));
            updateTimer(android.os.SystemClock.elapsedRealtime());
        } else {
            root.setContentDescription(text(R.string.content_description_start_recording));
        }
    }

    void destroy() {
        stopTimer();
        root.setOnClickListener(null);
        root.setOnTouchListener(null);
        recordingDragRegion.setOnTouchListener(null);
        stopAndSend.setOnClickListener(null);
        cancel.setOnClickListener(null);
    }

    private void startTimer() {
        stopTimer();
        timerRunning = true;
        updateTimer(android.os.SystemClock.elapsedRealtime());
        root.postDelayed(timerTick, TIMER_REFRESH_MS);
    }

    private void stopTimer() {
        timerRunning = false;
        root.removeCallbacks(timerTick);
    }

    private void updateTimer(long now) {
        String elapsed = RecordingElapsedFormatter.format(
                Math.max(0L, now - recordingStartedAt));
        recordingTimer.setText(elapsed);
        recordingTimer.setContentDescription(
                text(R.string.content_description_recording_elapsed, elapsed));
    }

    private String text(int resourceId, Object... arguments) {
        return LocalizedStrings.get(serviceContext, resourceId, arguments);
    }
}

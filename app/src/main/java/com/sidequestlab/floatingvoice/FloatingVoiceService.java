package com.sidequestlab.floatingvoice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.core.content.ContextCompat;

import com.sidequestlab.floatingvoice.core.AnchoredPanelPlacement;
import com.sidequestlab.floatingvoice.core.GestureClassifier;
import com.sidequestlab.floatingvoice.core.OverlayEvent;
import com.sidequestlab.floatingvoice.core.OverlayStateMachine;

import java.io.File;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

public final class FloatingVoiceService extends Service implements TelegramRepository.Listener {
    public static final String ACTION_START = "com.sidequestlab.floatingvoice.START_OVERLAY";
    public static final String ACTION_STOP = "com.sidequestlab.floatingvoice.STOP_OVERLAY";
    public static final String ACTION_COMPOSER_CLOSED =
            "com.sidequestlab.floatingvoice.COMPOSER_CLOSED";
    private static final int NOTIFICATION_ID = 41;
    private static final String CHANNEL_ID = "floatingvoice_overlay";
    private static final int TOUCH_MOVEMENT_THRESHOLD_DP = 12;
    private static final long TOUCH_LONG_PRESS_THRESHOLD_MS = 600L;

    private final OverlayStateMachine overlayStateMachine = new OverlayStateMachine();
    private final RecordingCancelOperation cancelOperation = new RecordingCancelOperation();
    private final TerminalInputGate terminalInputGate = new TerminalInputGate();
    private static final long TERMINAL_INPUT_SUPPRESSION_MS = 150L;
    private WindowManager windowManager;
    private OverlayWindowRegistry<View, WindowManager.LayoutParams> windowRegistry;
    private WindowManager.LayoutParams layoutParams;
    private View primaryOverlay;
    private FloatingOverlayViewController overlayViewController;
    private FloatingActionMenuController actionMenuController;
    private DragTapListener dragTapListener;
    private int idleAnchorX;
    private int idleAnchorY;
    private MediaRecorder recorder;
    private File activeRecording;
    private File readyVoiceRecording;
    private int readyVoiceDuration;
    private long readyVoiceAttemptId;
    private long recordingStartedAt;
    private TelegramRepository telegram;
    private String notificationText;
    private int notificationResourceId = R.string.notification_ready;
    private Object[] notificationArguments = new Object[0];
    private boolean primaryOverlayAttached;
    private BroadcastReceiver composerClosedReceiver;

    @Override public void onCreate() {
        super.onCreate();
        telegram = ((FloatingVoiceApp) getApplication()).telegram();
        telegram.addListener(this);
        composerClosedReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!ACTION_COMPOSER_CLOSED.equals(intent.getAction()) || isTearingDown()) return;
                if (overlayStateMachine.state() == OverlayStateMachine.State.TEXT_COMPOSING) {
                    dispatchOverlayEvent(OverlayEvent.CLOSE_COMPOSER);
                }
                restorePrimaryOverlay();
            }
        };
        IntentFilter composerFilter = new IntentFilter(ACTION_COMPOSER_CLOSED);
        ContextCompat.registerReceiver(this, composerClosedReceiver, composerFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!telegram.isReadyWithTarget()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                int serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                if (Build.VERSION.SDK_INT >= 34) {
                    serviceTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                }
                startForeground(NOTIFICATION_ID, buildNotification(), serviceTypes);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (primaryOverlay == null) addPrimaryOverlay();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        overlayStateMachine.accept(OverlayEvent.TEARDOWN);
        if (composerClosedReceiver != null) {
            try { unregisterReceiver(composerClosedReceiver); }
            catch (IllegalArgumentException ignored) { }
            composerClosedReceiver = null;
        }
        if (dragTapListener != null) dragTapListener.cancelPending();
        if (actionMenuController != null) actionMenuController.destroy();
        if (overlayViewController != null) overlayViewController.destroy();
        primaryOverlay = null;
        actionMenuController = null;
        overlayViewController = null;
        dragTapListener = null;
        if (recorder != null) stopAndRetainInterruptedRecording();
        if (windowRegistry != null) windowRegistry.removeAll();
        primaryOverlayAttached = false;
        telegram.removeListener(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void addPrimaryOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            updateState(R.string.overlay_permission_required);
            stopSelf();
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        windowRegistry = new OverlayWindowRegistry<>(new WindowManagerBackend(windowManager));
        overlayViewController = new FloatingOverlayViewController(
                this, new FloatingOverlayViewController.Listener() {
            @Override public void onStopAndSend() {
                if (!isTearingDown()) dispatchOverlayEvent(OverlayEvent.TAP);
            }

            @Override public void onCancel() {
                if (!isTearingDown()) {
                    dispatchOverlayEvent(OverlayEvent.CANCEL_VOICE_REQUESTED);
                }
            }
        });
        actionMenuController = new FloatingActionMenuController(
                this, windowRegistry, new FloatingActionMenuController.Listener() {
            @Override public void onComposeText() {
                // V5-05 bridge: the reducer closes the palette (HIDE_MENU) and emits
                // OPEN_TEXT_COMPOSER. V5-07 binds composer draft/send lifecycle to the reducer.
                dispatchOverlayEvent(OverlayEvent.COMPOSE_TEXT);
            }

            @Override public void onDismissRequested() {
                terminalInputGate.suppressTouchesThrough(
                        SystemClock.uptimeMillis() + 200L);
                dispatchOverlayEvent(OverlayEvent.GESTURE_CANCELED);
            }
        });
        primaryOverlay = overlayViewController.root();
        int size = px(R.dimen.overlay_fab_size);
        layoutParams = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        Rect initialSafeBounds = currentDisplayBounds();
        int initialMargin = px(R.dimen.overlay_safe_margin);
        idleAnchorX = initialSafeBounds.left + initialMargin;
        idleAnchorY = Math.max(initialSafeBounds.top + initialMargin, dp(160));
        layoutParams.x = idleAnchorX;
        layoutParams.y = idleAnchorY;
        overlayViewController.setIdleClickListener(v -> {
            if (terminalInputGate.shouldSuppress(SystemClock.uptimeMillis())) return;
            dispatchOverlayEvent(OverlayEvent.TAP);
        });
        dragTapListener = new DragTapListener();
        overlayViewController.setIdleTouchListener(dragTapListener);
        overlayViewController.setRecordingDragTouchListener(dragTapListener);
        try {
            windowRegistry.add(primaryOverlay, layoutParams);
            primaryOverlayAttached = true;
        } catch (RuntimeException e) {
            primaryOverlay = null;
            primaryOverlayAttached = false;
            stopSelf();
        }
    }

    private OverlayStateMachine.Transition dispatchOverlayEvent(OverlayEvent event) {
        return applyTransition(overlayStateMachine.accept(event));
    }

    private OverlayStateMachine.Transition dispatchOverlayEvent(
            OverlayEvent event, long attemptId) {
        return applyTransition(overlayStateMachine.accept(event, attemptId));
    }

    private OverlayStateMachine.Transition applyTransition(
            OverlayStateMachine.Transition transition) {
        for (OverlayStateMachine.Effect effect : transition.effects()) {
            switch (effect) {
                case START_VOICE -> startRecording();
                case STOP_VOICE -> stopRecordingAndSend();
                case CANCEL_VOICE -> cancelRecording();
                case SEND_VOICE -> sendReadyVoice();
                case SHOW_MENU -> showActionMenu();
                case HIDE_MENU -> hideActionMenu();
                case OPEN_TEXT_COMPOSER -> openTextComposer();
                case SEND_TEXT -> { /* V5-06 connects transport. */ }
            }
        }
        return transition;
    }

    private void startRecording() {
        File externalMusic = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        File root = new File(externalMusic == null ? getFilesDir() : externalMusic, "voice_notes");
        if (!root.mkdirs() && !root.isDirectory()) {
            dispatchOverlayEvent(OverlayEvent.VOICE_START_FAILED);
            updateState(R.string.recording_folder_failed);
            return;
        }
        activeRecording = new File(root, "voice-"
                + DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-') + ".ogg");
        MediaRecorder next = null;
        try {
            next = new MediaRecorder();
            next.setAudioSource(MediaRecorder.AudioSource.MIC);
            next.setOutputFormat(MediaRecorder.OutputFormat.OGG);
            next.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
            next.setAudioChannels(1);
            next.setAudioSamplingRate(48_000);
            next.setAudioEncodingBitRate(32_000);
            next.setOutputFile(activeRecording.getAbsolutePath());
            next.prepare();
            next.start();
        } catch (Exception e) {
            if (next != null) {
                try { next.release(); } catch (RuntimeException ignored) { }
            }
            recorder = null;
            dispatchOverlayEvent(OverlayEvent.VOICE_START_FAILED);
            updateState(R.string.recording_start_failed, e.getMessage());
            return;
        }

        recorder = next;
        recordingStartedAt = SystemClock.elapsedRealtime();
        dispatchOverlayEvent(OverlayEvent.VOICE_START_SUCCEEDED);
        boolean dockShown = showRecordingDock();
        if (isRecordingState()) {
            updateState(dockShown
                    ? R.string.recording_in_progress
                    : R.string.recording_in_progress_cancel_unavailable);
        }
    }

    private void stopRecordingAndSend() {
        hideActionMenu();
        terminalInputGate.suppressTouchesThrough(
                SystemClock.uptimeMillis() + TERMINAL_INPUT_SUPPRESSION_MS);
        MediaRecorder current = recorder;
        recorder = null;
        int duration = (int) Math.max(1,
                (SystemClock.elapsedRealtime() - recordingStartedAt + 999) / 1000);
        try {
            if (current == null) throw new IllegalStateException("Recorder is not active");
            current.stop();
            current.release();
        } catch (RuntimeException e) {
            if (current != null) {
                try { current.release(); } catch (RuntimeException ignored) { }
            }
            dispatchOverlayEvent(OverlayEvent.VOICE_STOP_FAILED);
            updateIdleBubble();
            updateState(R.string.recording_stop_failed_retained, activeRecording);
            return;
        }
        readyVoiceRecording = activeRecording;
        readyVoiceDuration = duration;
        readyVoiceAttemptId = overlayStateMachine.attemptId();
        activeRecording = null;
        updateIdleBubble();
        dispatchOverlayEvent(OverlayEvent.VOICE_STOP_SUCCEEDED);
    }

    private void cancelRecording() {
        hideActionMenu();
        terminalInputGate.suppressTouchesThrough(
                SystemClock.uptimeMillis() + TERMINAL_INPUT_SUPPRESSION_MS);
        MediaRecorder current = recorder;
        recorder = null;
        File canceledRecording = activeRecording;
        activeRecording = null;

        RecordingCancelOperation.Result result = cancelOperation.execute(
                recorderPort(current), canceledRecording, filePort());

        updateIdleBubble();
        switch (result.outcome()) {
            case CANCELED -> {
                dispatchOverlayEvent(OverlayEvent.VOICE_CANCEL_SUCCEEDED);
                updateState(R.string.recording_cancelled_no_message);
            }
            case RETAINED_STOP_FAILURE -> {
                dispatchOverlayEvent(OverlayEvent.VOICE_CANCEL_FAILED);
                updateState(R.string.recording_cancel_failed_retained, result.recording());
            }
            case RETAINED_RELEASE_FAILURE -> {
                dispatchOverlayEvent(OverlayEvent.VOICE_CANCEL_FAILED);
                updateState(R.string.recording_cancel_failed_retained, result.recording());
            }
            case RETAINED_DELETE_FAILURE -> {
                dispatchOverlayEvent(OverlayEvent.VOICE_CANCEL_FAILED);
                updateState(R.string.recording_cancel_delete_failed_retained, result.recording());
            }
        }
    }

    private static RecordingCancelOperation.RecorderPort recorderPort(MediaRecorder recorder) {
        return new RecordingCancelOperation.RecorderPort() {
            @Override public void stop() {
                if (recorder == null) throw new IllegalStateException("Recorder is not active");
                recorder.stop();
            }
            @Override public void release() {
                if (recorder != null) recorder.release();
            }
        };
    }

    private static RecordingCancelOperation.FilePort filePort() {
        return new RecordingCancelOperation.FilePort() {
            @Override public boolean exists(File file) {
                return file != null && file.exists();
            }
            @Override public boolean delete(File file) {
                return file != null && file.delete();
            }
        };
    }

    private boolean showRecordingDock() {
        if (overlayViewController == null || primaryOverlay == null || layoutParams == null
                || windowRegistry == null || !primaryOverlayAttached) {
            cancelRecordingFallback();
            return false;
        }
        hideActionMenu();
        idleAnchorX = layoutParams.x;
        idleAnchorY = layoutParams.y;
        overlayViewController.showRecording(recordingStartedAt);
        layoutParams.width = px(R.dimen.overlay_dock_width);
        layoutParams.height = px(R.dimen.overlay_dock_height);
        Rect display = currentDisplayBounds();
        int margin = px(R.dimen.overlay_safe_margin);
        int rightAlignedX = idleAnchorX
                + px(R.dimen.overlay_fab_size) - layoutParams.width;
        int minX = display.left + margin;
        int maxX = Math.max(minX, display.right - margin - layoutParams.width);
        layoutParams.x = idleAnchorX + layoutParams.width <= display.right - margin
                ? clamp(idleAnchorX, minX, maxX)
                : clamp(rightAlignedX, minX, maxX);
        int minY = display.top + margin;
        int maxY = Math.max(minY, display.bottom - margin - layoutParams.height);
        layoutParams.y = clamp(idleAnchorY, minY, maxY);
        try {
            windowRegistry.update(primaryOverlay, layoutParams);
            return true;
        } catch (RuntimeException ignored) {
            // A dock window that cannot be resized would leave no reachable stop/cancel
            // control. Cancel the recording (no Telegram send) and restore the idle bubble.
            cancelRecordingFallback();
            return false;
        }
    }

    private void cancelRecordingFallback() {
        if (overlayStateMachine.state() == OverlayStateMachine.State.RECORDING) {
            dispatchOverlayEvent(OverlayEvent.CANCEL_VOICE_REQUESTED);
        }
    }

    private void showIdleOverlay() {
        if (overlayViewController == null || primaryOverlay == null || layoutParams == null) return;
        overlayViewController.showIdle();
        layoutParams.width = px(R.dimen.overlay_fab_size);
        layoutParams.height = px(R.dimen.overlay_fab_size);
        Rect display = currentDisplayBounds();
        int margin = px(R.dimen.overlay_safe_margin);
        int minX = display.left + margin;
        int minY = display.top + margin;
        layoutParams.x = clamp(idleAnchorX, minX,
                Math.max(minX, display.right - margin - layoutParams.width));
        layoutParams.y = clamp(idleAnchorY, minY,
                Math.max(minY, display.bottom - margin - layoutParams.height));
        try { windowRegistry.update(primaryOverlay, layoutParams); }
        catch (RuntimeException ignored) { }
    }

    private void showActionMenu() {
        if (actionMenuController == null || layoutParams == null) return;
        Rect display = currentDisplayBounds();
        int panelWidth = px(R.dimen.overlay_palette_width);
        int panelHeight = px(R.dimen.overlay_palette_height);
        int margin = px(R.dimen.overlay_safe_margin);
        int gap = px(R.dimen.overlay_gap);
        final AnchoredPanelPlacement.Placement placement;
        try {
            placement = AnchoredPanelPlacement.place(
                    display.width(), display.height(),
                    layoutParams.x - display.left, layoutParams.y - display.top,
                    layoutParams.x - display.left + layoutParams.width,
                    layoutParams.y - display.top + layoutParams.height,
                    panelWidth, panelHeight, margin, gap);
        } catch (IllegalArgumentException ignored) {
            dispatchOverlayEvent(OverlayEvent.GESTURE_CANCELED);
            return;
        }
        if (!actionMenuController.showAt(
                display.left + placement.x(), display.top + placement.y(),
                panelWidth, panelHeight)) {
            dispatchOverlayEvent(OverlayEvent.GESTURE_CANCELED);
        }
    }

    private void hideActionMenu() {
        if (actionMenuController != null) actionMenuController.dismiss();
    }

    private void openTextComposer() {
        Intent composer = new Intent(this, TextComposerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(composer);
            hidePrimaryOverlay();
        } catch (RuntimeException ignored) {
            dispatchOverlayEvent(OverlayEvent.CLOSE_COMPOSER);
            restorePrimaryOverlay();
        }
    }

    private void hidePrimaryOverlay() {
        View currentOverlay = primaryOverlay;
        if (currentOverlay == null || windowRegistry == null || !primaryOverlayAttached) return;
        if (overlayStateMachine.state() != OverlayStateMachine.State.TEXT_COMPOSING) return;
        if (windowRegistry.remove(currentOverlay)) primaryOverlayAttached = false;
    }

    private void restorePrimaryOverlay() {
        View currentOverlay = primaryOverlay;
        if (currentOverlay == null || layoutParams == null || windowRegistry == null
                || primaryOverlayAttached || isTearingDown()) return;
        showIdleOverlay();
        try {
            windowRegistry.add(currentOverlay, layoutParams);
            primaryOverlayAttached = true;
        } catch (RuntimeException ignored) {
            primaryOverlayAttached = false;
            stopSelf();
        }
    }

    private Rect currentDisplayBounds() {
        return DisplaySafeBounds.from(this);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private void updateIdleAnchorFromPrimary() {
        if (layoutParams == null) return;
        Rect display = currentDisplayBounds();
        int fabSize = px(R.dimen.overlay_fab_size);
        if (layoutParams.width > fabSize
                && layoutParams.x + layoutParams.width / 2 > display.centerX()) {
            idleAnchorX = layoutParams.x + layoutParams.width - fabSize;
        } else {
            idleAnchorX = layoutParams.x;
        }
        idleAnchorY = layoutParams.y;
    }

    private void sendReadyVoice() {
        File completed = readyVoiceRecording;
        int duration = readyVoiceDuration;
        long attemptId = readyVoiceAttemptId;
        readyVoiceRecording = null;
        readyVoiceDuration = 0;
        readyVoiceAttemptId = 0;
        if (completed == null) return;

        telegram.sendVoiceNote(completed, duration, new TelegramRepository.SendCallback() {
            @Override public void onQueued(long temporaryMessageId) {
                handleVoiceSendCallback(OverlayEvent.VOICE_QUEUED, attemptId,
                        () -> updateState(R.string.voice_queued_retained));
            }

            @Override public void onRejected(String reason) {
                handleVoiceSendCallback(OverlayEvent.VOICE_REJECTED, attemptId,
                        () -> updateState(reason));
            }
        });
    }

    private void handleVoiceSendCallback(
            OverlayEvent event, long attemptId, Runnable notificationUpdate) {
        View currentOverlay = primaryOverlay;
        if (currentOverlay == null || isTearingDown()) return;
        currentOverlay.post(() -> {
            if (primaryOverlay != currentOverlay || isTearingDown()) return;
            OverlayStateMachine.Transition transition = dispatchOverlayEvent(event, attemptId);
            if (transition.previousState() != transition.nextState()) {
                notificationUpdate.run();
            }
        });
    }

    private void stopAndRetainInterruptedRecording() {
        MediaRecorder current = recorder;
        recorder = null;
        try { current.stop(); } catch (RuntimeException ignored) { }
        try { current.release(); } catch (RuntimeException ignored) { }
        if (activeRecording != null) {
            notificationResourceId = R.string.recording_interrupted_retained;
            notificationArguments = new Object[] {activeRecording.getAbsolutePath()};
            notificationText = null;
        }
    }

    private void updateIdleBubble() {
        View currentOverlay = primaryOverlay;
        if (currentOverlay == null) return;
        currentOverlay.post(() -> {
            if (primaryOverlay != currentOverlay || isTearingDown()) return;
            showIdleOverlay();
        });
    }

    private void updateState(String text) {
        notificationResourceId = 0;
        notificationArguments = new Object[0];
        notificationText = text;
        publishState();
    }

    private void updateState(int resourceId, Object... arguments) {
        notificationResourceId = resourceId;
        notificationArguments = Arrays.copyOf(arguments, arguments.length);
        notificationText = null;
        publishState();
    }

    private void publishState() {
        createNotificationChannel();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private String renderedNotificationText() {
        return notificationResourceId == 0
                ? notificationText
                : text(notificationResourceId, notificationArguments);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, FloatingVoiceService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_floating_voice)
                .setContentTitle(text(R.string.app_name))
                .setContentText(renderedNotificationText())
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null,
                        text(R.string.notification_stop_action), stopIntent).build())
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                text(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(text(R.string.notification_channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private String text(int resourceId, Object... arguments) {
        return LocalizedStrings.get(this, resourceId, arguments);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int px(int dimenResource) {
        return getResources().getDimensionPixelSize(dimenResource);
    }

    @Override public void onStatus(String status) {
        View currentOverlay = primaryOverlay;
        if (currentOverlay == null || isTearingDown()) return;
        currentOverlay.post(() -> {
            if (primaryOverlay != currentOverlay || isTearingDown()) return;
            boolean isRecording = isRecordingState();
            if (overlayViewController != null) overlayViewController.refreshStrings();
            if (!isRecording) updateState(status);
        });
    }

    @Override public void onLocaleChanged() {
        View currentOverlay = primaryOverlay;
        if (currentOverlay == null || isTearingDown()) return;
        currentOverlay.post(() -> {
            if (primaryOverlay != currentOverlay || isTearingDown()) return;
            boolean isRecording = isRecordingState();
            if (overlayViewController != null) overlayViewController.refreshStrings();
            if (actionMenuController != null) actionMenuController.refreshStrings();
            if (isRecording) {
                updateState(R.string.recording_in_progress);
            } else if (notificationResourceId == 0) {
                updateState(telegram.lastStatus());
            } else {
                publishState();
            }
        });
    }

    private boolean isRecordingState() {
        return overlayStateMachine.state() == OverlayStateMachine.State.RECORDING;
    }

    private boolean isTearingDown() {
        return overlayStateMachine.state() == OverlayStateMachine.State.TEARING_DOWN;
    }

    private final class DragTapListener implements View.OnTouchListener {
        private int initialX;
        private int initialY;
        private float downX;
        private float downY;
        private float latestX;
        private float latestY;
        private long downAt;
        private Runnable longPressTimeout;
        private View timeoutHost;
        private final GestureClassifier classifier = new GestureClassifier(
                dp(TOUCH_MOVEMENT_THRESHOLD_DP), TOUCH_LONG_PRESS_THRESHOLD_MS);

        @Override public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancelTimeout();
                    if (overlayViewController != null && !isRecordingState()) {
                        overlayViewController.setIdlePressed(true);
                    }
                    initialX = layoutParams.x;
                    initialY = layoutParams.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    latestX = downX;
                    latestY = downY;
                    downAt = SystemClock.elapsedRealtime();
                    classifier.classify(GestureClassifier.Action.DOWN, 0, 0, 0);
                    scheduleLongPress(view);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    latestX = event.getRawX();
                    latestY = event.getRawY();
                    GestureClassifier.Classification movement = classifier.classify(
                            GestureClassifier.Action.MOVE,
                            SystemClock.elapsedRealtime() - downAt,
                            latestX - downX, latestY - downY);
                    if (movement == GestureClassifier.Classification.DRAG
                            || movement == GestureClassifier.Classification.LONG_PRESS) {
                        cancelTimeout();
                        if (overlayViewController != null) {
                            overlayViewController.setIdlePressed(false);
                        }
                    }
                    if (movement == GestureClassifier.Classification.LONG_PRESS
                            && view == primaryOverlay
                            && overlayStateMachine.state() == OverlayStateMachine.State.IDLE) {
                        view.performHapticFeedback(
                                android.view.HapticFeedbackConstants.LONG_PRESS);
                        dispatchOverlayEvent(OverlayEvent.LONG_PRESS);
                    }
                    if (movement == GestureClassifier.Classification.DRAG) {
                        if (overlayStateMachine.state() == OverlayStateMachine.State.MENU_OPEN) {
                            dispatchOverlayEvent(OverlayEvent.GESTURE_CANCELED);
                        }
                        Rect display = currentDisplayBounds();
                        int margin = px(R.dimen.overlay_safe_margin);
                        int minX = display.left + margin;
                        int minY = display.top + margin;
                        layoutParams.x = clamp(initialX + Math.round(latestX - downX),
                                minX,
                                Math.max(minX,
                                        display.right - margin - layoutParams.width));
                        layoutParams.y = clamp(initialY + Math.round(latestY - downY),
                                minY,
                                Math.max(minY,
                                        display.bottom - margin - layoutParams.height));
                        windowRegistry.update(primaryOverlay, layoutParams);
                        updateIdleAnchorFromPrimary();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    latestX = event.getRawX();
                    latestY = event.getRawY();
                    cancelTimeout();
                    if (overlayViewController != null) {
                        overlayViewController.setIdlePressed(false);
                    }
                    GestureClassifier.Classification classification = classifier.classify(
                            GestureClassifier.Action.UP,
                            SystemClock.elapsedRealtime() - downAt,
                            latestX - downX, latestY - downY);
                    if (classification == GestureClassifier.Classification.TAP
                            && view == primaryOverlay
                            && overlayStateMachine.state() == OverlayStateMachine.State.IDLE) {
                        primaryOverlay.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    latestX = event.getRawX();
                    latestY = event.getRawY();
                    cancelTimeout();
                    classifier.classify(GestureClassifier.Action.CANCEL,
                            SystemClock.elapsedRealtime() - downAt,
                            latestX - downX, latestY - downY);
                    if (overlayViewController != null) {
                        overlayViewController.setIdlePressed(false);
                    }
                    if (overlayStateMachine.state() == OverlayStateMachine.State.MENU_OPEN) {
                        dispatchOverlayEvent(OverlayEvent.GESTURE_CANCELED);
                    }
                    return true;
                default:
                    return false;
            }
        }

        void cancelPending() {
            cancelTimeout();
            classifier.classify(GestureClassifier.Action.CANCEL,
                    Math.max(0, SystemClock.elapsedRealtime() - downAt),
                    latestX - downX, latestY - downY);
        }

        private void scheduleLongPress(View view) {
            if (overlayStateMachine.state() != OverlayStateMachine.State.IDLE) return;
            timeoutHost = view;
            longPressTimeout = () -> {
                longPressTimeout = null;
                timeoutHost = null;
                GestureClassifier.Classification classification = classifier.classify(
                        GestureClassifier.Action.TIMEOUT,
                        SystemClock.elapsedRealtime() - downAt,
                        latestX - downX, latestY - downY);
                if (classification == GestureClassifier.Classification.LONG_PRESS
                        && overlayStateMachine.state() == OverlayStateMachine.State.IDLE) {
                    view.performHapticFeedback(
                            android.view.HapticFeedbackConstants.LONG_PRESS);
                    dispatchOverlayEvent(OverlayEvent.LONG_PRESS);
                }
            };
            view.postDelayed(longPressTimeout, TOUCH_LONG_PRESS_THRESHOLD_MS);
        }

        private void cancelTimeout() {
            Runnable pending = longPressTimeout;
            View host = timeoutHost;
            longPressTimeout = null;
            timeoutHost = null;
            if (pending != null && host != null) host.removeCallbacks(pending);
        }
    }
}

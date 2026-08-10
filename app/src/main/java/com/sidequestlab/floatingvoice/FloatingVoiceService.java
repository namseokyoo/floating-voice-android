package com.sidequestlab.floatingvoice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
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
import android.widget.ImageButton;

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
    private ImageButton bubble;
    private WindowManager.LayoutParams cancelLayoutParams;
    private ImageButton cancelButton;
    private DragTapListener dragTapListener;
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

    @Override public void onCreate() {
        super.onCreate();
        telegram = ((FloatingVoiceApp) getApplication()).telegram();
        telegram.addListener(this);
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
        if (bubble == null) addBubble();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        overlayStateMachine.accept(OverlayEvent.TEARDOWN);
        ImageButton currentBubble = bubble;
        DragTapListener currentTouchListener = dragTapListener;
        bubble = null;
        cancelButton = null;
        dragTapListener = null;
        if (currentBubble != null && currentTouchListener != null) {
            currentTouchListener.cancelPending(currentBubble);
        }
        if (recorder != null) stopAndRetainInterruptedRecording();
        if (windowRegistry != null) windowRegistry.removeAll();
        telegram.removeListener(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void addBubble() {
        if (!Settings.canDrawOverlays(this)) {
            updateState(R.string.overlay_permission_required);
            stopSelf();
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        windowRegistry = new OverlayWindowRegistry<>(new WindowManagerBackend(windowManager));
        bubble = new ImageButton(LocalizedStrings.context(this));
        bubble.setImageResource(R.drawable.ic_overlay_mic);
        bubble.setContentDescription(text(R.string.content_description_start_recording));
        bubble.setPadding(dp(17), dp(17), dp(17), dp(17));
        bubble.setBackgroundResource(R.drawable.overlay_idle);
        int size = dp(64);
        layoutParams = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = dp(16);
        layoutParams.y = dp(160);
        bubble.setOnClickListener(v -> {
            if (terminalInputGate.shouldSuppress(SystemClock.uptimeMillis())) return;
            dispatchOverlayEvent(OverlayEvent.TAP);
        });
        dragTapListener = new DragTapListener();
        bubble.setOnTouchListener(dragTapListener);
        windowRegistry.add(bubble, layoutParams);
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
                case SEND_TEXT, SHOW_MENU, HIDE_MENU, OPEN_TEXT_COMPOSER -> {
                    // Later stages connect menu and text effects.
                }
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
        bubble.setImageResource(R.drawable.ic_overlay_stop);
        bubble.setContentDescription(text(R.string.content_description_stop_and_send));
        bubble.setBackgroundResource(R.drawable.overlay_recording);
        updateState(showCancelButton()
                ? R.string.recording_in_progress
                : R.string.recording_in_progress_cancel_unavailable);
    }

    private void stopRecordingAndSend() {
        hideCancelButton();
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
        hideCancelButton();
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

    private boolean showCancelButton() {
        if (cancelButton != null || windowRegistry == null) return cancelButton != null;
        ImageButton next = new ImageButton(LocalizedStrings.context(this));
        next.setImageResource(R.drawable.ic_overlay_cancel);
        next.setContentDescription(text(R.string.content_description_cancel_recording));
        next.setPadding(dp(14), dp(14), dp(14), dp(14));
        next.setBackgroundResource(R.drawable.overlay_recording);
        next.setOnClickListener(view -> {
            if (cancelButton != view || isTearingDown()) return;
            dispatchOverlayEvent(OverlayEvent.CANCEL_VOICE_REQUESTED);
        });

        int size = dp(56);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        cancelButton = next;
        cancelLayoutParams = params;
        positionCancelButton();
        try {
            windowRegistry.add(next, params);
            return true;
        } catch (RuntimeException ignored) {
            cancelButton = null;
            cancelLayoutParams = null;
            return false;
        }
    }

    private void hideCancelButton() {
        ImageButton current = cancelButton;
        cancelButton = null;
        cancelLayoutParams = null;
        if (windowRegistry != null) windowRegistry.remove(current);
    }

    private void positionCancelButton() {
        if (cancelLayoutParams == null || layoutParams == null) return;
        int gap = dp(8);
        int cancelSize = dp(56);
        int bubbleSize = dp(64);
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        int right = layoutParams.x + bubbleSize + gap;
        cancelLayoutParams.x = right + cancelSize <= width
                ? right
                : Math.max(0, layoutParams.x - gap - cancelSize);
        cancelLayoutParams.y = Math.max(0, Math.min(layoutParams.y, height - cancelSize));
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
        ImageButton currentBubble = bubble;
        if (currentBubble == null || isTearingDown()) return;
        currentBubble.post(() -> {
            if (bubble != currentBubble || isTearingDown()) return;
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
        ImageButton currentBubble = bubble;
        if (currentBubble == null) return;
        currentBubble.post(() -> {
            if (bubble != currentBubble || isTearingDown()) return;
            currentBubble.setImageResource(R.drawable.ic_overlay_mic);
            currentBubble.setContentDescription(text(R.string.content_description_start_recording));
            currentBubble.setBackgroundResource(R.drawable.overlay_idle);
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

    @Override public void onStatus(String status) {
        ImageButton currentBubble = bubble;
        if (currentBubble == null || isTearingDown()) return;
        currentBubble.post(() -> {
            if (bubble != currentBubble || isTearingDown()) return;
            boolean isRecording = isRecordingState();
            refreshOverlayDescriptions(isRecording);
            if (!isRecording) updateState(status);
        });
    }

    @Override public void onLocaleChanged() {
        ImageButton currentBubble = bubble;
        if (currentBubble == null || isTearingDown()) return;
        currentBubble.post(() -> {
            if (bubble != currentBubble || isTearingDown()) return;
            boolean isRecording = isRecordingState();
            refreshOverlayDescriptions(isRecording);
            if (isRecording) {
                updateState(cancelButton != null
                        ? R.string.recording_in_progress
                        : R.string.recording_in_progress_cancel_unavailable);
            } else if (notificationResourceId == 0) {
                updateState(telegram.lastStatus());
            } else {
                publishState();
            }
        });
    }

    private void refreshOverlayDescriptions(boolean isRecording) {
        if (bubble != null) {
            bubble.setContentDescription(text(isRecording
                    ? R.string.content_description_stop_and_send
                    : R.string.content_description_start_recording));
        }
        if (cancelButton != null) {
            cancelButton.setContentDescription(text(R.string.content_description_cancel_recording));
        }
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
        private final GestureClassifier classifier = new GestureClassifier(
                dp(TOUCH_MOVEMENT_THRESHOLD_DP), TOUCH_LONG_PRESS_THRESHOLD_MS);

        @Override public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancelTimeout(view);
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
                        cancelTimeout(view);
                    }
                    if (movement == GestureClassifier.Classification.DRAG) {
                        layoutParams.x = initialX + Math.round(latestX - downX);
                        layoutParams.y = initialY + Math.round(latestY - downY);
                        windowRegistry.update(view, layoutParams);
                        positionCancelButton();
                        windowRegistry.update(cancelButton, cancelLayoutParams);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    latestX = event.getRawX();
                    latestY = event.getRawY();
                    cancelTimeout(view);
                    GestureClassifier.Classification classification = classifier.classify(
                            GestureClassifier.Action.UP,
                            SystemClock.elapsedRealtime() - downAt,
                            latestX - downX, latestY - downY);
                    if (classification == GestureClassifier.Classification.TAP) {
                        view.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    latestX = event.getRawX();
                    latestY = event.getRawY();
                    cancelTimeout(view);
                    classifier.classify(GestureClassifier.Action.CANCEL,
                            SystemClock.elapsedRealtime() - downAt,
                            latestX - downX, latestY - downY);
                    return true;
                default:
                    return false;
            }
        }

        void cancelPending(View view) {
            cancelTimeout(view);
            classifier.classify(GestureClassifier.Action.CANCEL,
                    Math.max(0, SystemClock.elapsedRealtime() - downAt),
                    latestX - downX, latestY - downY);
        }

        private void scheduleLongPress(View view) {
            longPressTimeout = () -> {
                longPressTimeout = null;
                GestureClassifier.Classification classification = classifier.classify(
                        GestureClassifier.Action.TIMEOUT,
                        SystemClock.elapsedRealtime() - downAt,
                        latestX - downX, latestY - downY);
                if (classification == GestureClassifier.Classification.LONG_PRESS) {
                    // V5-05 will connect this classification to the visible menu.
                }
            };
            view.postDelayed(longPressTimeout, TOUCH_LONG_PRESS_THRESHOLD_MS);
        }

        private void cancelTimeout(View view) {
            Runnable pending = longPressTimeout;
            longPressTimeout = null;
            if (pending != null) view.removeCallbacks(pending);
        }
    }
}

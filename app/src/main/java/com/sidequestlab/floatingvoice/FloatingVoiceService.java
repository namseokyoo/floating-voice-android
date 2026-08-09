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
import android.os.Environment;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

public final class FloatingVoiceService extends Service implements TelegramRepository.Listener {
    public static final String ACTION_START = "com.sidequestlab.floatingvoice.START_OVERLAY";
    public static final String ACTION_STOP = "com.sidequestlab.floatingvoice.STOP_OVERLAY";
    private static final int NOTIFICATION_ID = 41;
    private static final String CHANNEL_ID = "floatingvoice_overlay";

    private WindowManager windowManager;
    private WindowManager.LayoutParams layoutParams;
    private TextView bubble;
    private MediaRecorder recorder;
    private File activeRecording;
    private long recordingStartedAt;
    private TelegramRepository telegram;
    private String notificationText = "준비됨 — 플로팅 버튼을 누르면 녹음합니다";

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
        if (recorder != null) stopAndRetainInterruptedRecording();
        if (bubble != null && windowManager != null) {
            windowManager.removeView(bubble);
            bubble = null;
        }
        telegram.removeListener(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void addBubble() {
        if (!Settings.canDrawOverlays(this)) {
            updateState("다른 앱 위 표시 권한이 필요합니다", false);
            stopSelf();
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        bubble = new TextView(this);
        bubble.setText("녹음");
        bubble.setTextColor(0xffffffff);
        bubble.setTextSize(13);
        bubble.setGravity(Gravity.CENTER);
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
        bubble.setOnClickListener(v -> toggleRecording());
        bubble.setOnTouchListener(new DragTapListener());
        windowManager.addView(bubble, layoutParams);
    }

    private void toggleRecording() {
        if (recorder == null) startRecording(); else stopRecordingAndSend();
    }

    private void startRecording() {
        File externalMusic = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        File root = new File(externalMusic == null ? getFilesDir() : externalMusic, "voice_notes");
        if (!root.mkdirs() && !root.isDirectory()) {
            updateState("녹음 파일 폴더를 만들 수 없습니다", false);
            return;
        }
        activeRecording = new File(root, "voice-"
                + DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-') + ".ogg");
        MediaRecorder next = new MediaRecorder();
        try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC);
            next.setOutputFormat(MediaRecorder.OutputFormat.OGG);
            next.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
            next.setAudioChannels(1);
            next.setAudioSamplingRate(48_000);
            next.setAudioEncodingBitRate(32_000);
            next.setOutputFile(activeRecording.getAbsolutePath());
            next.prepare();
            next.start();
            recorder = next;
            recordingStartedAt = SystemClock.elapsedRealtime();
            bubble.setText("전송");
            bubble.setBackgroundResource(R.drawable.overlay_recording);
            updateState("녹음 중 — 다시 누르면 녹음을 끝내고 전송합니다", true);
        } catch (Exception e) {
            next.release();
            recorder = null;
            updateState("녹음을 시작하지 못했습니다: " + e.getMessage(), false);
        }
    }

    private void stopRecordingAndSend() {
        MediaRecorder current = recorder;
        recorder = null;
        int duration = (int) Math.max(1,
                (SystemClock.elapsedRealtime() - recordingStartedAt + 999) / 1000);
        try {
            current.stop();
            current.release();
        } catch (RuntimeException e) {
            current.release();
            updateIdleBubble();
            updateState("녹음을 정상 종료하지 못했습니다. 파일 보관 위치: " + activeRecording, false);
            return;
        }
        File completed = activeRecording;
        activeRecording = null;
        updateIdleBubble();
        telegram.sendVoiceNote(completed, duration, new TelegramRepository.SendCallback() {
            @Override public void onQueued(long temporaryMessageId) {
                updateState("음성 전송 대기 중 — 성공 확인 전까지 파일을 보관합니다", false);
            }

            @Override public void onRejected(String reason) {
                updateState(reason, false);
            }
        });
    }

    private void stopAndRetainInterruptedRecording() {
        MediaRecorder current = recorder;
        recorder = null;
        try { current.stop(); } catch (RuntimeException ignored) { }
        current.release();
        if (activeRecording != null) {
            notificationText = "녹음 중 플로팅 버튼이 종료되었습니다. 파일 보관 위치: "
                    + activeRecording.getAbsolutePath();
        }
    }

    private void updateIdleBubble() {
        if (bubble != null) {
            bubble.post(() -> {
                bubble.setText("녹음");
                bubble.setBackgroundResource(R.drawable.overlay_idle);
            });
        }
    }

    private void updateState(String text, boolean recording) {
        notificationText = text;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification());
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
                .setContentTitle("플로팅 보이스")
                .setContentText(notificationText)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "플로팅 버튼 종료", stopIntent).build())
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "플로팅 음성 녹음", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Telegram 음성 전송용 플로팅 녹음 상태 알림");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onStatus(String status) {
        if (bubble != null) updateState(status, recorder != null);
    }

    private final class DragTapListener implements View.OnTouchListener {
        private int initialX;
        private int initialY;
        private float downX;
        private float downY;
        private long downAt;

        @Override public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = layoutParams.x;
                    initialY = layoutParams.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    downAt = SystemClock.elapsedRealtime();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    layoutParams.x = initialX + Math.round(event.getRawX() - downX);
                    layoutParams.y = initialY + Math.round(event.getRawY() - downY);
                    windowManager.updateViewLayout(bubble, layoutParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    float distance = Math.abs(event.getRawX() - downX)
                            + Math.abs(event.getRawY() - downY);
                    if (distance < dp(12) && SystemClock.elapsedRealtime() - downAt < 600) {
                        view.performClick();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }
}

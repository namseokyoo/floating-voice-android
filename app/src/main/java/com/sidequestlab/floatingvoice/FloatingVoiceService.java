package com.sidequestlab.floatingvoice;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.sidequestlab.floatingvoice.core.AnchoredPanelPlacement;
import com.sidequestlab.floatingvoice.core.AudioCaptureOwnership;
import com.sidequestlab.floatingvoice.core.Destination;
import com.sidequestlab.floatingvoice.core.DestinationCatalog;
import com.sidequestlab.floatingvoice.core.DestinationScope;
import com.sidequestlab.floatingvoice.core.DispatchTargetSnapshot;
import com.sidequestlab.floatingvoice.core.GestureClassifier;
import com.sidequestlab.floatingvoice.core.InputMode;
import com.sidequestlab.floatingvoice.core.InputOutputPolicy;
import com.sidequestlab.floatingvoice.core.OverlayColorPreset;
import com.sidequestlab.floatingvoice.core.OverlayEvent;
import com.sidequestlab.floatingvoice.core.OverlayReflowPolicy;
import com.sidequestlab.floatingvoice.core.OverlayStateMachine;
import com.sidequestlab.floatingvoice.core.OutputRoute;
import com.sidequestlab.floatingvoice.core.OutputRouteStateMachine;
import com.sidequestlab.floatingvoice.core.OutputSnapshot;
import com.sidequestlab.floatingvoice.core.RouteStateMachine;

import java.io.File;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FloatingVoiceService extends Service implements TelegramRepository.Listener {
    private static volatile boolean running;
    public static final String ACTION_START = "com.sidequestlab.floatingvoice.START_OVERLAY";
    public static final String ACTION_STOP = "com.sidequestlab.floatingvoice.STOP_OVERLAY";
    public static final String ACTION_RUNNING_STATE_CHANGED =
            "com.sidequestlab.floatingvoice.RUNNING_STATE_CHANGED";
    public static final String ACTION_SERVICE_TEARDOWN =
            "com.sidequestlab.floatingvoice.SERVICE_TEARDOWN";
    public static final String ACTION_COMPOSER_CLOSED =
            "com.sidequestlab.floatingvoice.COMPOSER_CLOSED";
    public static final String ACTION_COMPOSER_SUBMIT =
            "com.sidequestlab.floatingvoice.COMPOSER_SUBMIT";
    public static final String ACTION_SPEECH_TELEGRAM_SUBMIT =
            "com.sidequestlab.floatingvoice.SPEECH_TELEGRAM_SUBMIT";
    public static final String ACTION_SPEECH_TELEGRAM_RESULT =
            "com.sidequestlab.floatingvoice.SPEECH_TELEGRAM_RESULT";
    public static final String ACTION_SPEECH_REVIEW_CLOSED =
            "com.sidequestlab.floatingvoice.SPEECH_REVIEW_CLOSED";
    public static final String ACTION_DESTINATION_PICKED =
            "com.sidequestlab.floatingvoice.DESTINATION_PICKED";
    public static final String ACTION_DESTINATION_PICKER_CLOSED =
            "com.sidequestlab.floatingvoice.DESTINATION_PICKER_CLOSED";
    public static final String EXTRA_COMPOSER_TEXT = "composer_text";
    public static final String EXTRA_SPEECH_TELEGRAM_RESULT = "speech_telegram_result";
    public static final String EXTRA_SPEECH_TELEGRAM_DETAIL = "speech_telegram_detail";
    public static final String EXTRA_SPEECH_TELEGRAM_HANDOFF_ID =
            "speech_telegram_handoff_id";
    public static final int SPEECH_TELEGRAM_QUEUED = 1;
    public static final int SPEECH_TELEGRAM_DELIVERED = 2;
    public static final int SPEECH_TELEGRAM_REJECTED = 3;
    public static final String EXTRA_DESTINATION_PICKER_REQUEST_ID =
            "destination_picker_request_id";
    public static final String EXTRA_DESTINATION_SCOPE = "destination_scope";
    public static final String EXTRA_DESTINATION_LOCAL_ID = "destination_local_id";
    public static final String EXTRA_INPUT_MODE = "input_mode";
    public static final String EXTRA_OUTPUT_ROUTE = "output_route";
    public static final String EXTRA_OPEN_OUTPUT_CHOOSER = "open_output_chooser";
    static final int COMPOSER_SUBMIT_REJECTED = 0;
    static final int COMPOSER_SUBMIT_ACCEPTED = 1;
    private static final int NOTIFICATION_ID = 41;
    private static final String CHANNEL_ID = "floatingvoice_overlay";
    private static final int TOUCH_MOVEMENT_THRESHOLD_DP = 12;
    private static final long TOUCH_LONG_PRESS_THRESHOLD_MS = 600L;
    private static final long PREREQUISITE_CHECK_MS = 1_000L;
    private static final long PICKER_RESTORE_INPUT_SUPPRESSION_MS = 300L;
    private static final long ACCOUNT_ROUTE_GRACE_MS = 5_000L;

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
    private int currentFabSizePx;
    private boolean recordingStopOnRight;
    private MediaRecorder recorder;
    private File activeRecording;
    private File readyVoiceRecording;
    private DispatchTargetSnapshot readyVoiceTarget;
    private int readyVoiceDuration;
    private long readyVoiceAttemptId;
    private OutputRoute activeRecordingRoute;
    private OutputSnapshot readyVoiceOutput;
    private String readyText;
    private DispatchTargetSnapshot readyTextTarget;
    private long readyTextAttemptId;
    private long readyTextHandoffId;
    private boolean speechReviewVisible;
    private long recordingStartedAt;
    private TelegramRepository telegram;
    private AudioCaptureCoordinator audioCaptureCoordinator;
    private AudioCaptureOwnership.Lease recordingLease;
    private volatile RouteStateMachine routeStateMachine;
    private long destinationPickerRequestId;
    private DestinationScope pendingDestinationScope;
    private InputMode pendingOutputInputMode;
    private boolean destinationPickerOpen;
    private boolean discardNextOneOnVoiceStartFailure;
    private boolean composerAlternativeRequested;
    private long accountRouteGraceDeadline;
    private String notificationText;
    private int notificationResourceId = R.string.notification_ready;
    private Object[] notificationArguments = new Object[0];
    private boolean primaryOverlayAttached;
    private BroadcastReceiver composerClosedReceiver;
    private OverlayUiPreferences overlayUiPreferences;
    private SharedPreferences.OnSharedPreferenceChangeListener uiPreferenceListener;
    private Handler mainHandler;
    private Runnable prerequisiteMonitor;
    private ArchiveSettingsStore archiveSettingsStore;
    private LocalArchiveController localArchiveController;
    private RetainedAudioShareStore retainedAudioShareStore;
    private ExecutorService archiveExecutor;

    @Override public void onCreate() {
        super.onCreate();
        running = false;
        mainHandler = new Handler(Looper.getMainLooper());
        archiveSettingsStore = new ArchiveSettingsStore(this);
        localArchiveController = new LocalArchiveController();
        archiveExecutor = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "local-ogg-archive"));
        prerequisiteMonitor = () -> {
            if (isTearingDown()) return;
            if (!prerequisitesAvailable()) {
                updateState(R.string.service_prerequisite_lost);
                stopSelf();
                return;
            }
            mainHandler.postDelayed(prerequisiteMonitor, PREREQUISITE_CHECK_MS);
        };
        FloatingVoiceApp app = (FloatingVoiceApp) getApplication();
        telegram = app.telegram();
        audioCaptureCoordinator = app.audioCaptureCoordinator();
        retainedAudioShareStore = app.retainedAudioShares();
        initializeRouteState(telegram.destinationCatalog());
        telegram.addListener(this);
        overlayUiPreferences = new OverlayUiPreferences(this);
        currentFabSizePx = dp(overlayUiPreferences.sizePreset().sizeDp());
        uiPreferenceListener = (preferences, key) -> {
            if (isTearingDown()) return;
            if (OverlayUiPreferences.KEY_COLOR.equals(key)) {
                applyIdleColor();
                return;
            }
            if (!OverlayUiPreferences.KEY_SIZE.equals(key)) return;
            OverlayStateMachine.State state = overlayStateMachine.state();
            if (!showsIdleBubble(state) && state != OverlayStateMachine.State.MENU_OPEN) return;
            if (state == OverlayStateMachine.State.MENU_OPEN) {
                dispatchOverlayEvent(OverlayEvent.GESTURE_CANCELED);
            }
            currentFabSizePx = dp(overlayUiPreferences.sizePreset().sizeDp());
            if (overlayViewController != null) {
                overlayViewController.setIdleSize(currentFabSizePx);
                showIdleOverlay();
            }
        };
        overlayUiPreferences.register(uiPreferenceListener);
        composerClosedReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null || isTearingDown()) return;
                if (ACTION_DESTINATION_PICKED.equals(intent.getAction())) {
                    handleDestinationPicked(intent);
                    return;
                }
                if (ACTION_DESTINATION_PICKER_CLOSED.equals(intent.getAction())) {
                    handleDestinationPickerClosed(intent);
                    return;
                }
                if (ACTION_COMPOSER_SUBMIT.equals(intent.getAction())) {
                    if (prepareComposerTelegramSubmit(intent)) {
                        setResultCode(COMPOSER_SUBMIT_ACCEPTED);
                        dispatchOverlayEvent(OverlayEvent.SUBMIT_TEXT);
                    } else {
                        setResultCode(COMPOSER_SUBMIT_REJECTED);
                    }
                    return;
                }
                if (ACTION_SPEECH_TELEGRAM_SUBMIT.equals(intent.getAction())) {
                    if (prepareSpeechTelegramSubmit(intent)) {
                        setResultCode(COMPOSER_SUBMIT_ACCEPTED);
                        dispatchOverlayEvent(OverlayEvent.SUBMIT_TEXT);
                    } else {
                        setResultCode(COMPOSER_SUBMIT_REJECTED);
                    }
                    return;
                }
                if (ACTION_SPEECH_REVIEW_CLOSED.equals(intent.getAction())) {
                    speechReviewVisible = false;
                    OverlayStateMachine.State state = overlayStateMachine.state();
                    if (state == OverlayStateMachine.State.SPEECH_REVIEW_OPEN
                            || state == OverlayStateMachine.State.SPEECH_REVIEW_OPENING) {
                        dispatchOverlayEvent(OverlayEvent.CLOSE_SPEECH_REVIEW);
                    }
                    restorePrimaryOverlay();
                    return;
                }
                if (!ACTION_COMPOSER_CLOSED.equals(intent.getAction())) return;
                if (overlayStateMachine.state() == OverlayStateMachine.State.TEXT_COMPOSING) {
                    dispatchOverlayEvent(OverlayEvent.CLOSE_COMPOSER);
                }
                restorePrimaryOverlay();
            }
        };
        IntentFilter composerFilter = new IntentFilter(ACTION_COMPOSER_CLOSED);
        composerFilter.addAction(ACTION_COMPOSER_SUBMIT);
        composerFilter.addAction(ACTION_SPEECH_TELEGRAM_SUBMIT);
        composerFilter.addAction(ACTION_SPEECH_REVIEW_CLOSED);
        composerFilter.addAction(ACTION_DESTINATION_PICKED);
        composerFilter.addAction(ACTION_DESTINATION_PICKER_CLOSED);
        ContextCompat.registerReceiver(this, composerClosedReceiver, composerFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        createNotificationChannel();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mainHandler == null || isTearingDown()) return;
        mainHandler.post(this::reflowVisibleOverlay);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!prerequisitesAvailable()) {
            updateRunningState(false);
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
            updateRunningState(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (primaryOverlay == null) addPrimaryOverlay();
        updateRunningState(primaryOverlay != null && primaryOverlayAttached);
        if (running) {
            mainHandler.removeCallbacks(prerequisiteMonitor);
            mainHandler.postDelayed(prerequisiteMonitor, PREREQUISITE_CHECK_MS);
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        updateRunningState(false);
        overlayStateMachine.accept(OverlayEvent.TEARDOWN);
        if (mainHandler != null && prerequisiteMonitor != null) {
            mainHandler.removeCallbacks(prerequisiteMonitor);
        }
        if (composerClosedReceiver != null) {
            try { unregisterReceiver(composerClosedReceiver); }
            catch (IllegalArgumentException ignored) { }
            composerClosedReceiver = null;
        }
        sendBroadcast(new Intent(ACTION_SERVICE_TEARDOWN).setPackage(getPackageName()));
        if (overlayUiPreferences != null && uiPreferenceListener != null) {
            overlayUiPreferences.unregister(uiPreferenceListener);
            uiPreferenceListener = null;
        }
        if (dragTapListener != null) dragTapListener.cancelPending();
        if (actionMenuController != null) actionMenuController.destroy();
        if (overlayViewController != null) overlayViewController.destroy();
        primaryOverlay = null;
        actionMenuController = null;
        overlayViewController = null;
        dragTapListener = null;
        if (recorder != null) stopAndRetainInterruptedRecording();
        if (recorder == null) releaseRecordingOwnership();
        if (archiveExecutor != null) archiveExecutor.shutdownNow();
        audioCaptureCoordinator = null;
        if (windowRegistry != null) windowRegistry.removeAllWithRetries(3);
        primaryOverlayAttached = false;
        telegram.removeListener(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    public static boolean isRunning() { return running; }

    private void updateRunningState(boolean nextRunning) {
        running = nextRunning;
        sendBroadcast(new Intent(ACTION_RUNNING_STATE_CHANGED).setPackage(getPackageName()));
    }

    private boolean prerequisitesAvailable() {
        boolean microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean notification = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean authenticated = telegram != null
                && telegram.authStage() == TelegramRepository.AuthStage.READY;
        boolean routeReady = routeStateMachine != null;
        boolean accountStillResolving = authenticated && telegram.authenticatedAccountUserId() == 0L
                && SystemClock.uptimeMillis() <= accountRouteGraceDeadline;
        return OverlayCapabilityPolicy.evaluate(
                microphone, notification, Settings.canDrawOverlays(this),
                authenticated, routeReady, accountStillResolving).serviceAvailable();
    }

    private boolean telegramOutputAvailable() {
        boolean authenticated = telegram != null
                && telegram.authStage() == TelegramRepository.AuthStage.READY;
        boolean routeReady = routeStateMachine != null;
        boolean accountStillResolving = authenticated && telegram.authenticatedAccountUserId() == 0L
                && SystemClock.uptimeMillis() <= accountRouteGraceDeadline;
        return OverlayCapabilityPolicy.evaluate(
                true, true, true, authenticated, routeReady,
                accountStillResolving).telegramOutputAvailable();
    }

    @Override public void onAuthStage(TelegramRepository.AuthStage stage) {
        if (stage == TelegramRepository.AuthStage.READY) {
            // READY precedes the asynchronous GetMe account identity. Drop every route session
            // now so a previous account/default/one-shot cannot remain visible or start capture.
            routeStateMachine = null;
            accountRouteGraceDeadline = SystemClock.uptimeMillis() + ACCOUNT_ROUTE_GRACE_MS;
            initializeRouteState(telegram.destinationCatalog());
        } else {
            routeStateMachine = null;
            accountRouteGraceDeadline = 0L;
            boolean recordingRetained = stopActiveRecordingForLostTelegramRoute();
            updateDestinationChip();
            if (!recordingRetained) updateState(R.string.telegram_target_required_first);
        }
    }

    @Override public void onAccountChanged(String account) {
        long accountUserId = telegram.authenticatedAccountUserId();
        RouteStateMachine current = routeStateMachine;
        if (current != null && !current.matchesAuthenticatedAccount(accountUserId)) {
            routeStateMachine = null;
            accountRouteGraceDeadline = 0L;
            boolean recordingRetained = stopActiveRecordingForLostTelegramRoute();
            if (!recordingRetained) updateState(R.string.telegram_target_required_first);
        }
        if (routeStateMachine == null) initializeRouteState(telegram.destinationCatalog());
    }

    @Override public void onTargetChanged(TargetChat target) {
        // Legacy single-target changes do not own V7 routing.
    }

    @Override public void onDestinationCatalogChanged(DestinationCatalog catalog) {
        RouteStateMachine current = routeStateMachine;
        if (current == null) {
            initializeRouteState(catalog);
            return;
        }
        try {
            current.replaceCatalog(catalog);
            updateDestinationChip();
        } catch (IllegalArgumentException unsafeRebind) {
            routeStateMachine = null;
            accountRouteGraceDeadline = 0L;
            boolean recordingRetained = stopActiveRecordingForLostTelegramRoute();
            if (!recordingRetained) updateState(R.string.destination_selection_rejected);
            updateDestinationChip();
        }
    }

    @SuppressLint("RtlHardcoded") // x/y are physical display coordinates, not logical start/end.
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
                if (!isTearingDown()
                        && !terminalInputGate.shouldSuppress(SystemClock.uptimeMillis())) {
                    dispatchOverlayEvent(OverlayEvent.TAP);
                }
            }

            @Override public void onCancel() {
                if (!isTearingDown()
                        && !terminalInputGate.shouldSuppress(SystemClock.uptimeMillis())) {
                    dispatchOverlayEvent(OverlayEvent.CANCEL_VOICE_REQUESTED);
                }
            }

            @Override public void onChooseDestination() {
                if (!isTearingDown()
                        && !terminalInputGate.shouldSuppress(SystemClock.uptimeMillis())) {
                    openDestinationPicker(DestinationScope.CURRENT_RECORDING);
                }
            }
        });
        actionMenuController = new FloatingActionMenuController(
                this, windowRegistry, new FloatingActionMenuController.Listener() {
            @Override public void onSendVoice() {
                if (isTearingDown()) return;
                dispatchOverlayEvent(OverlayEvent.START_TELEGRAM_RECORDING);
            }

            @Override public void onChooseVoiceOutput() {
                if (isTearingDown()) return;
                openVoiceOutputPicker();
            }

            @Override public void onComposeText() {
                openComposer(false);
            }

            @Override public void onChooseTextOutput() {
                openComposer(true);
            }

            private void openComposer(boolean chooseOutput) {
                composerAlternativeRequested = chooseOutput;
                dispatchOverlayEvent(OverlayEvent.COMPOSE_TEXT);
            }

            @Override public void onSpeechText() {
                openSpeechTextReview();
            }

            @Override public void onChooseSpeechTextOutput() {
                // Speech review owns the one-operation Telegram destination and Android
                // Sharesheet controls, so the labeled alternative affordance opens it directly.
                openSpeechTextReview();
            }

            private void openSpeechTextReview() {
                if (isTearingDown() || recorder != null || audioCaptureCoordinator == null
                        || audioCaptureCoordinator.owner()
                        != AudioCaptureOwnership.Owner.NONE) {
                    dispatchOverlayEvent(OverlayEvent.GESTURE_CANCELED);
                    return;
                }
                dispatchOverlayEvent(OverlayEvent.OPEN_SPEECH_REVIEW);
            }

            @Override public void onDismissRequested() {
                terminalInputGate.suppressTouchesThrough(
                        SystemClock.uptimeMillis() + 200L);
                dispatchOverlayEvent(OverlayEvent.GESTURE_CANCELED);
            }
        });
        primaryOverlay = overlayViewController.root();
        overlayViewController.setIdleSize(currentFabSizePx);
        applyIdleColor();
        int size = currentFabSizePx;
        layoutParams = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
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
                case START_VOICE -> startRecording(OutputRoute.TELEGRAM_VOICE);
                case START_LOCAL_VOICE -> startRecording(OutputRoute.LOCAL_AUDIO_ARCHIVE);
                case START_SYSTEM_AUDIO_VOICE -> startRecording(OutputRoute.SYSTEM_AUDIO_SHARE);
                case STOP_VOICE -> stopRecordingAndSend();
                case CANCEL_VOICE -> cancelRecording();
                case SEND_VOICE -> sendReadyVoice();
                case OPEN_AUDIO_SHARE_CHOOSER -> sendReadyVoice();
                case SHOW_MENU -> showActionMenu();
                case HIDE_MENU -> hideActionMenu();
                case OPEN_TEXT_COMPOSER -> openTextComposer();
                case OPEN_SPEECH_REVIEW -> openSpeechReview();
                case RESTORE_PRIMARY_OVERLAY -> restorePrimaryOverlay();
                case SEND_TEXT -> sendReadyText();
            }
        }
        return transition;
    }

    private void startRecording(OutputRoute requestedRoute) {
        boolean localArchive = requestedRoute == OutputRoute.LOCAL_AUDIO_ARCHIVE;
        boolean systemAudioShare = requestedRoute == OutputRoute.SYSTEM_AUDIO_SHARE;
        boolean standaloneOutput = localArchive || systemAudioShare;
        if ((!standaloneOutput && !telegramOutputAvailable())
                || (localArchive && archiveSettingsStore.selection().status()
                != ArchiveSettingsStore.Status.READY)) {
            discardPendingVoiceOverride();
            dispatchOverlayEvent(OverlayEvent.VOICE_START_FAILED);
            updateState(localArchive ? R.string.archive_folder_reselection_required
                    : R.string.destination_required_before_recording);
            return;
        }
        AudioCaptureOwnership.Lease acquired = audioCaptureCoordinator == null
                ? null : audioCaptureCoordinator.startRecording().orElse(null);
        if (acquired == null) {
            discardPendingVoiceOverride();
            dispatchOverlayEvent(OverlayEvent.VOICE_START_FAILED);
            return;
        }
        recordingLease = acquired;
        RouteStateMachine route = routeStateMachine;
        Destination selectedDestination = standaloneOutput || route == null
                ? null : route.startRecording().orElse(null);
        if (!standaloneOutput && selectedDestination == null) {
            discardPendingVoiceOverride();
            releaseRecordingOwnership();
            dispatchOverlayEvent(OverlayEvent.VOICE_START_FAILED);
            updateState(R.string.destination_required_before_recording);
            return;
        }
        discardNextOneOnVoiceStartFailure = false;
        if (systemAudioShare) {
            activeRecording = retainedAudioShareStore.createPending(System.currentTimeMillis());
            if (activeRecording == null) {
                releaseRecordingOwnership();
                dispatchOverlayEvent(OverlayEvent.VOICE_START_FAILED);
                updateState(R.string.recording_folder_failed);
                return;
            }
        } else {
            File externalMusic = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            File root = new File(externalMusic == null ? getFilesDir() : externalMusic,
                    "voice_notes");
            if (!root.mkdirs() && !root.isDirectory()) {
                if (!standaloneOutput && route != null) route.cancelRecording();
                releaseRecordingOwnership();
                dispatchOverlayEvent(OverlayEvent.VOICE_START_FAILED);
                updateState(R.string.recording_folder_failed);
                return;
            }
            activeRecording = new File(root, "voice-"
                    + DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-') + ".ogg");
        }
        OutputSnapshot standaloneSnapshot = null;
        if (standaloneOutput) {
            OutputRouteStateMachine output = new OutputRouteStateMachine(
                    OutputRoute.ContentKind.AUDIO, 0L, DestinationCatalog.empty(), null);
            if (output.selectRoute(requestedRoute)) {
                standaloneSnapshot = output.freeze(activeRecording.getAbsolutePath()).orElse(null);
            }
            if (standaloneSnapshot == null) {
                releaseRecordingOwnership();
                if (systemAudioShare) retainedAudioShareStore.releaseActive(activeRecording);
                activeRecording = null;
                dispatchOverlayEvent(OverlayEvent.VOICE_START_FAILED);
                return;
            }
        }
        activeRecordingRoute = requestedRoute;
        readyVoiceOutput = standaloneSnapshot;
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
            boolean released = next == null || tryReleaseRecorder(next);
            recorder = released ? null : next;
            if (!standaloneOutput && route != null) route.cancelRecording();
            File failedRecording = activeRecording;
            if (RecordingRoutePolicy.releaseShareStoreOwnership(requestedRoute, released)) {
                retainedAudioShareStore.releaseActive(failedRecording);
            }
            if (released) {
                releaseRecordingOwnership();
                activeRecording = null;
                activeRecordingRoute = null;
                readyVoiceOutput = null;
            }
            boolean cleaned = released && (failedRecording == null
                    || !failedRecording.exists() || failedRecording.delete());
            dispatchOverlayEvent(OverlayEvent.VOICE_START_FAILED);
            updateState(cleaned ? R.string.recording_start_failed
                    : R.string.recording_start_failed_retained_private, e.getMessage());
            return;
        }

        recorder = next;
        recordingStartedAt = SystemClock.elapsedRealtime();
        dispatchOverlayEvent(OverlayEvent.VOICE_START_SUCCEEDED);
        if (!standaloneOutput) updateDestinationChip();
        boolean dockShown = showRecordingDock();
        if (isRecordingState()) {
            updateState(dockShown
                    ? (localArchive ? R.string.local_recording_in_progress
                    : systemAudioShare ? R.string.audio_share_recording_in_progress
                    : R.string.recording_in_progress)
                    : R.string.recording_in_progress_cancel_unavailable);
        }
    }

    private void stopRecordingAndSend() {
        hideActionMenu();
        terminalInputGate.suppressTouchesThrough(
                SystemClock.uptimeMillis() + TERMINAL_INPUT_SUPPRESSION_MS);
        RouteStateMachine route = routeStateMachine;
        boolean localArchive = activeRecordingRoute == OutputRoute.LOCAL_AUDIO_ARCHIVE;
        boolean systemAudioShare = activeRecordingRoute == OutputRoute.SYSTEM_AUDIO_SHARE;
        boolean standaloneOutput = localArchive || systemAudioShare;
        boolean routeFreezing = !standaloneOutput && route != null && route.beginFreezing();
        long routeAttemptId = route == null ? 0L : route.routeAttemptId();
        MediaRecorder current = recorder;
        recorder = null;
        int duration = (int) Math.max(1,
                (SystemClock.elapsedRealtime() - recordingStartedAt + 999) / 1000);
        boolean stopped = false;
        if (current != null) {
            try {
                current.stop();
                stopped = true;
            } catch (RuntimeException ignored) { }
        }
        boolean released = current == null || tryReleaseRecorder(current);
        recorder = released ? null : current;
        if (released) releaseRecordingOwnership();
        if (!stopped || !released) {
            if (routeFreezing) route.abortFreezing(routeAttemptId);
            if (RecordingRoutePolicy.releaseShareStoreOwnership(
                    activeRecordingRoute, released)) {
                retainedAudioShareStore.releaseActive(activeRecording);
            }
            dispatchOverlayEvent(OverlayEvent.VOICE_STOP_FAILED);
            updateIdleBubble();
            updateState(standaloneOutput ? R.string.recording_stop_failed_retained_private
                    : R.string.recording_stop_failed_retained, activeRecording);
            return;
        }

        if (systemAudioShare) {
            File completedShare = retainedAudioShareStore.promoteCompleted(activeRecording);
            if (completedShare == null) {
                activeRecording = null;
                activeRecordingRoute = null;
                readyVoiceOutput = null;
                dispatchOverlayEvent(OverlayEvent.VOICE_STOP_FAILED);
                updateIdleBubble();
                updateState(R.string.audio_share_promotion_failed_retained);
                return;
            }
            activeRecording = completedShare;
        }

        DispatchTargetSnapshot frozen = routeFreezing
                ? route.freeze().orElse(null) : null;
        if (!standaloneOutput && frozen == null) {
            if (routeFreezing) route.abortFreezing(routeAttemptId);
            File retained = activeRecording;
            activeRecording = null;
            dispatchOverlayEvent(OverlayEvent.VOICE_STOP_FAILED);
            updateIdleBubble();
            updateState(R.string.destination_invalid_recording_retained,
                    retained == null ? "" : retained.getAbsolutePath());
            return;
        }

        readyVoiceRecording = activeRecording;
        readyVoiceTarget = frozen;
        readyVoiceDuration = duration;
        readyVoiceAttemptId = overlayStateMachine.attemptId();
        activeRecording = null;
        updateIdleBubble();
        dispatchOverlayEvent(localArchive ? OverlayEvent.LOCAL_VOICE_STOP_SUCCEEDED
                : systemAudioShare ? OverlayEvent.SYSTEM_AUDIO_SHARE_STOP_SUCCEEDED
                : OverlayEvent.VOICE_STOP_SUCCEEDED);
    }

    private void cancelRecording() {
        hideActionMenu();
        terminalInputGate.suppressTouchesThrough(
                SystemClock.uptimeMillis() + TERMINAL_INPUT_SUPPRESSION_MS);
        MediaRecorder current = recorder;
        recorder = null;
        File canceledRecording = activeRecording;
        activeRecording = null;
        RouteStateMachine route = routeStateMachine;
        if (route != null && activeRecordingRoute == OutputRoute.TELEGRAM_VOICE) {
            route.cancelRecording();
        }

        RecordingCancelOperation.Result result = cancelOperation.execute(
                recorderPort(current), canceledRecording, filePort());
        if (RecordingRoutePolicy.releaseShareStoreOwnership(
                activeRecordingRoute, !result.releaseFailed())) {
            retainedAudioShareStore.releaseActive(canceledRecording);
        }
        if (result.releaseFailed()) {
            recorder = current;
            activeRecording = canceledRecording;
        } else {
            releaseRecordingOwnership();
        }

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
        if (!result.releaseFailed()) {
            activeRecordingRoute = null;
            readyVoiceOutput = null;
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
        return showRecordingDock(true);
    }

    private boolean showRecordingDock(boolean captureIdleAnchor) {
        if (overlayViewController == null || primaryOverlay == null || layoutParams == null
                || windowRegistry == null || !primaryOverlayAttached) {
            cancelRecordingFallback();
            return false;
        }
        hideActionMenu();
        if (captureIdleAnchor) {
            idleAnchorX = layoutParams.x;
            idleAnchorY = layoutParams.y;
        }
        int dockWidth = px(R.dimen.overlay_dock_width);
        int dockHeight = px(R.dimen.overlay_dock_height);
        int stopSize = px(R.dimen.overlay_stop_size);
        int dockPadding = dp(4);
        layoutParams.width = dockWidth;
        layoutParams.height = dockHeight;
        Rect display = currentDisplayBounds();
        int minX = display.left;
        int maxX = Math.max(minX, display.right - dockWidth);
        int idleCenterX = idleAnchorX + currentFabSizePx / 2;
        int stopCenterOffsetLeft = dockPadding + stopSize / 2;
        int stopCenterOffsetRight = dockWidth - dockPadding - stopSize / 2;
        int extendRightX = idleCenterX - stopCenterOffsetLeft;
        int extendLeftX = idleCenterX - stopCenterOffsetRight;
        boolean stopOnRight = extendRightX + dockWidth > display.right;
        recordingStopOnRight = stopOnRight;
        layoutParams.x = clamp(stopOnRight ? extendLeftX : extendRightX, minX, maxX);
        int minY = display.top;
        int maxY = Math.max(minY, display.bottom - dockHeight);
        layoutParams.y = clamp(idleAnchorY + currentFabSizePx / 2 - dockHeight / 2,
                minY, maxY);
        overlayViewController.showRecording(recordingStartedAt, stopOnRight);
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
        if (overlayUiPreferences != null) {
            currentFabSizePx = dp(overlayUiPreferences.sizePreset().sizeDp());
        }
        overlayViewController.showIdle();
        overlayViewController.setIdleSize(currentFabSizePx);
        applyIdleColor();
        layoutParams.width = currentFabSizePx;
        layoutParams.height = currentFabSizePx;
        Rect display = currentDisplayBounds();
        int margin = px(R.dimen.overlay_safe_margin);
        int minX = display.left + margin;
        int minY = display.top + margin;
        layoutParams.x = clamp(idleAnchorX, minX,
                Math.max(minX, display.right - margin - layoutParams.width));
        layoutParams.y = clamp(idleAnchorY, minY,
                Math.max(minY, display.bottom - margin - layoutParams.height));
        idleAnchorX = layoutParams.x;
        idleAnchorY = layoutParams.y;
        try { windowRegistry.update(primaryOverlay, layoutParams); }
        catch (RuntimeException ignored) { }
    }

    private void reflowVisibleOverlay() {
        if (isTearingDown() || !primaryOverlayAttached || layoutParams == null) return;
        switch (OverlayReflowPolicy.actionFor(overlayStateMachine.state())) {
            case REFLOW_IDLE -> showIdleOverlay();
            case REFLOW_RECORDING -> showRecordingDock(false);
            case CLOSE_MENU_AND_REFLOW_IDLE -> {
                dispatchOverlayEvent(OverlayEvent.GESTURE_CANCELED);
                showIdleOverlay();
            }
            case NONE -> {
                // Transient and hidden states have no visible window to reflow.
            }
        }
    }

    private void showActionMenu() {
        if (actionMenuController == null || layoutParams == null) return;
        actionMenuController.setDefaultOutputSummary(defaultDestinationSummary());
        Rect display = currentDisplayBounds();
        int panelWidth = px(R.dimen.overlay_palette_width);
        int margin = px(R.dimen.overlay_safe_margin);
        int maxPanelHeight = display.height() - 2 * margin;
        if (maxPanelHeight <= 0) {
            dispatchOverlayEvent(OverlayEvent.GESTURE_CANCELED);
            return;
        }
        int panelHeight = Math.min(px(R.dimen.overlay_palette_height), maxPanelHeight);
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

    private void openArchiveFolderPicker() {
        Intent picker = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_ARCHIVE_PICKER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { startActivity(picker); } catch (RuntimeException ignored) { }
    }

    private void openTextComposer() {
        boolean chooseOutput = composerAlternativeRequested;
        composerAlternativeRequested = false;
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_ANIMATION;
        Intent composer = new Intent(this, TextComposerActivity.class)
                .putExtra(EXTRA_OPEN_OUTPUT_CHOOSER, chooseOutput)
                .addFlags(flags);
        try {
            startActivity(composer);
            hidePrimaryOverlay();
        } catch (RuntimeException ignored) {
            dispatchOverlayEvent(OverlayEvent.CLOSE_COMPOSER);
            restorePrimaryOverlay();
        }
    }

    private void openSpeechReview() {
        Intent review = new Intent(this, SpeechReviewActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(review);
            speechReviewVisible = true;
            dispatchOverlayEvent(OverlayEvent.SPEECH_REVIEW_OPENED);
            hidePrimaryOverlay();
        } catch (RuntimeException ignored) {
            dispatchOverlayEvent(OverlayEvent.SPEECH_REVIEW_LAUNCH_FAILED);
            restorePrimaryOverlay();
        }
    }

    private synchronized void initializeRouteState(DestinationCatalog catalog) {
        if (routeStateMachine != null || telegram == null || catalog == null) return;
        long accountUserId = telegram.authenticatedAccountUserId();
        boolean authReady = telegram.authStage() == TelegramRepository.AuthStage.READY;
        if (!RouteInitializationPolicy.allowed(authReady, accountUserId)) {
            if (!authReady) {
                accountRouteGraceDeadline = 0L;
                return;
            }
            if (accountRouteGraceDeadline == 0L) {
                accountRouteGraceDeadline = SystemClock.uptimeMillis() + ACCOUNT_ROUTE_GRACE_MS;
            }
            return;
        }
        String defaultLocalId = catalog.defaultLocalId()
                .filter(localId -> catalog.selectable(localId, accountUserId).isPresent())
                .orElse(null);
        try {
            routeStateMachine = new RouteStateMachine(
                    accountUserId, catalog, defaultLocalId);
            accountRouteGraceDeadline = 0L;
        } catch (IllegalArgumentException invalidCatalog) {
            routeStateMachine = null;
        }
    }

    private void openVoiceOutputPicker() {
        RouteStateMachine route = routeStateMachine;
        if (route == null || destinationPickerOpen
                || overlayStateMachine.state() != OverlayStateMachine.State.MENU_OPEN
                || route.phase() != RouteStateMachine.Phase.IDLE) {
            return;
        }
        long requestId = ++destinationPickerRequestId;
        pendingDestinationScope = null;
        pendingOutputInputMode = InputMode.RAW_VOICE;
        destinationPickerOpen = true;
        terminalInputGate.suppressTouchesThrough(
                SystemClock.uptimeMillis() + TERMINAL_INPUT_SUPPRESSION_MS);
        hideActionMenu();
        if (primaryOverlay != null) primaryOverlay.setVisibility(View.INVISIBLE);

        Intent picker = new Intent(this, DestinationPickerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_HISTORY
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_DESTINATION_PICKER_REQUEST_ID, requestId)
                .putExtra(EXTRA_INPUT_MODE, InputMode.RAW_VOICE.name());
        route.defaultLocalId().ifPresent(localId ->
                picker.putExtra(EXTRA_DESTINATION_LOCAL_ID, localId));
        try {
            startActivity(picker);
        } catch (RuntimeException launchFailure) {
            destinationPickerOpen = false;
            pendingOutputInputMode = null;
            dispatchOverlayEvent(OverlayEvent.GESTURE_CANCELED);
            restoreAfterDestinationPicker();
            updateState(R.string.destination_selection_rejected);
        }
    }

    private void openDestinationPicker(DestinationScope scope) {
        RouteStateMachine route = routeStateMachine;
        if (route == null || destinationPickerOpen) return;
        boolean phaseAllowed = (scope == DestinationScope.DEFAULT
                || scope == DestinationScope.NEXT_ONE)
                ? route.phase() == RouteStateMachine.Phase.IDLE
                : scope == DestinationScope.CURRENT_RECORDING
                && route.phase() == RouteStateMachine.Phase.RECORDING;
        if (!phaseAllowed) {
            updateState(R.string.destination_selection_rejected);
            return;
        }

        String selectedLocalId = scope == DestinationScope.CURRENT_RECORDING
                ? route.currentDestination().map(Destination::localId).orElse(null)
                : scope == DestinationScope.DEFAULT
                ? route.defaultLocalId().orElse(null)
                : route.nextOneLocalId().orElseGet(
                        () -> route.defaultLocalId().orElse(null));
        long requestId = ++destinationPickerRequestId;
        pendingDestinationScope = scope;
        pendingOutputInputMode = null;
        destinationPickerOpen = true;
        terminalInputGate.suppressTouchesThrough(
                SystemClock.uptimeMillis() + TERMINAL_INPUT_SUPPRESSION_MS);
        hideActionMenu();
        if (primaryOverlay != null) primaryOverlay.setVisibility(View.INVISIBLE);

        Intent picker = new Intent(this, DestinationPickerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_HISTORY
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_DESTINATION_PICKER_REQUEST_ID, requestId)
                .putExtra(EXTRA_DESTINATION_SCOPE, scope.name());
        if (selectedLocalId != null) {
            picker.putExtra(EXTRA_DESTINATION_LOCAL_ID, selectedLocalId);
        }
        try {
            startActivity(picker);
        } catch (RuntimeException launchFailure) {
            destinationPickerOpen = false;
            pendingDestinationScope = null;
            restoreAfterDestinationPicker();
            updateState(R.string.destination_selection_rejected);
        }
    }

    private void handleDestinationPicked(Intent intent) {
        long requestId = intent.getLongExtra(EXTRA_DESTINATION_PICKER_REQUEST_ID, 0L);
        if (!destinationPickerOpen || requestId != destinationPickerRequestId) return;
        if (pendingOutputInputMode != null) {
            handleOneOperationOutputPicked(intent, pendingOutputInputMode);
            return;
        }
        String localId = intent.getStringExtra(EXTRA_DESTINATION_LOCAL_ID);
        DestinationScope scope = pendingDestinationScope;
        destinationPickerOpen = false;
        pendingDestinationScope = null;

        RouteStateMachine route = routeStateMachine;
        boolean accepted = false;
        if (route != null && scope != null && localId != null) {
            if (scope == DestinationScope.DEFAULT) {
                // Persist first. The repository publishes the updated catalog back to this
                // service; an in-memory-only default must never outlive a failed store write.
                accepted = telegram.setDefaultDestination(localId)
                        && route.select(DestinationScope.DEFAULT, localId);
            } else {
                accepted = route.select(scope, localId);
            }
        }
        restoreAfterDestinationPicker();
        if (!accepted) {
            updateState(R.string.destination_selection_rejected);
            return;
        }
        updateDestinationChip();
        updateState(scope == DestinationScope.DEFAULT
                        ? R.string.destination_default_selection_saved
                        : R.string.destination_selection_saved,
                destinationLabel(localId));
    }

    private void handleOneOperationOutputPicked(Intent intent, InputMode inputMode) {
        destinationPickerOpen = false;
        pendingDestinationScope = null;
        pendingOutputInputMode = null;
        String routeName = intent.getStringExtra(EXTRA_OUTPUT_ROUTE);
        OutputRoute outputRoute;
        try {
            outputRoute = OutputRoute.valueOf(routeName == null ? "" : routeName);
        } catch (IllegalArgumentException invalidRoute) {
            dispatchOverlayEvent(OverlayEvent.GESTURE_CANCELED);
            restoreAfterDestinationPicker();
            updateState(R.string.destination_selection_rejected);
            return;
        }
        if (!InputOutputPolicy.allows(inputMode, outputRoute)
                || inputMode != InputMode.RAW_VOICE) {
            dispatchOverlayEvent(OverlayEvent.GESTURE_CANCELED);
            restoreAfterDestinationPicker();
            updateState(R.string.destination_selection_rejected);
            return;
        }

        RouteStateMachine route = routeStateMachine;
        OverlayEvent startEvent = null;
        if (outputRoute == OutputRoute.TELEGRAM_VOICE) {
            String localId = intent.getStringExtra(EXTRA_DESTINATION_LOCAL_ID);
            if (route != null && localId != null
                    && route.select(DestinationScope.NEXT_ONE, localId)) {
                discardNextOneOnVoiceStartFailure = true;
                startEvent = OverlayEvent.START_TELEGRAM_RECORDING;
            }
        } else if (outputRoute == OutputRoute.SYSTEM_AUDIO_SHARE) {
            startEvent = OverlayEvent.START_SYSTEM_AUDIO_SHARE_RECORDING;
        } else if (outputRoute == OutputRoute.LOCAL_AUDIO_ARCHIVE) {
            if (archiveSettingsStore.selection().status()
                    != ArchiveSettingsStore.Status.READY) {
                dispatchOverlayEvent(OverlayEvent.GESTURE_CANCELED);
                restoreAfterDestinationPicker();
                updateState(R.string.archive_folder_reselection_required);
                openArchiveFolderPicker();
                return;
            }
            startEvent = OverlayEvent.START_LOCAL_RECORDING;
        }

        restoreAfterDestinationPicker();
        if (startEvent == null) {
            dispatchOverlayEvent(OverlayEvent.GESTURE_CANCELED);
            updateState(R.string.destination_selection_rejected);
            return;
        }
        OverlayStateMachine.Transition transition = dispatchOverlayEvent(startEvent);
        if (startEvent == OverlayEvent.START_TELEGRAM_RECORDING
                && transition.nextState() != OverlayStateMachine.State.VOICE_STARTING) {
            discardPendingVoiceOverride();
        }
    }

    private void discardPendingVoiceOverride() {
        if (!discardNextOneOnVoiceStartFailure) return;
        discardNextOneOnVoiceStartFailure = false;
        RouteStateMachine route = routeStateMachine;
        if (route != null) route.clearNextOne();
    }

    private void handleDestinationPickerClosed(Intent intent) {
        long requestId = intent.getLongExtra(EXTRA_DESTINATION_PICKER_REQUEST_ID, 0L);
        if (!destinationPickerOpen || requestId != destinationPickerRequestId) return;
        boolean outputPicker = pendingOutputInputMode != null;
        destinationPickerOpen = false;
        pendingDestinationScope = null;
        pendingOutputInputMode = null;
        if (outputPicker) dispatchOverlayEvent(OverlayEvent.GESTURE_CANCELED);
        restoreAfterDestinationPicker();
    }

    private void restoreAfterDestinationPicker() {
        View currentOverlay = primaryOverlay;
        if (currentOverlay == null || isTearingDown()) return;
        terminalInputGate.suppressTouchesThrough(SystemClock.uptimeMillis()
                + PICKER_RESTORE_INPUT_SUPPRESSION_MS);
        currentOverlay.setVisibility(View.VISIBLE);
        if (isRecordingState()) showRecordingDock(false);
        else showIdleOverlay();
    }

    private void updateDestinationChip() {
        RouteStateMachine route = routeStateMachine;
        View currentOverlay = primaryOverlay;
        if (route == null || currentOverlay == null) return;
        Destination current = route.currentDestination().orElse(null);
        if (current == null) return;
        String chip = text(R.string.destination_chip_current_recording,
                destinationLabel(current.localId()));
        currentOverlay.post(() -> {
            if (primaryOverlay == currentOverlay && overlayViewController != null
                    && !isTearingDown()) {
                overlayViewController.setDestinationChip(chip);
            }
        });
    }

    private String destinationLabel(String localId) {
        Destination destination = telegram.destinationCatalog().find(localId).orElse(null);
        if (destination == null) return localId == null ? "" : localId;
        if (!destination.userAlias().isBlank()) return destination.userAlias();
        if (!destination.resolvedTitle().isBlank()) return destination.resolvedTitle();
        return "@" + destination.resolvedUsername();
    }

    private String defaultDestinationSummary() {
        RouteStateMachine route = routeStateMachine;
        if (route == null) return text(R.string.overlay_default_output_unavailable);
        String defaultLocalId = route.defaultLocalId().orElse(null);
        if (defaultLocalId != null) {
            return text(R.string.destination_chip_default, destinationLabel(defaultLocalId));
        }
        return text(R.string.overlay_default_output_unavailable);
    }

    private void hidePrimaryOverlay() {
        View currentOverlay = primaryOverlay;
        if (currentOverlay == null || windowRegistry == null || !primaryOverlayAttached) return;
        OverlayStateMachine.State state = overlayStateMachine.state();
        if (state != OverlayStateMachine.State.TEXT_COMPOSING
                && state != OverlayStateMachine.State.SPEECH_REVIEW_OPENING
                && state != OverlayStateMachine.State.SPEECH_REVIEW_OPEN) return;
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

    private void applyIdleColor() {
        if (overlayViewController == null || overlayUiPreferences == null) return;
        int background = ContextCompat.getColor(
                this, overlayColorResource(overlayUiPreferences.colorPreset()));
        int foreground = ContextCompat.getColor(this, R.color.overlay_button_on_color);
        overlayViewController.setIdleColors(background, foreground);
    }

    private static int overlayColorResource(OverlayColorPreset preset) {
        return switch (preset) {
            case SAGE -> R.color.overlay_button_sage;
            case OCEAN -> R.color.overlay_button_ocean;
            case VIOLET -> R.color.overlay_button_violet;
            case AMBER -> R.color.overlay_button_amber;
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private void updateIdleAnchorFromPrimary() {
        if (layoutParams == null) return;
        int fabSize = currentFabSizePx;
        if (layoutParams.width > fabSize) {
            int dockWidth = px(R.dimen.overlay_dock_width);
            int dockHeight = px(R.dimen.overlay_dock_height);
            int stopSize = px(R.dimen.overlay_stop_size);
            int dockPadding = dp(4);
            int stopCenterOffset = recordingStopOnRight
                    ? dockWidth - dockPadding - stopSize / 2
                    : dockPadding + stopSize / 2;
            idleAnchorX = layoutParams.x + stopCenterOffset - fabSize / 2;
            idleAnchorY = layoutParams.y + dockHeight / 2 - fabSize / 2;
        } else {
            idleAnchorX = layoutParams.x;
            idleAnchorY = layoutParams.y;
        }
    }

    private static boolean showsIdleBubble(OverlayStateMachine.State state) {
        return state == OverlayStateMachine.State.IDLE
                || state == OverlayStateMachine.State.VOICE_QUEUEING
                || state == OverlayStateMachine.State.VOICE_PENDING
                || state == OverlayStateMachine.State.VOICE_ARCHIVING
                || state == OverlayStateMachine.State.TEXT_QUEUEING
                || state == OverlayStateMachine.State.TEXT_PENDING;
    }

    private void sendReadyText() {
        String text = readyText;
        DispatchTargetSnapshot target = readyTextTarget;
        long handoffId = readyTextHandoffId;
        boolean speechText = handoffId > 0L;
        long attemptId = readyTextAttemptId;
        readyText = null;
        readyTextTarget = null;
        readyTextHandoffId = 0L;
        readyTextAttemptId = 0L;
        if (text == null || text.isBlank()) {
            dispatchOverlayEvent(OverlayEvent.TEXT_REJECTED, attemptId);
            return;
        }
        TelegramRepository.TextSendCallback callback = new TelegramRepository.TextSendCallback() {
            @Override public void onQueued(long temporaryMessageId) {
                handleTextSendCallback(OverlayEvent.TEXT_QUEUED, attemptId,
                        () -> updateState(R.string.repo_text_queued),
                        speechText ? () -> notifySpeechTelegramResult(
                                handoffId, SPEECH_TELEGRAM_QUEUED, null) : null);
            }

            @Override public void onDelivered() {
                handleTextSendCallback(OverlayEvent.TEXT_DELIVERED, attemptId,
                        () -> updateState(R.string.repo_text_delivered),
                        speechText ? () -> notifySpeechTelegramResult(
                                handoffId, SPEECH_TELEGRAM_DELIVERED, null) : null);
            }

            @Override public void onRejected(String reason) {
                handleTextSendCallback(OverlayEvent.TEXT_REJECTED, attemptId,
                        () -> updateState(reason), speechText ? () -> {
                            notifySpeechTelegramResult(
                                    handoffId, SPEECH_TELEGRAM_REJECTED, reason);
                            if (!speechReviewVisible
                                    && overlayStateMachine.state()
                                    == OverlayStateMachine.State.SPEECH_REVIEW_OPEN) {
                                dispatchOverlayEvent(OverlayEvent.CLOSE_SPEECH_REVIEW);
                            }
                        } : null);
            }
        };
        if (target == null) telegram.sendText(text, callback);
        else telegram.sendText(text, target, callback);
    }

    private void notifySpeechTelegramResult(long handoffId, int result, String detail) {
        SpeechTelegramHandoffRegistry.Status status = switch (result) {
            case SPEECH_TELEGRAM_QUEUED -> SpeechTelegramHandoffRegistry.Status.QUEUED;
            case SPEECH_TELEGRAM_DELIVERED -> SpeechTelegramHandoffRegistry.Status.DELIVERED;
            case SPEECH_TELEGRAM_REJECTED -> SpeechTelegramHandoffRegistry.Status.REJECTED;
            default -> throw new IllegalArgumentException("Unknown speech Telegram result");
        };
        ((FloatingVoiceApp) getApplication()).speechTelegramHandoffs()
                .publish(handoffId, status);
        Intent event = new Intent(ACTION_SPEECH_TELEGRAM_RESULT)
                .setPackage(getPackageName())
                .putExtra(EXTRA_SPEECH_TELEGRAM_HANDOFF_ID, handoffId)
                .putExtra(EXTRA_SPEECH_TELEGRAM_RESULT, result);
        if (detail != null) event.putExtra(EXTRA_SPEECH_TELEGRAM_DETAIL, detail);
        sendBroadcast(event);
    }

    private boolean prepareComposerTelegramSubmit(Intent intent) {
        if (overlayStateMachine.state() != OverlayStateMachine.State.TEXT_COMPOSING
                || telegram == null) {
            return false;
        }
        String text = intent.getStringExtra(EXTRA_COMPOSER_TEXT);
        String requestedLocalId = intent.getStringExtra(EXTRA_DESTINATION_LOCAL_ID);
        DestinationCatalog catalog = telegram.destinationCatalog();
        long accountUserId = telegram.authenticatedAccountUserId();
        if (text == null || text.isBlank() || requestedLocalId == null
                || accountUserId <= 0L) {
            return false;
        }
        final OutputRouteStateMachine output;
        try {
            output = new OutputRouteStateMachine(
                    OutputRoute.ContentKind.TEXT, accountUserId, catalog,
                    catalog.defaultLocalId().orElse(null));
        } catch (IllegalArgumentException invalidState) {
            return false;
        }
        if (!output.selectTelegramDestination(requestedLocalId)) return false;
        OutputSnapshot snapshot = output.freeze(text.trim()).orElse(null);
        if (snapshot == null || snapshot.route() != OutputRoute.TELEGRAM_TEXT) return false;
        DispatchTargetSnapshot target = snapshot.telegramTarget().orElse(null);
        if (target == null) return false;
        readyText = snapshot.payload();
        readyTextTarget = target;
        readyTextHandoffId = 0L;
        readyTextAttemptId = overlayStateMachine.attemptId();
        return true;
    }

    private boolean prepareSpeechTelegramSubmit(Intent intent) {
        if (overlayStateMachine.state() != OverlayStateMachine.State.SPEECH_REVIEW_OPEN
                || telegram == null) {
            return false;
        }
        String text = intent.getStringExtra(EXTRA_COMPOSER_TEXT);
        String requestedLocalId = intent.getStringExtra(EXTRA_DESTINATION_LOCAL_ID);
        long handoffId = intent.getLongExtra(EXTRA_SPEECH_TELEGRAM_HANDOFF_ID, 0L);
        DestinationCatalog catalog = telegram.destinationCatalog();
        long accountUserId = telegram.authenticatedAccountUserId();
        if (text == null || text.isBlank() || requestedLocalId == null || handoffId <= 0L
                || accountUserId <= 0L) {
            return false;
        }
        String defaultLocalId = catalog.defaultLocalId().orElse(null);
        final OutputRouteStateMachine output;
        try {
            output = new OutputRouteStateMachine(
                    OutputRoute.ContentKind.TEXT, accountUserId, catalog, defaultLocalId);
        } catch (IllegalArgumentException invalidState) {
            return false;
        }
        if (!output.selectTelegramDestination(requestedLocalId)) return false;
        OutputSnapshot snapshot = output.freeze(text).orElse(null);
        if (snapshot == null || snapshot.route() != OutputRoute.TELEGRAM_TEXT) return false;
        DispatchTargetSnapshot target = snapshot.telegramTarget().orElse(null);
        if (target == null) return false;
        readyText = snapshot.payload();
        readyTextTarget = target;
        readyTextHandoffId = handoffId;
        readyTextAttemptId = overlayStateMachine.attemptId();
        return true;
    }

    private void handleTextSendCallback(
            OverlayEvent event, long attemptId, Runnable notificationUpdate,
            Runnable afterTransition) {
        getMainExecutor().execute(() -> {
            if (isTearingDown()) return;
            OverlayStateMachine.Transition transition = dispatchOverlayEvent(event, attemptId);
            if (transition.previousState() != transition.nextState()) {
                notificationUpdate.run();
                if (afterTransition != null) afterTransition.run();
            }
        });
    }

    private void sendReadyVoice() {
        File completed = readyVoiceRecording;
        DispatchTargetSnapshot target = readyVoiceTarget;
        OutputSnapshot output = readyVoiceOutput;
        int duration = readyVoiceDuration;
        long attemptId = readyVoiceAttemptId;
        if (output != null && output.route() == OutputRoute.LOCAL_AUDIO_ARCHIVE) {
            archiveReadyVoice(completed, output, attemptId);
            return;
        }
        if (output != null && output.route() == OutputRoute.SYSTEM_AUDIO_SHARE) {
            shareReadyVoice(completed, attemptId);
            return;
        }
        readyVoiceRecording = null;
        readyVoiceTarget = null;
        readyVoiceOutput = null;
        readyVoiceDuration = 0;
        readyVoiceAttemptId = 0L;
        activeRecordingRoute = null;
        if (completed == null || target == null) {
            if (target != null) completeRoute(target);
            dispatchOverlayEvent(OverlayEvent.VOICE_REJECTED, attemptId);
            return;
        }

        telegram.sendVoiceNote(completed, duration, target,
                new TelegramRepository.SendCallback() {
            @Override public void onQueued(long temporaryMessageId) {
                completeRoute(target);
                handleVoiceSendCallback(OverlayEvent.VOICE_QUEUED, attemptId,
                        () -> updateState(R.string.voice_queued_retained));
            }

            @Override public void onRejected(String reason) {
                completeRoute(target);
                handleVoiceSendCallback(OverlayEvent.VOICE_REJECTED, attemptId,
                        () -> updateState(reason));
            }
        });
    }

    private void archiveReadyVoice(File completed, OutputSnapshot output, long overlayAttemptId) {
        ArchiveSettingsStore.Selection selection = archiveSettingsStore.selection();
        if (completed == null || selection.status() != ArchiveSettingsStore.Status.READY) {
            dispatchOverlayEvent(OverlayEvent.VOICE_COMPLETED, overlayAttemptId);
            updateState(R.string.archive_failed_source_retained);
            return;
        }
        long outputAttemptId = output.outputAttemptId();
        Uri treeUri = Uri.parse(selection.treeUri());
        updateState(R.string.archive_saving);
        archiveExecutor.execute(() -> {
            LocalArchiveController.Result result = localArchiveController.archive(
                    new LocalArchiveController.SafStoragePort(
                            getContentResolver(), treeUri, completed));
            mainHandler.post(() -> handleArchiveResult(
                    completed, output, overlayAttemptId, outputAttemptId, result));
        });
    }

    private void shareReadyVoice(File completed, long overlayAttemptId) {
        AndroidShareController.Result result = AndroidShareController.Result.LAUNCH_FAILED;
        if (completed != null && completed.isFile() && completed.length() > 0L) {
            try {
                Uri uri = FileProvider.getUriForFile(this,
                        BuildConfig.APPLICATION_ID + ".fileprovider", completed);
                result = AndroidShareController.create(
                        this, text(R.string.audio_share_chooser_title)).shareAudio(uri.toString());
            } catch (RuntimeException ignored) { }
        }
        dispatchOverlayEvent(result
                == AndroidShareController.Result.CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED
                ? OverlayEvent.AUDIO_SHARE_CHOOSER_OPENED
                : OverlayEvent.AUDIO_SHARE_FAILED, overlayAttemptId);
        String name = completed == null ? "" : completed.getName();
        readyVoiceRecording = null;
        readyVoiceOutput = null;
        readyVoiceTarget = null;
        readyVoiceDuration = 0;
        readyVoiceAttemptId = 0L;
        activeRecordingRoute = null;
        int messageId = switch (result) {
            case CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED ->
                    R.string.audio_share_opened_retained;
            case NO_HANDLER -> R.string.audio_share_no_handler_retained;
            default -> R.string.audio_share_failed_retained;
        };
        updateState(messageId, name);
        if (result != AndroidShareController.Result.CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED) {
            Toast.makeText(this, text(messageId, name), Toast.LENGTH_LONG).show();
        }
    }

    private void handleArchiveResult(File completed, OutputSnapshot output,
                                     long overlayAttemptId, long outputAttemptId,
                                     LocalArchiveController.Result result) {
        if (isTearingDown() || overlayStateMachine.attemptId() != overlayAttemptId
                || readyVoiceOutput != output
                || output.outputAttemptId() != outputAttemptId) return;
        OverlayStateMachine.Transition transition = dispatchOverlayEvent(
                OverlayEvent.VOICE_COMPLETED, overlayAttemptId);
        if (transition.previousState() == transition.nextState()) return;
        // The source file itself remains on disk for every non-deleted outcome. The in-memory
        // handoff must still end here so a later capture cannot inherit this completed attempt.
        readyVoiceRecording = null;
        readyVoiceOutput = null;
        readyVoiceTarget = null;
        readyVoiceDuration = 0;
        readyVoiceAttemptId = 0L;
        activeRecordingRoute = null;
        switch (result.outcome()) {
            case VERIFIED_SOURCE_DELETED -> updateState(
                    R.string.archive_verified_saved, result.targetName());
            case VERIFIED_SOURCE_RETAINED -> updateState(
                    R.string.archive_verified_source_retained, result.targetName());
            case COPIED_UNVERIFIED_SOURCE_RETAINED -> updateState(
                    R.string.archive_copied_unverified, result.targetName());
            case FAILED_SOURCE_RETAINED -> updateState(result.partialTargetRetained()
                    ? R.string.archive_failed_partial_retained
                    : R.string.archive_failed_source_retained);
        }
    }

    private void completeRoute(DispatchTargetSnapshot target) {
        RouteStateMachine route = routeStateMachine;
        if (route != null) route.completeDispatch(target.routeAttemptId());
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
        if (current != null) {
            try { current.stop(); } catch (RuntimeException ignored) { }
        }
        boolean released = current == null || tryReleaseRecorder(current);
        recorder = released ? null : current;
        if (released) releaseRecordingOwnership();
        if (RecordingRoutePolicy.releaseShareStoreOwnership(activeRecordingRoute, released)
                && activeRecording != null && retainedAudioShareStore != null) {
            retainedAudioShareStore.releaseActive(activeRecording);
        }
        if (activeRecording != null) {
            boolean privateOutput = activeRecordingRoute == OutputRoute.LOCAL_AUDIO_ARCHIVE
                    || activeRecordingRoute == OutputRoute.SYSTEM_AUDIO_SHARE;
            notificationResourceId = privateOutput
                    ? R.string.recording_interrupted_retained_private
                    : R.string.recording_interrupted_retained;
            notificationArguments = privateOutput
                    ? new Object[0] : new Object[] {activeRecording.getAbsolutePath()};
            notificationText = null;
        }
    }

    private boolean stopActiveRecordingForLostTelegramRoute() {
        if (RecordingRoutePolicy.stopForTelegramRouteLoss(activeRecordingRoute,
                overlayStateMachine.state() == OverlayStateMachine.State.RECORDING)) {
            dispatchOverlayEvent(OverlayEvent.TAP);
            return true;
        }
        return false;
    }

    private void releaseRecordingOwnership() {
        AudioCaptureOwnership.Lease lease = recordingLease;
        recordingLease = null;
        if (lease != null && audioCaptureCoordinator != null) {
            audioCaptureCoordinator.finishRecording(lease, true);
        }
    }

    private static boolean tryReleaseRecorder(MediaRecorder current) {
        try {
            current.release();
            return true;
        } catch (RuntimeException ignored) {
            return false;
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
                        int minX;
                        int maxX;
                        int minY;
                        int maxY;
                        if (layoutParams.width > currentFabSizePx) {
                            int dockWidth = px(R.dimen.overlay_dock_width);
                            int dockHeight = px(R.dimen.overlay_dock_height);
                            int stopSize = px(R.dimen.overlay_stop_size);
                            int stopOffset = recordingStopOnRight
                                    ? dockWidth - dp(4) - stopSize / 2
                                    : dp(4) + stopSize / 2;
                            minX = Math.max(display.left,
                                    display.left + margin + currentFabSizePx / 2 - stopOffset);
                            maxX = Math.min(display.right - layoutParams.width,
                                    display.right - margin - currentFabSizePx / 2 - stopOffset);
                            minY = Math.max(display.top,
                                    display.top + margin + currentFabSizePx / 2 - dockHeight / 2);
                            maxY = Math.min(display.bottom - layoutParams.height,
                                    display.bottom - margin - currentFabSizePx / 2 - dockHeight / 2);
                        } else {
                            minX = display.left + margin;
                            maxX = display.right - margin - layoutParams.width;
                            minY = display.top + margin;
                            maxY = display.bottom - margin - layoutParams.height;
                        }
                        maxX = Math.max(minX, maxX);
                        maxY = Math.max(minY, maxY);
                        layoutParams.x = clamp(initialX + Math.round(latestX - downX),
                                minX, maxX);
                        layoutParams.y = clamp(initialY + Math.round(latestY - downY),
                                minY, maxY);
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
                            && showsIdleBubble(overlayStateMachine.state())) {
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

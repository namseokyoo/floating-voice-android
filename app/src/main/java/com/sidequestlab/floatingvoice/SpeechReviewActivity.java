package com.sidequestlab.floatingvoice;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.sidequestlab.floatingvoice.core.Destination;
import com.sidequestlab.floatingvoice.core.DestinationCatalog;
import com.sidequestlab.floatingvoice.core.OutputRoute;
import com.sidequestlab.floatingvoice.core.OutputRouteStateMachine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Non-exported, memory-only review/edit host for explicit Android text sharing. */
public final class SpeechReviewActivity extends AppCompatActivity {
    private SpeechReviewViewModel model;
    private AndroidShareController shareController;
    private TextView status;
    private EditText draft;
    private Button share;
    private Button retry;
    private Button stop;
    private Button listeningCancel;
    private View outputControls;
    private TextView telegramDestination;
    private Button chooseDestination;
    private Button sendTelegram;
    private TelegramRepository telegram;
    private SpeechTelegramHandoffRegistry telegramHandoffs;
    private BroadcastReceiver serviceTeardownReceiver;
    private BroadcastReceiver speechTelegramResultReceiver;
    private Consumer<SpeechReviewSession.UiState> observer;
    private boolean applyingState;
    private boolean chooserAwaitingReturn;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        setContentView(R.layout.activity_speech_review);
        configureResponsiveActionRows();

        status = findViewById(R.id.speech_review_status);
        draft = findViewById(R.id.speech_review_draft);
        share = findViewById(R.id.speech_review_share);
        retry = findViewById(R.id.speech_review_retry);
        stop = findViewById(R.id.speech_review_stop);
        listeningCancel = findViewById(R.id.speech_review_listening_cancel);
        outputControls = findViewById(R.id.speech_review_output_controls);
        telegramDestination = findViewById(R.id.speech_review_telegram_destination);
        chooseDestination = findViewById(R.id.speech_review_choose_destination);
        sendTelegram = findViewById(R.id.speech_review_send_telegram);
        shareController = AndroidShareController.create(
                this, getString(R.string.speech_review_share_chooser_title));

        FloatingVoiceApp app = (FloatingVoiceApp) getApplication();
        telegram = app.telegram();
        telegramHandoffs = app.speechTelegramHandoffs();
        AudioCaptureCoordinator coordinator = app.audioCaptureCoordinator();
        model = new ViewModelProvider(this, new ViewModelProvider.Factory() {
            @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> type) {
                if (!type.isAssignableFrom(SpeechReviewViewModel.class)) {
                    throw new IllegalArgumentException("Unsupported ViewModel " + type.getName());
                }
                SpeechReviewViewModel created = new SpeechReviewViewModel(
                        coordinator,
                        listener -> controllerPort(SystemSpeechRecognizerController.create(
                                SpeechReviewActivity.this, coordinator, listener)));
                return type.cast(created);
            }
        }).get(SpeechReviewViewModel.class);
        refreshTelegramDestination();

        observer = this::render;
        model.setObserver(observer);
        draft.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (applyingState) return;
                model.session().editDraft(s == null ? "" : s.toString());
                model.notifyStateChanged();
            }
            @Override public void afterTextChanged(Editable editable) { }
        });
        share.setOnClickListener(view -> shareDraft());
        chooseDestination.setOnClickListener(view -> openTelegramDestinationDialog());
        sendTelegram.setOnClickListener(view -> sendDraftToTelegram());
        retry.setOnClickListener(view -> model.retry());
        stop.setOnClickListener(view -> model.stopListening());
        listeningCancel.setOnClickListener(view -> closeWithoutSharing());
        findViewById(R.id.speech_review_cancel).setOnClickListener(view -> closeWithoutSharing());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                closeWithoutSharing();
            }
        });

        serviceTeardownReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent != null && FloatingVoiceService.ACTION_SERVICE_TEARDOWN
                        .equals(intent.getAction()) && !isFinishing()) {
                    model.cancel();
                    finish();
                }
            }
        };
        ContextCompat.registerReceiver(this, serviceTeardownReceiver,
                new IntentFilter(FloatingVoiceService.ACTION_SERVICE_TEARDOWN),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        speechTelegramResultReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null || !FloatingVoiceService.ACTION_SPEECH_TELEGRAM_RESULT
                        .equals(intent.getAction())) return;
                replayTelegramHandoffResult();
            }
        };
        ContextCompat.registerReceiver(this, speechTelegramResultReceiver,
                new IntentFilter(FloatingVoiceService.ACTION_SPEECH_TELEGRAM_RESULT),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        replayTelegramHandoffResult();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            model.startOnce();
        } else if (!model.startKeyboardOnly()) {
            status.setText(R.string.speech_recognition_mic_busy);
            draft.setEnabled(false);
            share.setEnabled(false);
            retry.setEnabled(false);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        SpeechReviewWindowGeometry.Size size = SpeechReviewWindowGeometry.calculate(
                metrics.widthPixels, metrics.heightPixels, metrics.density);
        getWindow().setGravity(Gravity.CENTER);
        getWindow().setLayout(size.widthPx(), size.heightPx());
    }

    @Override protected void onResume() {
        super.onResume();
        replayTelegramHandoffResult();
        if (model != null && model.hasSession()
                && model.session().uiState().stage()
                == SpeechReviewSession.Stage.SHARE_CONFIRMATION_REQUIRED) {
            chooserAwaitingReturn = false;
            model.session().chooserReturned(shareController);
            model.notifyStateChanged();
        }
        if (model != null && model.hasSession()
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
                && model.session().uiState().stage() == SpeechReviewSession.Stage.LISTENING) {
            model.cancel();
        }
    }

    @Override protected void onStop() {
        SpeechReviewSession.Stage retainedStage = model != null && model.hasSession()
                ? model.session().uiState().stage()
                : SpeechReviewSession.Stage.CHECKING;
        boolean shouldFinish = SpeechReviewLifecyclePolicy.shouldFinishOnStop(
                isChangingConfigurations(), chooserAwaitingReturn, retainedStage);
        if (model != null && model.hasSession() && shouldFinish
                && SpeechReviewLifecyclePolicy.shouldCancelRecognitionOnStop(retainedStage)) {
            model.cancel();
        }
        if (!isFinishing() && shouldFinish) {
            finish();
        }
        super.onStop();
    }

    private void shareDraft() {
        SpeechReviewSession.UiState state = model.session().uiState();
        model.clearTelegramFeedback();
        DestinationCatalog catalog = telegram == null
                ? DestinationCatalog.empty() : telegram.destinationCatalog();
        long accountUserId = telegram == null ? 0L : telegram.authenticatedAccountUserId();
        OutputRouteStateMachine output = new OutputRouteStateMachine(
                OutputRoute.ContentKind.TEXT, accountUserId, catalog,
                catalog.defaultLocalId().orElse(null));
        if (!output.selectRoute(OutputRoute.SYSTEM_TEXT_SHARE)
                || output.freeze(state.draft()).isEmpty()) {
            return;
        }
        AndroidShareController.Result result = model.session().share(shareController);
        chooserAwaitingReturn = result
                == AndroidShareController.Result.CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED;
        model.notifyStateChanged();
        if (result == AndroidShareController.Result.NO_HANDLER) {
            status.setText(R.string.speech_review_no_handler);
        } else if (result == AndroidShareController.Result.LAUNCH_FAILED) {
            status.setText(R.string.speech_review_share_failed);
        }
    }

    private void sendDraftToTelegram() {
        SpeechReviewViewModel.TelegramOutputState output = model.telegramOutputState();
        if (output.handoffInFlight() || output.selectedLocalId() == null) return;
        SpeechReviewSession.UiState state = model.session().uiState();
        if (!state.shareEnabled() || state.draft().isBlank()) return;
        long handoffId = Math.max(1L, SystemClock.elapsedRealtimeNanos());
        if (!model.beginTelegramHandoff(handoffId)) return;
        sendTelegram.setEnabled(false);
        chooseDestination.setEnabled(false);
        share.setEnabled(false);
        status.setText(R.string.speech_review_telegram_queuing);

        Intent handoff = new Intent(FloatingVoiceService.ACTION_SPEECH_TELEGRAM_SUBMIT)
                .setPackage(getPackageName())
                .putExtra(FloatingVoiceService.EXTRA_COMPOSER_TEXT, state.draft())
                .putExtra(FloatingVoiceService.EXTRA_DESTINATION_LOCAL_ID,
                        output.selectedLocalId())
                .putExtra(FloatingVoiceService.EXTRA_SPEECH_TELEGRAM_HANDOFF_ID,
                        handoffId);
        BroadcastReceiver receipt = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (getResultCode() == FloatingVoiceService.COMPOSER_SUBMIT_ACCEPTED) {
                    replayTelegramHandoffResult();
                    return;
                }
                if (!model.rejectTelegramHandoff(handoffId)) return;
                telegramHandoffs.clear(handoffId);
                model.notifyStateChanged();
                render(model.session().uiState());
                status.setText(R.string.speech_review_telegram_handoff_failed);
            }
        };
        try {
            sendOrderedBroadcast(handoff, null, receipt, null,
                    FloatingVoiceService.COMPOSER_SUBMIT_REJECTED, null, null);
        } catch (RuntimeException failure) {
            if (model.rejectTelegramHandoff(handoffId)) {
                telegramHandoffs.clear(handoffId);
                model.notifyStateChanged();
                render(model.session().uiState());
                status.setText(R.string.speech_review_telegram_handoff_failed);
            }
        }
    }

    private void openTelegramDestinationDialog() {
        SpeechReviewViewModel.TelegramOutputState output = model.telegramOutputState();
        if (telegram == null || output.handoffInFlight()) return;
        DestinationCatalog catalog = telegram.destinationCatalog();
        long accountUserId = telegram.authenticatedAccountUserId();
        List<Destination> selectable = new ArrayList<>();
        for (Destination destination : catalog.destinations()) {
            if (destination.selectableBy(accountUserId)) selectable.add(destination);
        }
        if (selectable.isEmpty()) {
            status.setText(R.string.speech_review_no_verified_destinations);
            return;
        }
        CharSequence[] labels = new CharSequence[selectable.size()];
        int checked = -1;
        for (int index = 0; index < selectable.size(); index++) {
            Destination destination = selectable.get(index);
            labels[index] = destinationLabel(destination);
            if (destination.localId().equals(output.selectedLocalId())) checked = index;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.speech_review_destination_dialog_title)
                .setSingleChoiceItems(labels, checked, null)
                .setNegativeButton(R.string.destination_picker_close, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getListView().setOnItemClickListener(
                (parent, view, position, id) -> {
                    model.selectTelegramDestination(selectable.get(position).localId());
                    refreshTelegramDestination();
                    render(model.session().uiState());
                    dialog.dismiss();
                }));
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            if (Build.VERSION.SDK_INT >= 31) dialog.getWindow().setHideOverlayWindows(true);
        }
    }

    private void refreshTelegramDestination() {
        if (model == null) return;
        SpeechReviewViewModel.TelegramOutputState output = model.telegramOutputState();
        if (telegram == null) {
            model.invalidateTelegramDestination(output.selectedLocalId());
            telegramDestination.setText(R.string.speech_review_telegram_destination_none);
            return;
        }
        DestinationCatalog catalog = telegram.destinationCatalog();
        long accountUserId = telegram.authenticatedAccountUserId();
        Destination selected = output.selectedLocalId() == null ? null
                : catalog.selectable(output.selectedLocalId(), accountUserId).orElse(null);
        if (selected == null && output.selectedLocalId() != null) {
            model.invalidateTelegramDestination(output.selectedLocalId());
        }
        if (selected == null && !output.explicitSelection()) {
            selected = catalog.defaultLocalId()
                    .flatMap(localId -> catalog.selectable(localId, accountUserId))
                    .orElse(null);
            if (selected != null) model.initializeTelegramDestination(selected.localId());
        }
        telegramDestination.setText(selected == null
                ? getString(R.string.speech_review_telegram_destination_none)
                : getString(R.string.speech_review_telegram_destination_format,
                        destinationLabel(selected)));
    }

    private void replayTelegramHandoffResult() {
        if (model == null || telegramHandoffs == null) return;
        SpeechReviewViewModel.TelegramOutputState output = model.telegramOutputState();
        if (!output.handoffInFlight()) return;
        telegramHandoffs.latest(output.handoffId()).ifPresent(event -> {
            if (!model.matchesTelegramHandoff(event.handoffId())) return;
            switch (event.status()) {
                case QUEUED -> status.setText(R.string.speech_review_telegram_pending);
                case DELIVERED -> {
                    telegramHandoffs.clear(event.handoffId());
                    model.close();
                    finish();
                }
                case REJECTED -> {
                    telegramHandoffs.clear(event.handoffId());
                    if (!model.rejectTelegramHandoff(event.handoffId())) return;
                    if (model.hasSession()) render(model.session().uiState());
                    status.setText(R.string.speech_review_telegram_handoff_failed);
                }
            }
        });
    }

    private static String destinationLabel(Destination destination) {
        if (!destination.userAlias().isBlank()) return destination.userAlias();
        if (!destination.resolvedTitle().isBlank()) return destination.resolvedTitle();
        return "@" + destination.resolvedUsername();
    }

    private void closeWithoutSharing() {
        if (model != null) model.cancel();
        finish();
    }

    private void configureResponsiveActionRows() {
        boolean stack = SpeechReviewActionLayoutPolicy.shouldStack(
                getResources().getConfiguration().fontScale);
        configureActionRow(findViewById(R.id.speech_review_primary_actions), stack);
        configureActionRow(findViewById(R.id.speech_review_secondary_actions), stack);
    }

    private void configureActionRow(LinearLayout row, boolean stack) {
        row.setOrientation(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        int gap = Math.round(8f * getResources().getDisplayMetrics().density);
        for (int index = 0; index < row.getChildCount(); index++) {
            View child = row.getChildAt(index);
            LinearLayout.LayoutParams params =
                    (LinearLayout.LayoutParams) child.getLayoutParams();
            params.width = stack ? ViewGroup.LayoutParams.MATCH_PARENT : 0;
            params.weight = stack ? 0f : 1f;
            params.topMargin = stack && index > 0 ? gap : 0;
            params.setMarginStart(!stack && index > 0 ? gap : 0);
            child.setLayoutParams(params);
        }
    }

    private void render(SpeechReviewSession.UiState state) {
        if (state == null || draft == null) return;
        String nextDraft = state.draft();
        if (!nextDraft.contentEquals(draft.getText())) {
            applyingState = true;
            draft.setText(nextDraft);
            draft.setSelection(draft.length());
            applyingState = false;
        }
        draft.setEnabled(state.editable());
        share.setEnabled(state.shareEnabled());
        retry.setEnabled(state.retryEnabled());
        boolean dictating = state.stage() == SpeechReviewSession.Stage.LISTENING
                || state.stage() == SpeechReviewSession.Stage.PROCESSING;
        boolean captureActive = state.stage() == SpeechReviewSession.Stage.CHECKING || dictating;
        boolean reviewing = state.stage() == SpeechReviewSession.Stage.EDITING
                || state.stage() == SpeechReviewSession.Stage.KEYBOARD_FALLBACK
                || state.stage() == SpeechReviewSession.Stage.SHARE_FAILED;
        stop.setVisibility(dictating ? View.VISIBLE : View.GONE);
        stop.setEnabled(dictating);
        listeningCancel.setVisibility(captureActive ? View.VISIBLE : View.GONE);
        outputControls.setVisibility(reviewing ? View.VISIBLE : View.GONE);
        if (reviewing) refreshTelegramDestination();
        SpeechReviewViewModel.TelegramOutputState output = model.telegramOutputState();
        chooseDestination.setEnabled(reviewing && !output.handoffInFlight());
        sendTelegram.setEnabled(reviewing && state.shareEnabled()
                && output.selectedLocalId() != null && !output.handoffInFlight());
        share.setVisibility(reviewing ? View.VISIBLE : View.GONE);
        retry.setVisibility(reviewing ? View.VISIBLE : View.GONE);
        int statusResource = switch (state.stage()) {
            case CHECKING -> R.string.speech_recognition_checking;
            case LISTENING -> R.string.speech_recognition_listening;
            case PROCESSING -> R.string.speech_recognition_processing;
            case EDITING -> R.string.speech_review_editing;
            case KEYBOARD_FALLBACK -> R.string.speech_review_keyboard_fallback;
            case SHARE_CONFIRMATION_REQUIRED -> R.string.speech_review_share_opened;
            case SHARE_FAILED -> R.string.speech_review_share_failed;
            case CANCELED -> R.string.speech_recognition_canceled;
        };
        status.setText(model.telegramFeedback()
                == SpeechReviewViewModel.TelegramFeedback.REJECTED
                ? R.string.speech_review_telegram_handoff_failed : statusResource);
    }

    private static SpeechReviewViewModel.RecognitionPort controllerPort(
            SystemSpeechRecognizerController controller) {
        return new SpeechReviewViewModel.RecognitionPort() {
            @Override public SystemSpeechRecognizerController.StartResult start() {
                return controller.start();
            }
            @Override public void stopListening() { controller.stopListening(); }
            @Override public void cancel() { controller.cancel(); }
            @Override public void destroy() { controller.destroy(); }
        };
    }

    @Override protected void onDestroy() {
        if (model != null && observer != null) model.clearObserver(observer);
        observer = null;
        if (serviceTeardownReceiver != null) {
            try {
                unregisterReceiver(serviceTeardownReceiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver was already removed by the platform.
            }
            serviceTeardownReceiver = null;
        }
        if (speechTelegramResultReceiver != null) {
            try {
                unregisterReceiver(speechTelegramResultReceiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver was already removed by the platform.
            }
            speechTelegramResultReceiver = null;
        }
        if (!isChangingConfigurations()) {
            sendBroadcast(new Intent(FloatingVoiceService.ACTION_SPEECH_REVIEW_CLOSED)
                    .setPackage(getPackageName()));
        }
        super.onDestroy();
    }
}

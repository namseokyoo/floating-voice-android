package com.sidequestlab.floatingvoice;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

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
    private BroadcastReceiver serviceTeardownReceiver;
    private Consumer<SpeechReviewSession.UiState> observer;
    private boolean applyingState;
    private boolean chooserAwaitingReturn;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        setContentView(R.layout.activity_speech_review);

        status = findViewById(R.id.speech_review_status);
        draft = findViewById(R.id.speech_review_draft);
        share = findViewById(R.id.speech_review_share);
        retry = findViewById(R.id.speech_review_retry);
        stop = findViewById(R.id.speech_review_stop);
        shareController = AndroidShareController.create(
                this, getString(R.string.speech_review_share_chooser_title));

        FloatingVoiceApp app = (FloatingVoiceApp) getApplication();
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
        retry.setOnClickListener(view -> model.retry());
        stop.setOnClickListener(view -> model.stopListening());
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

    private void closeWithoutSharing() {
        if (model != null) model.cancel();
        finish();
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
        boolean reviewing = state.stage() == SpeechReviewSession.Stage.EDITING
                || state.stage() == SpeechReviewSession.Stage.KEYBOARD_FALLBACK
                || state.stage() == SpeechReviewSession.Stage.SHARE_FAILED;
        stop.setVisibility(dictating ? View.VISIBLE : View.GONE);
        stop.setEnabled(dictating);
        share.setVisibility(reviewing ? View.VISIBLE : View.GONE);
        retry.setVisibility(reviewing ? View.VISIBLE : View.GONE);
        status.setText(switch (state.stage()) {
            case CHECKING -> R.string.speech_recognition_checking;
            case LISTENING -> R.string.speech_recognition_listening;
            case PROCESSING -> R.string.speech_recognition_processing;
            case EDITING -> R.string.speech_review_editing;
            case KEYBOARD_FALLBACK -> R.string.speech_review_keyboard_fallback;
            case SHARE_CONFIRMATION_REQUIRED -> R.string.speech_review_share_opened;
            case SHARE_FAILED -> R.string.speech_review_share_failed;
            case CANCELED -> R.string.speech_recognition_canceled;
        });
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
        if (!isChangingConfigurations()) {
            sendBroadcast(new Intent(FloatingVoiceService.ACTION_SPEECH_REVIEW_CLOSED)
                    .setPackage(getPackageName()));
        }
        super.onDestroy();
    }
}

package com.sidequestlab.floatingvoice;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;

/** Debug-only visible A52s system-STT measurement harness. */
public final class SttCapabilityDebugActivity extends AppCompatActivity {
    private final SttMeasurementSession session = new SttMeasurementSession();
    private SpeechRecognizer recognizer;
    private long activeGeneration;

    private TextView supportView;
    private TextView phraseIndexView;
    private TextView phraseView;
    private TextView stateView;
    private TextView partialView;
    private TextView rawResultView;
    private TextView metricsView;
    private TextView summaryView;
    private EditText correctedView;
    private RadioButton standardMode;
    private RadioButton onDeviceMode;
    private RadioButton onlineEnvironment;
    private Button startButton;
    private Button cancelButton;

    private final ActivityResultLauncher<String> microphonePermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startRecognition();
                else stateView.setText(R.string.debug_stt_permission_denied);
            });

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stt_capability_debug);
        bindViews();
        bindActions();
        renderPhrase();
        renderSupport();
    }

    private void bindViews() {
        supportView = findViewById(R.id.debug_stt_support);
        phraseIndexView = findViewById(R.id.debug_stt_phrase_index);
        phraseView = findViewById(R.id.debug_stt_phrase);
        stateView = findViewById(R.id.debug_stt_state);
        partialView = findViewById(R.id.debug_stt_partial);
        rawResultView = findViewById(R.id.debug_stt_raw_result);
        metricsView = findViewById(R.id.debug_stt_metrics);
        summaryView = findViewById(R.id.debug_stt_summary);
        correctedView = findViewById(R.id.debug_stt_corrected);
        standardMode = findViewById(R.id.debug_stt_standard_mode);
        onDeviceMode = findViewById(R.id.debug_stt_on_device_mode);
        onlineEnvironment = findViewById(R.id.debug_stt_online_environment);
        startButton = findViewById(R.id.debug_stt_start);
        cancelButton = findViewById(R.id.debug_stt_cancel);
    }

    private void bindActions() {
        findViewById(R.id.debug_stt_previous).setOnClickListener(v -> selectPhrase(-1));
        findViewById(R.id.debug_stt_next).setOnClickListener(v -> selectPhrase(1));
        startButton.setOnClickListener(v -> ensurePermissionAndStart());
        cancelButton.setOnClickListener(v -> cancelRecognition(R.string.debug_stt_canceled));
        findViewById(R.id.debug_stt_record_measurement).setOnClickListener(v -> recordMeasurement());
        correctedView.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) renderMetrics();
        });
        onDeviceMode.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !onDeviceAvailable()) {
                standardMode.setChecked(true);
                stateView.setText(R.string.debug_stt_on_device_unavailable);
            }
        });
    }

    private void selectPhrase(int delta) {
        int current = session.phraseNumber() - 1;
        int next = Math.max(0, Math.min(session.phraseCount() - 1, current + delta));
        if (next == current) return;
        destroyRecognizer();
        session.selectPhrase(next);
        renderPhrase();
    }

    private void renderPhrase() {
        phraseIndexView.setText(getString(R.string.debug_stt_phrase_index,
                session.phraseNumber(), session.phraseCount()));
        phraseView.setText(session.expectedPhrase());
        partialView.setText("");
        rawResultView.setText("");
        correctedView.setText("");
        metricsView.setText(R.string.debug_stt_metrics_empty);
        summaryView.setText(session.summaryText());
        stateView.setText(R.string.debug_stt_ready);
    }

    private void renderSupport() {
        boolean standard = SpeechRecognizer.isRecognitionAvailable(this);
        boolean onDevice = onDeviceAvailable();
        supportView.setText(getString(R.string.debug_stt_support_format,
                Build.VERSION.SDK_INT,
                standard ? getString(R.string.debug_yes) : getString(R.string.debug_no),
                onDevice ? getString(R.string.debug_yes) : getString(R.string.debug_no)));
        onDeviceMode.setEnabled(onDevice);
        if (!standard) {
            startButton.setEnabled(false);
            stateView.setText(R.string.debug_stt_standard_unavailable);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            SttRecognitionSupportApi33.check(this, recognitionIntent(), supportView);
        }
    }

    private boolean onDeviceAvailable() {
        return Build.VERSION.SDK_INT >= 31
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
    }

    private Intent recognitionIntent() {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
    }


    private void ensurePermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startRecognition();
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startRecognition() {
        destroyRecognizer();
        boolean requestOnDevice = onDeviceMode.isChecked();
        if (requestOnDevice && !onDeviceAvailable()) {
            stateView.setText(R.string.debug_stt_on_device_unavailable);
            return;
        }
        try {
            if (requestOnDevice) {
                if (Build.VERSION.SDK_INT < 31) {
                    stateView.setText(R.string.debug_stt_on_device_unavailable);
                    return;
                }
                recognizer = SttOnDeviceRecognizerApi31.create(this);
            } else {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            }
        } catch (RuntimeException creationFailure) {
            stateView.setText(R.string.debug_stt_create_failed);
            destroyRecognizer();
            return;
        }
        activeGeneration = session.beginAttempt(SystemClock.elapsedRealtime(),
                requestOnDevice ? SttMeasurementSession.RecognizerMode.ON_DEVICE
                        : SttMeasurementSession.RecognizerMode.STANDARD,
                actualEnvironment());
        recognizer.setRecognitionListener(listenerFor(activeGeneration));
        stateView.setText(R.string.debug_stt_listening);
        startButton.setEnabled(false);
        cancelButton.setEnabled(true);
        try {
            recognizer.startListening(recognitionIntent());
        } catch (RuntimeException startFailure) {
            session.failAttempt(activeGeneration);
            stateView.setText(R.string.debug_stt_start_failed);
            finishAttempt();
        }
    }

    private RecognitionListener listenerFor(long generation) {
        return new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                if (generation == activeGeneration) stateView.setText(R.string.debug_stt_speak_now);
            }
            @Override public void onBeginningOfSpeech() {
                if (generation == activeGeneration) stateView.setText(R.string.debug_stt_hearing);
            }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() {
                if (generation == activeGeneration) {
                    session.markSpeechEnded(generation, SystemClock.elapsedRealtime());
                    stateView.setText(R.string.debug_stt_processing);
                }
            }
            @Override public void onError(int error) {
                if (generation != activeGeneration) return;
                session.failAttempt(generation);
                stateView.setText(getString(R.string.debug_stt_error, error, errorName(error)));
                finishAttempt();
            }
            @Override public void onResults(Bundle results) {
                if (generation != activeGeneration) return;
                String result = firstResult(results);
                if (!session.acceptFinal(generation, result, SystemClock.elapsedRealtime())) return;
                rawResultView.setText(session.rawFinalText());
                correctedView.setText(session.rawFinalText());
                stateView.setText(session.rawFinalText().isEmpty()
                        ? R.string.debug_stt_empty_result : R.string.debug_stt_final_received);
                renderMetrics();
                finishAttempt();
            }
            @Override public void onPartialResults(Bundle partialResults) {
                String partial = firstResult(partialResults);
                if (session.acceptPartial(generation, partial)) partialView.setText(partial);
            }
            @Override public void onEvent(int eventType, Bundle params) { }
        };
    }

    private static String firstResult(Bundle bundle) {
        if (bundle == null) return "";
        ArrayList<String> values = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return values == null || values.isEmpty() || values.get(0) == null ? "" : values.get(0);
    }

    private void renderMetrics() {
        String corrected = correctedView.getText() == null ? "" : correctedView.getText().toString();
        int correction = SttMeasurementSession.correctionDistance(session.rawFinalText(), corrected);
        metricsView.setText(getString(R.string.debug_stt_metrics_format,
                modeLabel(session.currentMode()),
                environmentLabel(session.currentEnvironment()),
                session.currentLatencyMs(), correction));
    }

    private void recordMeasurement() {
        if (session.rawFinalText().isEmpty()) {
            stateView.setText(R.string.debug_stt_record_requires_result);
            return;
        }
        String corrected = correctedView.getText() == null ? "" : correctedView.getText().toString();
        session.recordCurrentCorrection(corrected);
        summaryView.setText(session.summaryText());
        stateView.setText(R.string.debug_stt_recorded);
    }

    private String modeLabel(SttMeasurementSession.RecognizerMode mode) {
        return getString(mode == SttMeasurementSession.RecognizerMode.ON_DEVICE
                ? R.string.debug_stt_mode_on_device : R.string.debug_stt_mode_standard);
    }

    private String environmentLabel(SttMeasurementSession.Environment environment) {
        return getString(environment == SttMeasurementSession.Environment.AIRPLANE_MODE
                ? R.string.debug_stt_environment_airplane : R.string.debug_stt_environment_online);
    }

    private SttMeasurementSession.Environment actualEnvironment() {
        boolean airplane = Settings.Global.getInt(
                getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        onlineEnvironment.setChecked(!airplane);
        RadioButton airplaneEnvironment = findViewById(R.id.debug_stt_airplane_environment);
        airplaneEnvironment.setChecked(airplane);
        onlineEnvironment.setEnabled(false);
        airplaneEnvironment.setEnabled(false);
        return airplane ? SttMeasurementSession.Environment.AIRPLANE_MODE
                : SttMeasurementSession.Environment.ONLINE;
    }

    private String errorName(int error) {
        return switch (error) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT";
            case SpeechRecognizer.ERROR_NETWORK -> "NETWORK";
            case SpeechRecognizer.ERROR_AUDIO -> "AUDIO";
            case SpeechRecognizer.ERROR_SERVER -> "SERVER";
            case SpeechRecognizer.ERROR_CLIENT -> "CLIENT";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT";
            case SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "PERMISSION";
            default -> "ERROR";
        };
    }

    private void cancelRecognition(int statusText) {
        session.cancelAttempt();
        stateView.setText(statusText);
        destroyRecognizer();
        startButton.setEnabled(true);
        cancelButton.setEnabled(false);
    }

    private void finishAttempt() {
        destroyRecognizer();
        startButton.setEnabled(true);
        cancelButton.setEnabled(false);
    }

    private void destroyRecognizer() {
        activeGeneration++;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (RuntimeException ignored) { }
            recognizer.destroy();
            recognizer = null;
        }
    }

    @Override protected void onStop() {
        super.onStop();
        if (recognizer != null) cancelRecognition(R.string.debug_stt_stopped_for_lifecycle);
    }

    @Override protected void onDestroy() {
        destroyRecognizer();
        super.onDestroy();
    }
}

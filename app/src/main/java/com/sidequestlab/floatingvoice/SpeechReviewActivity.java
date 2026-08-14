package com.sidequestlab.floatingvoice;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * Non-exported lifecycle host for V8-03 recognition. Review/edit/share behavior belongs to V8-04.
 */
public final class SpeechReviewActivity extends AppCompatActivity
        implements SystemSpeechRecognizerController.Listener {
    private SystemSpeechRecognizerController controller;
    private TextView status;
    private BroadcastReceiver serviceTeardownReceiver;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        setContentView(R.layout.activity_speech_review);
        status = findViewById(R.id.speech_review_status);
        findViewById(R.id.speech_review_cancel).setOnClickListener(view -> {
            if (controller != null) controller.cancel();
            finish();
        });

        serviceTeardownReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent != null && FloatingVoiceService.ACTION_SERVICE_TEARDOWN
                        .equals(intent.getAction()) && !isFinishing()) {
                    finish();
                }
            }
        };
        ContextCompat.registerReceiver(this, serviceTeardownReceiver,
                new IntentFilter(FloatingVoiceService.ACTION_SERVICE_TEARDOWN),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            status.setText(R.string.speech_recognition_permission_required);
            return;
        }
        FloatingVoiceApp app = (FloatingVoiceApp) getApplication();
        controller = SystemSpeechRecognizerController.create(
                this, app.audioCaptureCoordinator(), this);
        controller.start();
    }

    @Override protected void onResume() {
        super.onResume();
        if (controller != null && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            controller.cancel();
            finish();
        }
    }

    @Override protected void onStop() {
        if (controller != null && !isChangingConfigurations()) controller.cancel();
        super.onStop();
    }

    @Override public void onOutcome(SystemSpeechRecognizerController.Outcome outcome) {
        if (status == null) return;
        int text = switch (outcome.type()) {
            case SUPPORT_CHANGED -> R.string.speech_recognition_checking;
            case LISTENING, PARTIAL_RESULT -> R.string.speech_recognition_listening;
            case PROCESSING -> R.string.speech_recognition_processing;
            case FINAL_RESULT -> R.string.speech_recognition_complete;
            case OWNERSHIP_DENIED -> R.string.speech_recognition_mic_busy;
            case KEYBOARD_REQUIRED, UNAVAILABLE -> R.string.speech_recognition_keyboard_required;
            case ERROR, DESTROY_FAILED -> R.string.speech_recognition_failed;
            case CANCELED, DESTROYED -> R.string.speech_recognition_canceled;
        };
        status.setText(text);
    }

    @Override protected void onDestroy() {
        if (serviceTeardownReceiver != null) {
            try {
                unregisterReceiver(serviceTeardownReceiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver was already removed by the platform.
            }
            serviceTeardownReceiver = null;
        }
        if (controller != null) {
            controller.destroy();
            controller = null;
        }
        super.onDestroy();
    }
}

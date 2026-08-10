package com.sidequestlab.floatingvoice;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

/**
 * Transient, activity-backed composer with normal IME behavior.
 * Draft text stays in memory and is handed only to the app-private overlay service.
 */
public final class TextComposerActivity extends AppCompatActivity {
    public static final class DraftViewModel extends ViewModel {
        String draft = "";
        final MutableLiveData<Boolean> closeAfterHandoff = new MutableLiveData<>(false);
    }

    private boolean submitInFlight;

    @SuppressLint("ClickableViewAccessibility")
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        setContentView(R.layout.activity_text_composer);

        DraftViewModel draftModel = new ViewModelProvider(this).get(DraftViewModel.class);
        if (Boolean.TRUE.equals(draftModel.closeAfterHandoff.getValue())) {
            finish();
            return;
        }
        draftModel.closeAfterHandoff.observe(this, shouldClose -> {
            if (Boolean.TRUE.equals(shouldClose) && !isFinishing()) finish();
        });

        TextComposerEditText editor = findViewById(R.id.text_composer_input);
        Button send = findViewById(R.id.text_composer_send);
        editor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        editor.setSaveEnabled(false);
        editor.setSaveFromParentEnabled(false);
        editor.setFreezesText(false);
        editor.setBackAction(this::finish);
        editor.setText(draftModel.draft);
        send.setEnabled(!draftModel.draft.trim().isEmpty());

        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                draftModel.draft = text == null ? "" : text.toString();
                send.setEnabled(!submitInFlight && !draftModel.draft.trim().isEmpty());
            }
            @Override public void afterTextChanged(Editable editable) { }
        });
        send.setOnTouchListener((view, event) -> {
            if (!isObscured(event)) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                editor.setError(getString(R.string.text_composer_obscured));
            }
            return true;
        });
        send.setOnClickListener(view -> submit(editor, send, draftModel));
        findViewById(R.id.text_composer_close).setOnClickListener(view -> finish());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                finish();
            }
        });

        editor.setSelection(editor.length());
        editor.requestFocus();
    }

    private void submit(TextComposerEditText editor, Button send, DraftViewModel draftModel) {
        String text = editor.getText() == null ? "" : editor.getText().toString().trim();
        if (text.isEmpty() || submitInFlight) return;
        submitInFlight = true;
        send.setEnabled(false);

        Intent handoff = new Intent(FloatingVoiceService.ACTION_COMPOSER_SUBMIT)
                .setPackage(getPackageName())
                .putExtra(FloatingVoiceService.EXTRA_COMPOSER_TEXT, text);
        BroadcastReceiver receipt = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                submitInFlight = false;
                if (getResultCode() == FloatingVoiceService.COMPOSER_SUBMIT_ACCEPTED) {
                    draftModel.draft = "";
                    editor.setText("");
                    draftModel.closeAfterHandoff.setValue(true);
                } else {
                    editor.setError(getString(R.string.text_composer_send_failed));
                    send.setEnabled(!draftModel.draft.trim().isEmpty());
                }
            }
        };
        try {
            sendOrderedBroadcast(handoff, null, receipt, null,
                    FloatingVoiceService.COMPOSER_SUBMIT_REJECTED, null, null);
        } catch (RuntimeException failure) {
            submitInFlight = false;
            editor.setError(getString(R.string.text_composer_send_failed));
            send.setEnabled(!draftModel.draft.trim().isEmpty());
        }
    }

    private static boolean isObscured(MotionEvent event) {
        int unsafeFlags = MotionEvent.FLAG_WINDOW_IS_OBSCURED
                | MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED;
        return (event.getFlags() & unsafeFlags) != 0;
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onDestroy() {
        if (isFinishing()) {
            sendBroadcast(new Intent(FloatingVoiceService.ACTION_COMPOSER_CLOSED)
                    .setPackage(getPackageName()));
        }
        super.onDestroy();
    }
}

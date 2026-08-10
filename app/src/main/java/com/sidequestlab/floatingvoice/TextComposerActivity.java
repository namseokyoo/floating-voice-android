package com.sidequestlab.floatingvoice;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Transient, activity-backed text composer used to validate normal IME window behavior.
 * Draft text is deliberately neither persisted nor sent anywhere.
 */
public final class TextComposerActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_text_composer);

        TextComposerEditText editor = findViewById(R.id.text_composer_input);
        editor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        editor.setSaveEnabled(false);
        editor.setSaveFromParentEnabled(false);
        editor.setFreezesText(false);
        editor.setBackAction(this::finish);

        findViewById(R.id.text_composer_close).setOnClickListener(view -> finish());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                finish();
            }
        });

        editor.requestFocus();
    }
}

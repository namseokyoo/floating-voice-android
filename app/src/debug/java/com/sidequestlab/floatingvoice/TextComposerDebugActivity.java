package com.sidequestlab.floatingvoice;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/** Debug-only manual launcher. This class is not compiled into release builds. */
public final class TextComposerDebugActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_composer_debug);
        findViewById(R.id.debug_text_composer_show).setOnClickListener(
                view -> startActivity(new Intent(this, TextComposerActivity.class)));
    }
}

package com.sidequestlab.floatingvoice;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** Debug-only manual harness. This class is not compiled into release builds. */
public final class TextComposerDebugActivity extends AppCompatActivity {
    private TextComposerOverlay overlay;
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_composer_debug);
        status = findViewById(R.id.debug_text_composer_status);
        findViewById(R.id.debug_text_composer_settings).setOnClickListener(
                view -> openOverlayPermissionSettings());
        findViewById(R.id.debug_text_composer_show).setOnClickListener(view -> showComposer());
        findViewById(R.id.debug_text_composer_close).setOnClickListener(view -> closeComposer());
        refreshStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!Settings.canDrawOverlays(this)) {
            closeComposer();
        } else {
            refreshStatus();
        }
    }

    @Override protected void onDestroy() {
        closeComposer();
        super.onDestroy();
    }

    private void showComposer() {
        closeComposer();
        if (!Settings.canDrawOverlays(this)) {
            status.setText(R.string.debug_text_composer_permission_required);
            return;
        }

        TextComposerOverlay next = new TextComposerOverlay(this, this::onComposerDismissed);
        if (next.show()) {
            overlay = next;
            status.setText(R.string.debug_text_composer_showing);
        } else {
            next.close();
            status.setText(R.string.debug_text_composer_failed);
        }
    }

    private void onComposerDismissed(TextComposerOverlay dismissedOverlay) {
        if (overlay == dismissedOverlay) overlay = null;
        refreshStatus();
    }

    private void openOverlayPermissionSettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void closeComposer() {
        TextComposerOverlay current = overlay;
        overlay = null;
        if (current != null) current.close();
        if (status != null) refreshStatus();
    }

    private void refreshStatus() {
        if (!Settings.canDrawOverlays(this)) {
            status.setText(R.string.debug_text_composer_permission_required);
        } else if (overlay != null && overlay.isShowing()) {
            status.setText(R.string.debug_text_composer_showing);
        } else {
            status.setText(R.string.debug_text_composer_ready);
        }
    }
}

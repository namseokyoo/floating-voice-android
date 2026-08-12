package com.sidequestlab.floatingvoice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.sidequestlab.floatingvoice.core.Destination;
import com.sidequestlab.floatingvoice.core.DestinationCatalog;
import com.sidequestlab.floatingvoice.core.DestinationScope;

/** Secure non-exported, activity-backed destination picker used by the overlay service. */
public final class DestinationPickerActivity extends AppCompatActivity {
    private static final float PICKER_MAX_HEIGHT_FRACTION = 0.70f;
    private long requestId;
    private DestinationScope scope;
    private boolean resultSent;
    private BroadcastReceiver serviceTeardownReceiver;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        setContentView(R.layout.activity_destination_picker);
        View sheet = findViewById(R.id.destination_picker_sheet);
        sheet.post(() -> {
            int maxHeight = Math.round(getResources().getDisplayMetrics().heightPixels
                    * PICKER_MAX_HEIGHT_FRACTION);
            android.view.ViewGroup.LayoutParams params = sheet.getLayoutParams();
            params.height = maxHeight;
            sheet.setLayoutParams(params);
        });

        requestId = getIntent().getLongExtra(
                FloatingVoiceService.EXTRA_DESTINATION_PICKER_REQUEST_ID, 0L);
        String scopeName = getIntent().getStringExtra(
                FloatingVoiceService.EXTRA_DESTINATION_SCOPE);
        try {
            scope = DestinationScope.valueOf(scopeName == null ? "" : scopeName);
        } catch (IllegalArgumentException error) {
            finish();
            return;
        }
        if (requestId <= 0L || (scope != DestinationScope.DEFAULT
                && scope != DestinationScope.NEXT_ONE
                && scope != DestinationScope.CURRENT_RECORDING)) {
            finish();
            return;
        }

        serviceTeardownReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent != null && FloatingVoiceService.ACTION_SERVICE_TEARDOWN
                        .equals(intent.getAction()) && !isFinishing()) finish();
            }
        };
        ContextCompat.registerReceiver(this, serviceTeardownReceiver,
                new IntentFilter(FloatingVoiceService.ACTION_SERVICE_TEARDOWN),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        findViewById(R.id.destination_picker_scrim).setOnClickListener(view -> finish());
        findViewById(R.id.destination_picker_sheet).setOnClickListener(view -> { });
        findViewById(R.id.destination_picker_close).setOnClickListener(view -> finish());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { finish(); }
        });
        render();
    }

    private void render() {
        TelegramRepository telegram = ((FloatingVoiceApp) getApplication()).telegram();
        DestinationCatalog catalog = telegram.destinationCatalog();
        long accountUserId = telegram.authenticatedAccountUserId();
        String selectedLocalId = getIntent().getStringExtra(
                FloatingVoiceService.EXTRA_DESTINATION_LOCAL_ID);

        TextView title = findViewById(R.id.destination_picker_title);
        title.setText(scope == DestinationScope.CURRENT_RECORDING
                ? R.string.destination_picker_recording_title
                : scope == DestinationScope.DEFAULT
                ? R.string.destination_picker_default_title
                : R.string.destination_picker_idle_title);
        TextView supporting = findViewById(R.id.destination_picker_supporting);
        supporting.setText(scope == DestinationScope.DEFAULT
                ? R.string.destination_picker_default_supporting
                : R.string.destination_picker_supporting);
        LinearLayout list = findViewById(R.id.destination_picker_list);
        TextView empty = findViewById(R.id.destination_picker_empty);
        list.removeAllViews();

        int selectableCount = 0;
        for (Destination destination : catalog.destinations()) {
            View row = LayoutInflater.from(this).inflate(
                    R.layout.destination_picker_row, list, false);
            boolean selectable = destination.selectableBy(accountUserId);
            boolean selected = destination.localId().equals(selectedLocalId);
            bindRow(row, destination, selectable, selected);
            if (selectable) selectableCount++;
            list.addView(row);
        }
        empty.setVisibility(selectableCount == 0 ? View.VISIBLE : View.GONE);
        boolean largeFont = getResources().getConfiguration().fontScale >= 1.5f;
        supporting.setVisibility(largeFont ? View.GONE : View.VISIBLE);
    }

    private void bindRow(View row, Destination destination,
                         boolean selectable, boolean selected) {
        TextView alias = row.findViewById(R.id.destination_picker_row_alias);
        TextView identity = row.findViewById(R.id.destination_picker_row_identity);
        TextView status = row.findViewById(R.id.destination_picker_row_status);
        TextView check = row.findViewById(R.id.destination_picker_row_check);
        String username = destination.resolvedUsername().isEmpty()
                ? destination.configuredUsername() : destination.resolvedUsername();

        alias.setText(destination.userAlias());
        identity.setText(getString(R.string.destination_item_identity_format, username));
        status.setText(selectable
                ? R.string.destination_picker_verified_status
                : R.string.destination_picker_disabled_status);
        status.setVisibility(getResources().getConfiguration().fontScale >= 1.5f
                ? View.GONE : View.VISIBLE);
        check.setText(R.string.destination_picker_selected_mark);
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        check.setContentDescription(selected
                ? getString(R.string.destination_picker_selected_description,
                        destination.userAlias()) : null);

        row.setEnabled(selectable);
        row.setAlpha(selectable ? 1f : 0.48f);
        row.setFilterTouchesWhenObscured(true);
        row.setContentDescription(destination.userAlias() + ", "
                + getString(selectable ? R.string.destination_picker_verified_status
                        : R.string.destination_picker_disabled_status));
        if (selectable) {
            row.setOnClickListener(view -> submit(destination.localId()));
        }
    }

    private void submit(String localId) {
        if (resultSent) return;
        resultSent = true;
        sendBroadcast(new Intent(FloatingVoiceService.ACTION_DESTINATION_PICKED)
                .setPackage(getPackageName())
                .putExtra(FloatingVoiceService.EXTRA_DESTINATION_PICKER_REQUEST_ID, requestId)
                .putExtra(FloatingVoiceService.EXTRA_DESTINATION_LOCAL_ID, localId));
        finish();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onDestroy() {
        if (serviceTeardownReceiver != null) {
            try { unregisterReceiver(serviceTeardownReceiver); }
            catch (IllegalArgumentException ignored) { }
            serviceTeardownReceiver = null;
        }
        if (isFinishing() && !resultSent && requestId > 0L) {
            sendBroadcast(new Intent(FloatingVoiceService.ACTION_DESTINATION_PICKER_CLOSED)
                    .setPackage(getPackageName())
                    .putExtra(FloatingVoiceService.EXTRA_DESTINATION_PICKER_REQUEST_ID, requestId));
        }
        super.onDestroy();
    }
}

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
import com.sidequestlab.floatingvoice.core.InputMode;
import com.sidequestlab.floatingvoice.core.InputOutputPolicy;
import com.sidequestlab.floatingvoice.core.OutputRoute;

/** Secure non-exported, activity-backed destination picker used by the overlay service. */
public final class DestinationPickerActivity extends AppCompatActivity {
    private static final float PICKER_MAX_HEIGHT_FRACTION = 0.70f;
    private long requestId;
    private DestinationScope scope;
    private InputMode inputMode;
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
        String inputModeName = getIntent().getStringExtra(
                FloatingVoiceService.EXTRA_INPUT_MODE);
        if (inputModeName != null) {
            try {
                inputMode = InputMode.valueOf(inputModeName);
            } catch (IllegalArgumentException error) {
                finish();
                return;
            }
        } else {
            String scopeName = getIntent().getStringExtra(
                    FloatingVoiceService.EXTRA_DESTINATION_SCOPE);
            try {
                scope = DestinationScope.valueOf(scopeName == null ? "" : scopeName);
            } catch (IllegalArgumentException error) {
                finish();
                return;
            }
        }
        boolean validDestinationScope = scope == DestinationScope.DEFAULT
                || scope == DestinationScope.NEXT_ONE
                || scope == DestinationScope.CURRENT_RECORDING;
        boolean validOutputMode = inputMode == InputMode.RAW_VOICE;
        if (requestId <= 0L || (!validDestinationScope && !validOutputMode)) {
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
        title.setText(inputMode == InputMode.RAW_VOICE
                ? R.string.voice_output_picker_title
                : scope == DestinationScope.CURRENT_RECORDING
                ? R.string.destination_picker_recording_title
                : scope == DestinationScope.DEFAULT
                ? R.string.destination_picker_default_title
                : R.string.destination_picker_idle_title);
        TextView supporting = findViewById(R.id.destination_picker_supporting);
        supporting.setText(inputMode == InputMode.RAW_VOICE
                ? R.string.voice_output_picker_supporting
                : scope == DestinationScope.DEFAULT
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
            bindRow(row, destination, selectable, selected,
                    inputMode == InputMode.RAW_VOICE
                            ? OutputRoute.TELEGRAM_VOICE : null);
            if (selectable) selectableCount++;
            list.addView(row);
        }
        if (inputMode == InputMode.RAW_VOICE) {
            addOutputRow(list, OutputRoute.SYSTEM_AUDIO_SHARE,
                    R.string.voice_output_android_share,
                    R.string.voice_output_android_share_supporting);
            addOutputRow(list, OutputRoute.LOCAL_AUDIO_ARCHIVE,
                    R.string.voice_output_local_archive,
                    R.string.voice_output_local_archive_supporting);
            selectableCount += 2;
        }
        empty.setVisibility(selectableCount == 0 ? View.VISIBLE : View.GONE);
        boolean largeFont = getResources().getConfiguration().fontScale >= 1.5f;
        supporting.setVisibility(largeFont ? View.GONE : View.VISIBLE);
    }

    private void bindRow(View row, Destination destination,
                         boolean selectable, boolean selected,
                         OutputRoute oneOperationRoute) {
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
            row.setOnClickListener(view -> {
                if (oneOperationRoute == null) {
                    submitDestination(destination.localId());
                } else {
                    submitOutput(oneOperationRoute, destination.localId());
                }
            });
        }
    }

    private void addOutputRow(LinearLayout list, OutputRoute route,
                              int titleResource, int supportingResource) {
        if (!InputOutputPolicy.allows(InputMode.RAW_VOICE, route)) return;
        View row = LayoutInflater.from(this).inflate(
                R.layout.destination_picker_row, list, false);
        TextView alias = row.findViewById(R.id.destination_picker_row_alias);
        TextView identity = row.findViewById(R.id.destination_picker_row_identity);
        TextView status = row.findViewById(R.id.destination_picker_row_status);
        TextView check = row.findViewById(R.id.destination_picker_row_check);
        alias.setText(titleResource);
        identity.setText(supportingResource);
        status.setVisibility(View.GONE);
        check.setVisibility(View.INVISIBLE);
        row.setFilterTouchesWhenObscured(true);
        row.setContentDescription(getString(titleResource) + ", "
                + getString(supportingResource));
        row.setOnClickListener(view -> submitOutput(route, null));
        list.addView(row);
    }

    private void submitDestination(String localId) {
        if (resultSent) return;
        resultSent = true;
        sendBroadcast(new Intent(FloatingVoiceService.ACTION_DESTINATION_PICKED)
                .setPackage(getPackageName())
                .putExtra(FloatingVoiceService.EXTRA_DESTINATION_PICKER_REQUEST_ID, requestId)
                .putExtra(FloatingVoiceService.EXTRA_DESTINATION_LOCAL_ID, localId));
        finish();
    }

    private void submitOutput(OutputRoute route, String localId) {
        if (resultSent || !InputOutputPolicy.allows(InputMode.RAW_VOICE, route)) return;
        resultSent = true;
        Intent result = new Intent(FloatingVoiceService.ACTION_DESTINATION_PICKED)
                .setPackage(getPackageName())
                .putExtra(FloatingVoiceService.EXTRA_DESTINATION_PICKER_REQUEST_ID, requestId)
                .putExtra(FloatingVoiceService.EXTRA_OUTPUT_ROUTE, route.name());
        if (localId != null) {
            result.putExtra(FloatingVoiceService.EXTRA_DESTINATION_LOCAL_ID, localId);
        }
        sendBroadcast(result);
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

package com.sidequestlab.floatingvoice;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.sidequestlab.floatingvoice.core.Destination;
import com.sidequestlab.floatingvoice.core.DestinationCatalog;
import com.sidequestlab.floatingvoice.core.OutputRoute;

import java.util.ArrayList;
import java.util.List;

/**
 * Transient, activity-backed composer with normal IME behavior.
 * Draft text stays in memory and is handed only to the app-private overlay service.
 */
public final class TextComposerActivity extends AppCompatActivity {
    public static final class DraftViewModel extends ViewModel {
        String draft = "";
        OutputRoute selectedRoute = OutputRoute.TELEGRAM_TEXT;
        String selectedLocalId;
        boolean outputInitialized;
        final MutableLiveData<Boolean> closeAfterHandoff = new MutableLiveData<>(false);
    }

    private boolean submitInFlight;
    private boolean chooserAwaitingReturn;
    private AndroidShareController shareController;
    private TelegramRepository telegram;
    private BroadcastReceiver serviceTeardownReceiver;

    @SuppressLint("ClickableViewAccessibility")
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        setContentView(R.layout.activity_text_composer);
        serviceTeardownReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent != null && FloatingVoiceService.ACTION_SERVICE_TEARDOWN
                        .equals(intent.getAction()) && !isFinishing()) finish();
            }
        };
        ContextCompat.registerReceiver(this, serviceTeardownReceiver,
                new IntentFilter(FloatingVoiceService.ACTION_SERVICE_TEARDOWN),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        DraftViewModel draftModel = new ViewModelProvider(this).get(DraftViewModel.class);
        if (Boolean.TRUE.equals(draftModel.closeAfterHandoff.getValue())) {
            finish();
            return;
        }
        draftModel.closeAfterHandoff.observe(this, shouldClose -> {
            if (Boolean.TRUE.equals(shouldClose) && !isFinishing()) finish();
        });

        telegram = ((FloatingVoiceApp) getApplication()).telegram();
        shareController = AndroidShareController.create(
                this, getString(R.string.text_composer_share_chooser_title));
        initializeOutput(draftModel);

        TextComposerEditText editor = findViewById(R.id.text_composer_input);
        Button output = findViewById(R.id.text_composer_output);
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
        output.setOnClickListener(view -> showOutputDialog(draftModel, output, send));
        send.setOnClickListener(view -> submit(editor, output, send, draftModel));
        findViewById(R.id.text_composer_close).setOnClickListener(view -> finish());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                finish();
            }
        });

        editor.setSelection(editor.length());
        editor.requestFocus();
        refreshOutputButton(draftModel, output, send);
        if (savedInstanceState == null && getIntent().getBooleanExtra(
                FloatingVoiceService.EXTRA_OPEN_OUTPUT_CHOOSER, false)) {
            output.post(() -> {
                if (!isFinishing()) showOutputDialog(draftModel, output, send);
            });
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (chooserAwaitingReturn && shareController != null) {
            shareController.chooserReturned();
            chooserAwaitingReturn = false;
            TextComposerEditText editor = findViewById(R.id.text_composer_input);
            Button send = findViewById(R.id.text_composer_send);
            send.setEnabled(!submitInFlight && editor.getText() != null
                    && !editor.getText().toString().trim().isEmpty());
        }
    }

    private void submit(TextComposerEditText editor, Button output,
                        Button send, DraftViewModel draftModel) {
        String text = editor.getText() == null ? "" : editor.getText().toString().trim();
        if (text.isEmpty() || submitInFlight) return;
        if (draftModel.selectedRoute == OutputRoute.SYSTEM_TEXT_SHARE) {
            AndroidShareController.Result result = shareController.shareText(text);
            if (result == AndroidShareController.Result.CHOOSER_OPENED_USER_CONFIRMATION_REQUIRED) {
                chooserAwaitingReturn = true;
                send.setEnabled(false);
                return;
            }
            editor.setError(getString(result == AndroidShareController.Result.NO_HANDLER
                    ? R.string.text_composer_share_no_handler
                    : R.string.text_composer_share_failed));
            send.setEnabled(true);
            return;
        }
        if (draftModel.selectedRoute != OutputRoute.TELEGRAM_TEXT
                || draftModel.selectedLocalId == null) {
            editor.setError(getString(R.string.text_composer_destination_required));
            return;
        }
        submitInFlight = true;
        output.setEnabled(false);
        send.setEnabled(false);

        Intent handoff = new Intent(FloatingVoiceService.ACTION_COMPOSER_SUBMIT)
                .setPackage(getPackageName())
                .putExtra(FloatingVoiceService.EXTRA_COMPOSER_TEXT, text)
                .putExtra(FloatingVoiceService.EXTRA_DESTINATION_LOCAL_ID,
                        draftModel.selectedLocalId);
        BroadcastReceiver receipt = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                submitInFlight = false;
                if (getResultCode() == FloatingVoiceService.COMPOSER_SUBMIT_ACCEPTED) {
                    draftModel.draft = "";
                    editor.setText("");
                    draftModel.closeAfterHandoff.setValue(true);
                } else {
                    editor.setError(getString(R.string.text_composer_send_failed));
                    output.setEnabled(true);
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
            output.setEnabled(true);
            send.setEnabled(!draftModel.draft.trim().isEmpty());
        }
    }

    private void initializeOutput(DraftViewModel draftModel) {
        if (draftModel.outputInitialized) return;
        draftModel.outputInitialized = true;
        DestinationCatalog catalog = telegram == null
                ? DestinationCatalog.empty() : telegram.destinationCatalog();
        long accountUserId = telegram == null ? 0L : telegram.authenticatedAccountUserId();
        draftModel.selectedLocalId = catalog.defaultLocalId()
                .filter(localId -> catalog.selectable(localId, accountUserId).isPresent())
                .orElse(null);
    }

    private void showOutputDialog(DraftViewModel draftModel, Button output, Button send) {
        if (submitInFlight || telegram == null) return;
        DestinationCatalog catalog = telegram.destinationCatalog();
        long accountUserId = telegram.authenticatedAccountUserId();
        List<Destination> selectable = new ArrayList<>();
        for (Destination destination : catalog.destinations()) {
            if (destination.selectableBy(accountUserId)) selectable.add(destination);
        }
        CharSequence[] labels = new CharSequence[selectable.size() + 1];
        int checked = draftModel.selectedRoute == OutputRoute.SYSTEM_TEXT_SHARE
                ? selectable.size() : -1;
        for (int index = 0; index < selectable.size(); index++) {
            Destination destination = selectable.get(index);
            labels[index] = getString(R.string.text_composer_telegram_output_format,
                    destinationLabel(destination));
            if (draftModel.selectedRoute == OutputRoute.TELEGRAM_TEXT
                    && destination.localId().equals(draftModel.selectedLocalId)) {
                checked = index;
            }
        }
        labels[selectable.size()] = getString(R.string.text_composer_android_share_output);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.text_composer_output_dialog_title)
                .setSingleChoiceItems(labels, checked, null)
                .setNegativeButton(R.string.destination_picker_close, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getListView().setOnItemClickListener(
                (parent, view, position, id) -> {
                    if (position == selectable.size()) {
                        draftModel.selectedRoute = OutputRoute.SYSTEM_TEXT_SHARE;
                        draftModel.selectedLocalId = null;
                    } else {
                        draftModel.selectedRoute = OutputRoute.TELEGRAM_TEXT;
                        draftModel.selectedLocalId = selectable.get(position).localId();
                    }
                    refreshOutputButton(draftModel, output, send);
                    dialog.dismiss();
                }));
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            if (Build.VERSION.SDK_INT >= 31) dialog.getWindow().setHideOverlayWindows(true);
        }
    }

    private void refreshOutputButton(DraftViewModel draftModel, Button output, Button send) {
        if (draftModel.selectedRoute == OutputRoute.SYSTEM_TEXT_SHARE) {
            output.setText(R.string.text_composer_android_share_output);
            send.setText(R.string.text_composer_share);
            return;
        }
        Destination destination = telegram == null || draftModel.selectedLocalId == null
                ? null : telegram.destinationCatalog().find(draftModel.selectedLocalId).orElse(null);
        output.setText(destination == null
                ? getString(R.string.text_composer_destination_required)
                : getString(R.string.text_composer_telegram_output_format,
                        destinationLabel(destination)));
        send.setText(R.string.text_composer_send);
    }

    private static String destinationLabel(Destination destination) {
        if (!destination.userAlias().isBlank()) return destination.userAlias();
        if (!destination.resolvedTitle().isBlank()) return destination.resolvedTitle();
        return "@" + destination.resolvedUsername();
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
        if (serviceTeardownReceiver != null) {
            try { unregisterReceiver(serviceTeardownReceiver); }
            catch (IllegalArgumentException ignored) { }
            serviceTeardownReceiver = null;
        }
        if (isFinishing()) {
            sendBroadcast(new Intent(FloatingVoiceService.ACTION_COMPOSER_CLOSED)
                    .setPackage(getPackageName()));
        }
        super.onDestroy();
    }
}

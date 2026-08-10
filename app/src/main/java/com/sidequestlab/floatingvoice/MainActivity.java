package com.sidequestlab.floatingvoice;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.sidequestlab.floatingvoice.core.AppConfig;
import com.sidequestlab.floatingvoice.core.OverlaySizePreset;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends AppCompatActivity implements TelegramRepository.Listener {
    private static final int PERMISSION_REQUEST = 100;

    private enum LanguageOption {
        SYSTEM(""), KOREAN("ko"), ENGLISH("en");

        private final String languageTag;

        LanguageOption(String languageTag) { this.languageTag = languageTag; }

        private static LanguageOption fromLanguage(String language) {
            for (LanguageOption option : values()) {
                if (option.languageTag.equals(language)) return option;
            }
            return SYSTEM;
        }

        private static LanguageOption fromPosition(int position) {
            LanguageOption[] options = values();
            return position >= 0 && position < options.length ? options[position] : SYSTEM;
        }
    }

    private EditText apiId;
    private EditText apiHash;
    private EditText phone;
    private EditText emailAddress;
    private EditText emailCode;
    private EditText code;
    private EditText password;
    private EditText username;
    private TextView authStatus;
    private TextView accountStatus;
    private TextView targetStatus;
    private TextView permissionStatus;
    private TextView operationStatus;
    private TelegramRepository telegram;
    private SecureSettingsStore settingsStore;
    private OverlayUiPreferences overlayUiPreferences;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        overlayUiPreferences = new OverlayUiPreferences(this);
        setupLanguageSelector();
        setupOverlaySizeSelector();

        FloatingVoiceApp app = (FloatingVoiceApp) getApplication();
        telegram = app.telegram();
        settingsStore = app.settings();
        settingsStore.loadConfig().ifPresent(this::showConfig);
        app.startTelegramIfConfigured();
        telegram.addListener(this);

        findViewById(R.id.save_start).setOnClickListener(v -> saveAndStart());
        findViewById(R.id.submit_phone).setOnClickListener(v ->
                telegram.submitPhoneNumber(phone.getText().toString().replaceAll("[\\s()-]", "")));
        findViewById(R.id.submit_email_address).setOnClickListener(v -> {
            telegram.submitEmailAddress(emailAddress.getText().toString());
            emailAddress.setText("");
        });
        findViewById(R.id.submit_email_code).setOnClickListener(v -> {
            telegram.submitEmailCode(emailCode.getText().toString());
            emailCode.setText("");
        });
        findViewById(R.id.submit_code).setOnClickListener(v -> {
            telegram.submitCode(code.getText().toString());
            code.setText("");
        });
        findViewById(R.id.submit_password).setOnClickListener(v -> {
            telegram.submitPassword(password.getText().toString());
            password.setText("");
        });
        findViewById(R.id.resolve_bot).setOnClickListener(v -> telegram.resolveConfiguredBot());
        findViewById(R.id.permissions).setOnClickListener(v -> requestRequiredPermissions());
        findViewById(R.id.start_overlay).setOnClickListener(v -> startOverlay());
        findViewById(R.id.stop_overlay).setOnClickListener(v -> stopOverlay());
        findViewById(R.id.logout).setOnClickListener(v -> confirmLogout());
        refreshPermissionStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshPermissionStatus();
    }

    @Override protected void onDestroy() {
        if (telegram != null) telegram.removeListener(this);
        super.onDestroy();
    }

    private void bindViews() {
        apiId = findViewById(R.id.api_id);
        apiHash = findViewById(R.id.api_hash);
        phone = findViewById(R.id.phone);
        emailAddress = findViewById(R.id.email_address);
        emailCode = findViewById(R.id.email_code);
        code = findViewById(R.id.auth_code);
        password = findViewById(R.id.password);
        username = findViewById(R.id.bot_username);
        authStatus = findViewById(R.id.auth_status);
        accountStatus = findViewById(R.id.account_status);
        targetStatus = findViewById(R.id.target_status);
        permissionStatus = findViewById(R.id.permission_status);
        operationStatus = findViewById(R.id.operation_status);
        apiId.setInputType(InputType.TYPE_CLASS_NUMBER);
    }

    private void setupLanguageSelector() {
        Spinner selector = findViewById(R.id.language_selector);
        LocaleListCompat current = AppCompatDelegate.getApplicationLocales();
        String language = current.isEmpty() || current.get(0) == null
                ? "" : current.get(0).getLanguage();
        selector.setSelection(LanguageOption.fromLanguage(language).ordinal(), false);
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                 int position, long id) {
                LanguageOption option = LanguageOption.fromPosition(position);
                LocaleListCompat requested = LocaleListCompat.forLanguageTags(option.languageTag);
                if (!requested.equals(AppCompatDelegate.getApplicationLocales())) {
                    AppCompatDelegate.setApplicationLocales(requested);
                    ((FloatingVoiceApp) getApplication()).telegram().refreshLocalizedState();
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void setupOverlaySizeSelector() {
        Spinner selector = findViewById(R.id.overlay_size_selector);
        selector.setSelection(overlayUiPreferences.sizePreset().ordinal(), false);
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                 int position, long id) {
                overlayUiPreferences.setSizePreset(OverlaySizePreset.fromPosition(position));
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void saveAndStart() {
        AppConfig.ValidationResult result = AppConfig.validate(
                apiId.getText().toString(), apiHash.getText().toString(),
                phone.getText().toString(), username.getText().toString());
        if (!result.isValid()) {
            List<String> messages = new ArrayList<>();
            for (AppConfig.ValidationError error : result.errors()) {
                messages.add(getString(validationErrorResource(error)));
            }
            operationStatus.setText(String.join("\n", messages));
            return;
        }
        AppConfig config = result.config();
        settingsStore.saveConfig(config);
        showConfig(config);
        telegram.start(config);
        operationStatus.setText(R.string.settings_saved_no_message);
    }

    private static int validationErrorResource(AppConfig.ValidationError error) {
        switch (error) {
            case INVALID_API_ID: return R.string.validation_invalid_api_id;
            case INVALID_API_HASH: return R.string.validation_invalid_api_hash;
            case INVALID_PHONE_NUMBER: return R.string.validation_invalid_phone;
            case INVALID_BOT_USERNAME: return R.string.validation_invalid_username;
            default: throw new IllegalArgumentException(error.name());
        }
    }

    private void showConfig(AppConfig config) {
        apiId.setText(Integer.toString(config.apiId()));
        apiHash.setText(config.apiHash());
        phone.setText(config.phoneNumber());
        username.setText(config.botUsername());
    }

    private void requestRequiredPermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), PERMISSION_REQUEST);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Intent overlay = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(overlay);
            return;
        }
        refreshPermissionStatus();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                      int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) refreshPermissionStatus();
    }

    private boolean permissionsGranted() {
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean notifications = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        return mic && notifications && Settings.canDrawOverlays(this);
    }

    private void refreshPermissionStatus() {
        if (permissionStatus == null) return;
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean notification = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText(getString(R.string.permission_status_format,
                yesNo(Settings.canDrawOverlays(this)), yesNo(mic), yesNo(notification)));
    }

    private void startOverlay() {
        if (!permissionsGranted()) {
            operationStatus.setText(R.string.permissions_required_first);
            requestRequiredPermissions();
            return;
        }
        if (!telegram.isReadyWithTarget()) {
            operationStatus.setText(R.string.telegram_target_required_first);
            return;
        }
        Intent service = new Intent(this, FloatingVoiceService.class)
                .setAction(FloatingVoiceService.ACTION_START);
        try {
            startForegroundService(service);
            operationStatus.setText(R.string.overlay_started);
        } catch (RuntimeException e) {
            operationStatus.setText(getString(R.string.overlay_start_failed,
                    e.getClass().getSimpleName()));
        }
    }

    private void stopOverlay() {
        stopService(new Intent(this, FloatingVoiceService.class));
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.logout_title)
                .setMessage(R.string.logout_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.logout, (dialog, which) -> {
                    stopOverlay();
                    telegram.logOutAndRevokeSession();
                })
                .show();
    }

    private String yesNo(boolean value) {
        return getString(value ? R.string.permission_granted : R.string.permission_needed);
    }

    @Override public void onStatus(String status) {
        runOnUiThread(() -> operationStatus.setText(status));
    }

    @Override public void onAuthStage(TelegramRepository.AuthStage stage) {
        runOnUiThread(() -> {
            authStatus.setText(getString(R.string.auth_status_format, authStageLabel(stage)));
            updateAuthControls(stage);
        });
    }

    @Override public void onAccountChanged(String account) {
        runOnUiThread(() -> accountStatus.setText(account));
    }

    @Override public void onTargetChanged(TargetChat target) {
        runOnUiThread(() -> targetStatus.setText(target == null
                ? getString(R.string.target_not_confirmed)
                : getString(R.string.target_confirmed_format, target)));
    }

    private void updateAuthControls(TelegramRepository.AuthStage stage) {
        boolean phoneStep = stage == TelegramRepository.AuthStage.PHONE;
        boolean codeStep = stage == TelegramRepository.AuthStage.CODE;
        boolean passwordStep = stage == TelegramRepository.AuthStage.PASSWORD;
        boolean emailAddressStep = stage == TelegramRepository.AuthStage.EMAIL_ADDRESS;
        boolean emailCodeStep = stage == TelegramRepository.AuthStage.EMAIL_CODE;

        findViewById(R.id.submit_phone).setVisibility(phoneStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.auth_code).setVisibility(codeStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.submit_code).setVisibility(codeStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.password).setVisibility(passwordStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.submit_password).setVisibility(passwordStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.email_help).setVisibility(
                emailAddressStep || emailCodeStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.email_address).setVisibility(emailAddressStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.submit_email_address).setVisibility(
                emailAddressStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.email_code).setVisibility(emailCodeStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.submit_email_code).setVisibility(emailCodeStep ? View.VISIBLE : View.GONE);
    }

    private String authStageLabel(TelegramRepository.AuthStage stage) {
        switch (stage) {
            case NOT_STARTED: return getString(R.string.auth_stage_not_started);
            case PARAMETERS: return getString(R.string.auth_stage_parameters);
            case PHONE: return getString(R.string.auth_stage_phone);
            case EMAIL_ADDRESS: return getString(R.string.auth_stage_email_address);
            case EMAIL_CODE: return getString(R.string.auth_stage_email_code);
            case CODE: return getString(R.string.auth_stage_code);
            case PASSWORD: return getString(R.string.auth_stage_password);
            case READY: return getString(R.string.auth_stage_ready);
            case LOGGING_OUT: return getString(R.string.auth_stage_logging_out);
            case CLOSED: return getString(R.string.auth_stage_closed);
            case UNSUPPORTED: return getString(R.string.auth_stage_unsupported);
            default: throw new IllegalArgumentException(stage.name());
        }
    }
}

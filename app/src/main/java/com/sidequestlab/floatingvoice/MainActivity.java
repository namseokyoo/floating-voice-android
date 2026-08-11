package com.sidequestlab.floatingvoice;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.sidequestlab.floatingvoice.core.AppConfig;
import com.sidequestlab.floatingvoice.core.DashboardReadiness;
import com.sidequestlab.floatingvoice.core.OverlayColorPreset;
import com.sidequestlab.floatingvoice.core.OverlaySizePreset;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends AppCompatActivity implements TelegramRepository.Listener {
    private static final int PERMISSION_REQUEST = 100;
    private static final String PERMISSION_PREFS = "permission_request_history";

    private enum Page { HOME, CONNECTION, SETTINGS }

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
    private EditText connectionPhone;
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
    private TextView permissionMicrophoneStatus;
    private TextView permissionNotificationStatus;
    private TextView operationStatus;
    private TextView connectionStatus;
    private TextView dashboardEyebrow;
    private TextView dashboardHeadline;
    private TextView dashboardSupporting;
    private TextView dashboardTarget;
    private TextView dashboardActionNote;
    private TextView connectionStep;
    private TextView connectionTitle;
    private TextView connectionSupporting;
    private TextView appBarTitle;
    private ImageView dashboardIcon;
    private View homeScreen;
    private View connectionScreen;
    private View settingsScreen;
    private View navigationButton;
    private View settingsButton;
    private View operationStatusPanel;
    private View connectionSettingsCard;
    private View authenticationCard;
    private View targetPermissionsCard;
    private View targetStep;
    private View permissionStep;
    private View connectionComplete;
    private MaterialButton dashboardPrimaryAction;
    private MaterialButton connectionSettingsToggle;
    private TelegramRepository telegram;
    private SecureSettingsStore settingsStore;
    private OverlayUiPreferences overlayUiPreferences;
    private BroadcastReceiver serviceStateReceiver;
    private AppConfig savedConfig;
    private DashboardReadiness.State dashboardState = DashboardReadiness.State.CONNECT_TELEGRAM;
    private Page currentPage = Page.HOME;
    private boolean editingApiSettings;
    private boolean statusCallbacksReady;
    private String persistentStatus;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        applySystemBarInsets();
        bindViews();
        overlayUiPreferences = new OverlayUiPreferences(this);
        setupLanguageSelector();
        setupOverlaySizeSelector();
        setupOverlayColorSelector();

        FloatingVoiceApp app = (FloatingVoiceApp) getApplication();
        telegram = app.telegram();
        settingsStore = app.settings();
        savedConfig = settingsStore.loadConfig().orElse(null);
        if (savedConfig != null) showConfig(savedConfig);

        serviceStateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent != null && FloatingVoiceService.ACTION_RUNNING_STATE_CHANGED
                        .equals(intent.getAction())) {
                    presentLocalStatus(FloatingVoiceService.isRunning()
                            ? R.string.overlay_started : R.string.overlay_stopped, false, true);
                    refreshUi();
                }
            }
        };

        bindActions();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (currentPage == Page.HOME) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                } else {
                    showPage(Page.HOME, false);
                }
            }
        });

        app.startTelegramIfConfigured();
        telegram.addListener(this);
        statusCallbacksReady = true;
        refreshPermissionStatus();
        showPage(Page.HOME, false);
    }

    private void bindViews() {
        apiId = findViewById(R.id.api_id);
        apiHash = findViewById(R.id.api_hash);
        connectionPhone = findViewById(R.id.connection_phone);
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
        permissionMicrophoneStatus = findViewById(R.id.permission_microphone_status);
        permissionNotificationStatus = findViewById(R.id.permission_notification_status);
        operationStatus = findViewById(R.id.operation_status);
        connectionStatus = findViewById(R.id.connection_status);
        dashboardEyebrow = findViewById(R.id.dashboard_eyebrow);
        dashboardHeadline = findViewById(R.id.dashboard_headline);
        dashboardSupporting = findViewById(R.id.dashboard_supporting);
        dashboardTarget = findViewById(R.id.dashboard_target);
        dashboardActionNote = findViewById(R.id.dashboard_action_note);
        connectionStep = findViewById(R.id.connection_step);
        connectionTitle = findViewById(R.id.connection_title);
        connectionSupporting = findViewById(R.id.connection_supporting);
        appBarTitle = findViewById(R.id.app_bar_title);
        dashboardIcon = findViewById(R.id.dashboard_icon);
        homeScreen = findViewById(R.id.home_screen);
        connectionScreen = findViewById(R.id.connection_screen);
        settingsScreen = findViewById(R.id.settings_screen);
        navigationButton = findViewById(R.id.navigation_button);
        settingsButton = findViewById(R.id.settings_button);
        operationStatusPanel = findViewById(R.id.operation_status_panel);
        connectionSettingsCard = findViewById(R.id.connection_settings_card);
        authenticationCard = findViewById(R.id.authentication_card);
        targetPermissionsCard = findViewById(R.id.target_permissions_card);
        targetStep = findViewById(R.id.target_step);
        permissionStep = findViewById(R.id.permission_step);
        connectionComplete = findViewById(R.id.connection_complete);
        dashboardPrimaryAction = findViewById(R.id.dashboard_primary_action);
        connectionSettingsToggle = findViewById(R.id.connection_settings_toggle);
        apiId.setInputType(InputType.TYPE_CLASS_NUMBER);
    }

    private void bindActions() {
        findViewById(R.id.save_start).setOnClickListener(v -> saveConnectionAndStart());
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
        findViewById(R.id.resolve_bot).setOnClickListener(v -> saveTargetAndResolve());
        findViewById(R.id.permissions).setOnClickListener(v -> requestRequiredPermissions());
        findViewById(R.id.connection_done).setOnClickListener(v -> showPage(Page.HOME, false));
        findViewById(R.id.restart_auth).setOnClickListener(
                v -> telegram.restartCurrentConfiguration());
        findViewById(R.id.dismiss_error).setOnClickListener(v -> dismissPersistentStatus());
        findViewById(R.id.logout).setOnClickListener(v -> confirmLogout());
        dashboardPrimaryAction.setOnClickListener(v -> handlePrimaryAction());
        connectionSettingsToggle.setOnClickListener(v -> showPage(Page.CONNECTION, true));
        navigationButton.setOnClickListener(v -> showPage(Page.HOME, false));
        settingsButton.setOnClickListener(v -> showPage(Page.SETTINGS, false));
        permissionStatus.setOnClickListener(v -> requestRequiredPermissions());
        permissionMicrophoneStatus.setOnClickListener(v -> requestRequiredPermissions());
        permissionNotificationStatus.setOnClickListener(v -> requestRequiredPermissions());
    }

    @Override protected void onResume() {
        super.onResume();
        refreshPermissionStatus();
        refreshUi();
    }

    @Override protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, serviceStateReceiver,
                new IntentFilter(FloatingVoiceService.ACTION_RUNNING_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        refreshUi();
    }

    @Override protected void onStop() {
        try { unregisterReceiver(serviceStateReceiver); }
        catch (IllegalArgumentException ignored) { }
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (telegram != null) telegram.removeListener(this);
        super.onDestroy();
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

    private void setupOverlayColorSelector() {
        Spinner selector = findViewById(R.id.overlay_color_selector);
        selector.setSelection(overlayUiPreferences.colorPreset().ordinal(), false);
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                 int position, long id) {
                overlayUiPreferences.setColorPreset(OverlayColorPreset.fromPosition(position));
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.root);
        int start = root.getPaddingStart();
        int top = root.getPaddingTop();
        int end = root.getPaddingEnd();
        int bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets safe = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            view.setPaddingRelative(start + safe.left, top + safe.top,
                    end + safe.right, bottom + safe.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void showPage(Page page, boolean editApi) {
        currentPage = page;
        editingApiSettings = page == Page.CONNECTION && editApi;
        homeScreen.setVisibility(page == Page.HOME ? View.VISIBLE : View.GONE);
        connectionScreen.setVisibility(page == Page.CONNECTION ? View.VISIBLE : View.GONE);
        settingsScreen.setVisibility(page == Page.SETTINGS ? View.VISIBLE : View.GONE);
        navigationButton.setVisibility(page == Page.HOME ? View.GONE : View.VISIBLE);
        settingsButton.setVisibility(page == Page.HOME ? View.VISIBLE : View.GONE);
        appBarTitle.setText(page == Page.CONNECTION ? R.string.screen_connection
                : page == Page.SETTINGS ? R.string.screen_settings : R.string.app_name);
        if (page == Page.CONNECTION) refreshConnectionFlow();
    }

    private void handlePrimaryAction() {
        switch (dashboardState) {
            case CONNECT_TELEGRAM:
            case AUTHENTICATING:
            case SELECT_TARGET:
                showPage(Page.CONNECTION, false);
                break;
            case GRANT_PERMISSIONS:
                showPage(Page.CONNECTION, false);
                requestRequiredPermissions();
                break;
            case READY:
                startOverlay();
                break;
            case RUNNING:
                confirmStopOverlay();
                break;
            default:
                throw new IllegalStateException(dashboardState.name());
        }
    }

    private void saveConnectionAndStart() {
        AppConfig.ValidationResult result = AppConfig.validateConnection(
                apiId.getText().toString(), apiHash.getText().toString(),
                connectionPhone.getText().toString());
        if (!result.isValid()) {
            presentValidationErrors(result.errors());
            return;
        }
        AppConfig base = result.config();
        boolean connectionChanged = savedConfig != null
                && !savedConfig.hasSameConnection(base);
        TelegramRepository.AuthStage authStage = telegram.authStage();
        boolean activeSession = authStage != TelegramRepository.AuthStage.NOT_STARTED
                && authStage != TelegramRepository.AuthStage.CLOSED;
        if (connectionChanged && activeSession) {
            presentLocalStatus(R.string.connection_change_requires_logout, true, false);
            return;
        }
        String existingUsername = !connectionChanged && savedConfig != null
                && savedConfig.hasBotUsername() ? savedConfig.botUsername() : "";
        AppConfig config = new AppConfig(base.apiId(), base.apiHash(),
                base.phoneNumber(), existingUsername);
        settingsStore.saveConfig(config);
        savedConfig = config;
        showConfig(config);
        telegram.start(config);
        editingApiSettings = false;
        presentLocalStatus(R.string.settings_saved_no_message, false, true);
        refreshUi();
    }

    private void saveTargetAndResolve() {
        if (savedConfig == null) {
            showPage(Page.CONNECTION, true);
            return;
        }
        AppConfig.ValidationResult result = AppConfig.validate(
                Integer.toString(savedConfig.apiId()), savedConfig.apiHash(),
                savedConfig.phoneNumber(), username.getText().toString());
        if (!result.isValid()) {
            presentValidationErrors(result.errors());
            return;
        }
        savedConfig = result.config();
        settingsStore.saveConfig(savedConfig);
        showConfig(savedConfig);
        telegram.start(savedConfig);
        telegram.resolveConfiguredBot();
    }

    private void presentValidationErrors(List<AppConfig.ValidationError> errors) {
        List<String> messages = new ArrayList<>();
        for (AppConfig.ValidationError error : errors) {
            messages.add(getString(validationErrorResource(error)));
        }
        presentStatus(String.join("\n", messages), true, false);
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
        apiId.setText(String.format(Locale.ROOT, "%d", config.apiId()));
        apiHash.setText(config.apiHash());
        connectionPhone.setText(config.phoneNumber());
        phone.setText(config.phoneNumber());
        username.setText(config.botUsername());
    }

    private void requestRequiredPermissions() {
        boolean microphoneDenied = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED;
        boolean notificationDenied = Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
        if ((microphoneDenied && isPermanentlyDenied(Manifest.permission.RECORD_AUDIO))
                || (notificationDenied
                && isPermanentlyDenied(Manifest.permission.POST_NOTIFICATIONS))) {
            showPermissionSettingsDialog();
            return;
        }

        ArrayList<String> permissions = new ArrayList<>();
        if (microphoneDenied) permissions.add(Manifest.permission.RECORD_AUDIO);
        if (notificationDenied) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) {
            markPermissionRequestsAttempted(permissions);
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

    private boolean isPermanentlyDenied(String permission) {
        SharedPreferences history = getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE);
        return history.getBoolean(permission, false)
                && !shouldShowRequestPermissionRationale(permission);
    }

    private void markPermissionRequestsAttempted(List<String> permissions) {
        SharedPreferences.Editor editor = getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE)
                .edit();
        for (String permission : permissions) editor.putBoolean(permission, true);
        editor.apply();
    }

    private void showPermissionSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_settings_title)
                .setMessage(R.string.permission_settings_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.open_app_settings, (dialog, which) -> {
                    Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(details);
                })
                .show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                      int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST) return;
        refreshPermissionStatus();
        boolean allGranted = grantResults.length > 0;
        for (int result : grantResults) {
            allGranted &= result == PackageManager.PERMISSION_GRANTED;
        }
        if (allGranted && !Settings.canDrawOverlays(this)) requestRequiredPermissions();
    }

    private boolean permissionsGranted() {
        boolean microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean notifications = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        return microphone && notifications && Settings.canDrawOverlays(this);
    }

    private void refreshPermissionStatus() {
        if (permissionStatus == null) return;
        boolean microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean notification = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText(getString(R.string.permission_overlay_format,
                yesNo(Settings.canDrawOverlays(this))));
        permissionMicrophoneStatus.setText(getString(R.string.permission_microphone_format,
                yesNo(microphone)));
        permissionNotificationStatus.setText(getString(R.string.permission_notification_format,
                yesNo(notification)));
        refreshUi();
    }

    private void refreshUi() {
        if (telegram == null || dashboardHeadline == null) return;
        refreshDashboard();
        if (currentPage == Page.CONNECTION) refreshConnectionFlow();
    }

    private void refreshDashboard() {
        boolean running = FloatingVoiceService.isRunning();
        boolean hasConfiguration = savedConfig != null;
        boolean authenticationComplete = telegram.authStage() == TelegramRepository.AuthStage.READY;
        TargetChat target = telegram.target();
        boolean targetConfirmed = savedConfig != null && savedConfig.hasBotUsername()
                && target != null && target.username().equals(savedConfig.botUsername());
        dashboardState = DashboardReadiness.evaluate(running, hasConfiguration,
                authenticationComplete, targetConfirmed, permissionsGranted());

        switch (dashboardState) {
            case CONNECT_TELEGRAM:
                applyHomeState(R.drawable.ic_quiet_link, R.string.home_connect_eyebrow,
                        R.string.home_connect_title, R.string.home_connect_supporting,
                        R.string.home_connect_action, R.string.home_connect_note, false);
                break;
            case AUTHENTICATING:
                applyHomeState(R.drawable.ic_quiet_link, R.string.home_auth_eyebrow,
                        R.string.home_auth_title, R.string.home_auth_supporting,
                        R.string.home_auth_action, R.string.home_auth_note, false);
                break;
            case SELECT_TARGET:
                applyHomeState(R.drawable.ic_quiet_link, R.string.home_target_eyebrow,
                        R.string.home_target_title, R.string.home_target_supporting,
                        R.string.home_target_action, R.string.home_target_note, false);
                break;
            case GRANT_PERMISSIONS:
                applyHomeState(R.drawable.ic_quiet_mic, R.string.home_permission_eyebrow,
                        R.string.home_permission_title, R.string.home_permission_supporting,
                        R.string.home_permission_action, R.string.home_permission_note, false);
                break;
            case READY:
                applyHomeState(R.drawable.ic_quiet_mic, R.string.home_ready_eyebrow,
                        R.string.home_ready_title, R.string.home_ready_supporting,
                        R.string.home_ready_action, R.string.home_ready_note, false);
                break;
            case RUNNING:
                applyHomeState(R.drawable.ic_quiet_wave, R.string.home_running_eyebrow,
                        R.string.home_running_title, R.string.home_running_supporting,
                        R.string.home_running_action, R.string.home_running_note, true);
                break;
            default:
                throw new IllegalStateException(dashboardState.name());
        }

        if ((dashboardState == DashboardReadiness.State.READY
                || dashboardState == DashboardReadiness.State.RUNNING) && target != null) {
            dashboardTarget.setText(getString(R.string.home_target_format, target));
            dashboardTarget.setVisibility(View.VISIBLE);
        } else {
            dashboardTarget.setVisibility(View.GONE);
        }
    }

    private void applyHomeState(int icon, int eyebrow, int title, int supporting,
                                int action, int note, boolean destructive) {
        dashboardIcon.setImageResource(icon);
        dashboardEyebrow.setText(eyebrow);
        dashboardHeadline.setText(title);
        dashboardSupporting.setText(supporting);
        dashboardPrimaryAction.setText(action);
        dashboardActionNote.setText(note);
        stylePrimaryAction(destructive);
    }

    private void stylePrimaryAction(boolean destructive) {
        if (destructive) {
            int error = ContextCompat.getColor(this, R.color.quiet_recording);
            dashboardPrimaryAction.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            dashboardPrimaryAction.setTextColor(error);
            dashboardPrimaryAction.setStrokeColor(ColorStateList.valueOf(error));
            dashboardPrimaryAction.setStrokeWidth(dp(1));
        } else {
            int primary = ContextCompat.getColor(this, R.color.quiet_primary);
            int onPrimary = ContextCompat.getColor(this, R.color.quiet_on_primary);
            dashboardPrimaryAction.setBackgroundTintList(ColorStateList.valueOf(primary));
            dashboardPrimaryAction.setTextColor(onPrimary);
            dashboardPrimaryAction.setStrokeColor(ColorStateList.valueOf(Color.TRANSPARENT));
            dashboardPrimaryAction.setStrokeWidth(0);
        }
    }

    private void refreshConnectionFlow() {
        if (connectionStep == null) return;
        connectionSettingsCard.setVisibility(View.GONE);
        authenticationCard.setVisibility(View.GONE);
        targetPermissionsCard.setVisibility(View.GONE);
        targetStep.setVisibility(View.GONE);
        permissionStep.setVisibility(View.GONE);
        connectionComplete.setVisibility(View.GONE);

        TelegramRepository.AuthStage authStage = telegram.authStage();
        boolean connectionEntryStage = authStage == TelegramRepository.AuthStage.NOT_STARTED
                || authStage == TelegramRepository.AuthStage.CLOSED;
        if (editingApiSettings || dashboardState == DashboardReadiness.State.CONNECT_TELEGRAM
                || connectionEntryStage) {
            setConnectionStage(1, R.string.connection_api_title, R.string.connection_api_supporting);
            connectionSettingsCard.setVisibility(View.VISIBLE);
        } else if (dashboardState == DashboardReadiness.State.AUTHENTICATING) {
            setConnectionStage(2, R.string.connection_auth_title, R.string.connection_auth_supporting);
            authenticationCard.setVisibility(View.VISIBLE);
            updateAuthControls(authStage);
        } else if (dashboardState == DashboardReadiness.State.SELECT_TARGET) {
            setConnectionStage(3, R.string.connection_target_title, R.string.connection_target_supporting);
            targetPermissionsCard.setVisibility(View.VISIBLE);
            targetStep.setVisibility(View.VISIBLE);
        } else if (dashboardState == DashboardReadiness.State.GRANT_PERMISSIONS) {
            setConnectionStage(4, R.string.connection_permission_title,
                    R.string.connection_permission_supporting);
            targetPermissionsCard.setVisibility(View.VISIBLE);
            permissionStep.setVisibility(View.VISIBLE);
        } else {
            setConnectionStage(4, R.string.connection_complete_title,
                    R.string.connection_complete_supporting);
            connectionComplete.setVisibility(View.VISIBLE);
        }
    }

    private void setConnectionStage(int step, int title, int supporting) {
        connectionStep.setText(getString(R.string.connection_step_format, step));
        connectionTitle.setText(title);
        connectionSupporting.setText(supporting);
    }

    private void startOverlay() {
        if (!permissionsGranted()) {
            presentLocalStatus(R.string.permissions_required_first, true, false);
            requestRequiredPermissions();
            return;
        }
        if (!telegram.isReadyWithTarget()) {
            presentLocalStatus(R.string.telegram_target_required_first, true, false);
            return;
        }
        Intent service = new Intent(this, FloatingVoiceService.class)
                .setAction(FloatingVoiceService.ACTION_START);
        try {
            startForegroundService(service);
            presentLocalStatus(R.string.overlay_starting, false, true);
        } catch (RuntimeException e) {
            presentStatus(getString(R.string.overlay_start_failed,
                    e.getClass().getSimpleName()), true, false);
        }
    }

    private void confirmStopOverlay() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.stop_overlay_title)
                .setMessage(R.string.stop_overlay_message)
                .setNegativeButton(R.string.keep_running, null)
                .setPositiveButton(R.string.home_running_action,
                        (dialog, which) -> stopOverlay())
                .show();
    }

    private void stopOverlay() {
        boolean requested = stopService(new Intent(this, FloatingVoiceService.class));
        if (requested) {
            presentLocalStatus(R.string.overlay_stopping, false, true);
        } else {
            presentLocalStatus(R.string.overlay_stopped, false, true);
            refreshUi();
        }
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void presentLocalStatus(int resourceId, boolean persistent, boolean transientFeedback) {
        presentStatus(getString(resourceId), persistent, transientFeedback);
    }

    private void presentStatus(String status, boolean persistent, boolean transientFeedback) {
        connectionStatus.setText(status);
        if (!persistent) {
            connectionStatus.postDelayed(() -> {
                if (status.contentEquals(connectionStatus.getText())) {
                    connectionStatus.setText("");
                }
            }, 3500L);
        }
        if (persistent) {
            persistentStatus = status;
            operationStatus.setText(status);
            operationStatusPanel.setVisibility(View.VISIBLE);
        } else if (persistentStatus == null) {
            operationStatusPanel.setVisibility(View.GONE);
        }
        if (transientFeedback && statusCallbacksReady && currentPage == Page.HOME
                && hasWindowFocus()) {
            Snackbar.make(findViewById(R.id.root), status, Snackbar.LENGTH_SHORT).show();
        }
    }

    private void dismissPersistentStatus() {
        persistentStatus = null;
        telegram.clearPersistentStatus();
        operationStatusPanel.setVisibility(View.GONE);
    }

    @Override public void onStatus(String status, boolean persistent) {
        runOnUiThread(() -> presentStatus(status, persistent, !persistent));
    }

    @Override public void onAuthStage(TelegramRepository.AuthStage stage) {
        runOnUiThread(() -> {
            authStatus.setText(getString(R.string.auth_status_format, authStageLabel(stage)));
            updateAuthControls(stage);
            refreshUi();
        });
    }

    @Override public void onAccountChanged(String account) {
        runOnUiThread(() -> accountStatus.setText(account));
    }

    @Override public void onTargetChanged(TargetChat target) {
        runOnUiThread(() -> {
            targetStatus.setText(target == null
                    ? getString(R.string.target_not_confirmed)
                    : getString(R.string.target_confirmed_format, target));
            refreshUi();
        });
    }

    @Override public void onLocaleChanged() {
        runOnUiThread(() -> {
            String localizedPersistentStatus = telegram.lastPersistentStatus();
            if (localizedPersistentStatus == null) {
                presentStatus(telegram.lastStatus(), false, false);
            } else {
                presentStatus(localizedPersistentStatus, true, false);
            }
            refreshUi();
            showPage(currentPage, editingApiSettings);
        });
    }

    private void updateAuthControls(TelegramRepository.AuthStage stage) {
        boolean phoneStep = stage == TelegramRepository.AuthStage.PHONE;
        boolean codeStep = stage == TelegramRepository.AuthStage.CODE;
        boolean passwordStep = stage == TelegramRepository.AuthStage.PASSWORD;
        boolean emailAddressStep = stage == TelegramRepository.AuthStage.EMAIL_ADDRESS;
        boolean emailCodeStep = stage == TelegramRepository.AuthStage.EMAIL_CODE;
        boolean restartAvailable = stage == TelegramRepository.AuthStage.PARAMETERS
                || stage == TelegramRepository.AuthStage.UNSUPPORTED;

        findViewById(R.id.phone_field).setVisibility(phoneStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.submit_phone).setVisibility(phoneStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.auth_code_field).setVisibility(codeStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.submit_code).setVisibility(codeStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.password_field).setVisibility(passwordStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.submit_password).setVisibility(passwordStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.email_help).setVisibility(
                emailAddressStep || emailCodeStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.email_address_field).setVisibility(
                emailAddressStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.submit_email_address).setVisibility(
                emailAddressStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.email_code_field).setVisibility(emailCodeStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.submit_email_code).setVisibility(emailCodeStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.restart_auth).setVisibility(
                restartAvailable ? View.VISIBLE : View.GONE);

        TextView summary = findViewById(R.id.authentication_complete);
        boolean inputStep = phoneStep || codeStep || passwordStep
                || emailAddressStep || emailCodeStep;
        summary.setVisibility(inputStep ? View.GONE : View.VISIBLE);
        summary.setText(stage == TelegramRepository.AuthStage.READY
                ? R.string.authentication_complete : R.string.authentication_waiting);
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

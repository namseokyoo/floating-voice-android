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
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.sidequestlab.floatingvoice.core.AppConfig;
import com.sidequestlab.floatingvoice.core.ConnectionInfoFormatter;
import com.sidequestlab.floatingvoice.core.DashboardReadiness;
import com.sidequestlab.floatingvoice.core.Destination;
import com.sidequestlab.floatingvoice.core.DestinationCatalog;
import com.sidequestlab.floatingvoice.core.OverlayColorPreset;
import com.sidequestlab.floatingvoice.core.OverlaySizePreset;
import com.sidequestlab.floatingvoice.core.TargetChangePolicy;
import com.sidequestlab.floatingvoice.core.TargetEditCompletionPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends AppCompatActivity implements TelegramRepository.Listener {
    private static final int PERMISSION_REQUEST = 100;
    public static final String EXTRA_OPEN_ARCHIVE_PICKER =
            "com.sidequestlab.floatingvoice.OPEN_ARCHIVE_PICKER";
    private static final String PERMISSION_PREFS = "permission_request_history";
    private static final String STATE_PENDING_TARGET_EDITOR = "pending_target_editor";
    private static final String STATE_PAGE = "page";
    private static final String STATE_CONNECTION_MODE = "connection_mode";
    private static final String STATE_PENDING_TARGET_USERNAME = "pending_target_username";
    private static final String STATE_PENDING_TARGET_OPERATION = "pending_target_operation";
    private static final String STATE_DESTINATION_ORDER_EDITING = "destination_order_editing";
    private static final int MENU_SET_DEFAULT = 1;
    private static final int MENU_RENAME = 2;
    private static final int MENU_REVERIFY = 3;
    private static final int MENU_TOGGLE_ENABLED = 4;
    private static final int MENU_DELETE = 5;

    private enum Page {
        HOME, CONNECTION, APP_SETTINGS, DESTINATION_SETTINGS, TELEGRAM_SETTINGS
    }
    private enum ConnectionMode { RESUME, EDIT_API, EDIT_TARGET }

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
    private TextView settingsTargetSummary;
    private TextView settingsConnectionSummary;
    private TextView settingsApiIdValue;
    private TextView settingsApiHashValue;
    private TextView settingsPhoneValue;
    private ImageView dashboardIcon;
    private View homeScreen;
    private View connectionScreen;
    private View appSettingsScreen;
    private View destinationSettingsScreen;
    private View telegramSettingsScreen;
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
    private MaterialButton dashboardLocalShareAction;
    private MaterialButton destinationAddButton;
    private MaterialButton destinationOrderEditButton;
    private MaterialButton resendAuthCodeButton;
    private MaterialButton changeAuthPhoneButton;
    private LinearLayout destinationsListContainer;
    private TextView destinationsListEmpty;
    private TextView archiveFolderStatus;
    private TelegramRepository telegram;
    private SecureSettingsStore settingsStore;
    private OverlayUiPreferences overlayUiPreferences;
    private ArchiveSettingsStore archiveSettingsStore;
    private ActivityResultLauncher<Intent> archiveFolderLauncher;
    private BroadcastReceiver serviceStateReceiver;
    private AppConfig savedConfig;
    private DashboardReadiness.State dashboardState = DashboardReadiness.State.CONNECT_TELEGRAM;
    private Page currentPage = Page.HOME;
    private ConnectionMode connectionMode = ConnectionMode.RESUME;
    private boolean pendingTargetEditor;
    private String pendingTargetUsername;
    private long pendingTargetOperation;
    private boolean statusCallbacksReady;
    private boolean destinationOrderEditing;
    private String persistentStatus;
    private final Handler authUiHandler = new Handler(Looper.getMainLooper());
    private boolean editingAuthPhone;
    private final Runnable authCodeCountdown = new Runnable() {
        @Override public void run() {
            if (telegram == null || telegram.authStage() != TelegramRepository.AuthStage.CODE) return;
            updateAuthControls(TelegramRepository.AuthStage.CODE);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        archiveSettingsStore = new ArchiveSettingsStore(this);
        archiveFolderLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null
                            || result.getData().getData() == null) {
                        archiveSettingsStore.onPickerCancelled();
                        refreshArchiveFolderStatus();
                        return;
                    }
                    persistArchiveFolder(
                            result.getData().getData(), result.getData().getFlags());
                });
        pendingTargetEditor = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_PENDING_TARGET_EDITOR, false);
        if (savedInstanceState != null) {
            try {
                currentPage = Page.valueOf(savedInstanceState.getString(
                        STATE_PAGE, Page.HOME.name()));
                connectionMode = ConnectionMode.valueOf(savedInstanceState.getString(
                        STATE_CONNECTION_MODE, ConnectionMode.RESUME.name()));
            } catch (IllegalArgumentException ignored) {
                currentPage = Page.HOME;
                connectionMode = ConnectionMode.RESUME;
            }
            pendingTargetUsername = savedInstanceState.getString(STATE_PENDING_TARGET_USERNAME);
            pendingTargetOperation = savedInstanceState.getLong(STATE_PENDING_TARGET_OPERATION);
            destinationOrderEditing = savedInstanceState.getBoolean(
                    STATE_DESTINATION_ORDER_EDITING, false);
        }
        applySystemBarInsets();
        bindViews();
        if (getIntent().getBooleanExtra(EXTRA_OPEN_ARCHIVE_PICKER, false)) {
            getIntent().removeExtra(EXTRA_OPEN_ARCHIVE_PICKER);
            archiveFolderStatus.post(this::launchArchiveFolderPicker);
        }
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
                    boolean running = FloatingVoiceService.isRunning();
                    presentLocalStatus(running
                            ? R.string.overlay_started : R.string.overlay_stopped, false, true);
                    if (!running && pendingTargetEditor
                            && currentPage == Page.DESTINATION_SETTINGS) {
                        pendingTargetEditor = false;
                        openTargetEditor();
                    } else {
                        if (!running) pendingTargetEditor = false;
                        refreshUi();
                    }
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
                    navigateBack();
                }
            }
        });

        app.startTelegramIfConfigured();
        telegram.addListener(this);
        statusCallbacksReady = true;
        refreshPermissionStatus();
        showPage(currentPage, connectionMode);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_PENDING_TARGET_EDITOR, pendingTargetEditor);
        outState.putString(STATE_PAGE, currentPage.name());
        outState.putString(STATE_CONNECTION_MODE, connectionMode.name());
        outState.putString(STATE_PENDING_TARGET_USERNAME, pendingTargetUsername);
        outState.putLong(STATE_PENDING_TARGET_OPERATION, pendingTargetOperation);
        outState.putBoolean(STATE_DESTINATION_ORDER_EDITING, destinationOrderEditing);
        super.onSaveInstanceState(outState);
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
        settingsTargetSummary = findViewById(R.id.settings_target_summary);
        settingsConnectionSummary = findViewById(R.id.settings_connection_summary);
        settingsApiIdValue = findViewById(R.id.settings_api_id_value);
        settingsApiHashValue = findViewById(R.id.settings_api_hash_value);
        settingsPhoneValue = findViewById(R.id.settings_phone_value);
        dashboardIcon = findViewById(R.id.dashboard_icon);
        homeScreen = findViewById(R.id.home_screen);
        connectionScreen = findViewById(R.id.connection_screen);
        appSettingsScreen = findViewById(R.id.app_settings_screen);
        destinationSettingsScreen = findViewById(R.id.destination_settings_screen);
        telegramSettingsScreen = findViewById(R.id.telegram_settings_screen);
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
        dashboardLocalShareAction = findViewById(R.id.dashboard_local_share_action);
        destinationAddButton = findViewById(R.id.destination_add_button);
        destinationOrderEditButton = findViewById(R.id.destination_order_edit_button);
        resendAuthCodeButton = findViewById(R.id.resend_auth_code);
        changeAuthPhoneButton = findViewById(R.id.change_auth_phone);
        destinationsListContainer = findViewById(R.id.destinations_list_container);
        destinationsListEmpty = findViewById(R.id.destinations_list_empty);
        archiveFolderStatus = findViewById(R.id.archive_folder_status);
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
        resendAuthCodeButton.setOnClickListener(v -> telegram.resendAuthenticationCode());
        changeAuthPhoneButton.setOnClickListener(v -> {
            editingAuthPhone = !editingAuthPhone;
            if (editingAuthPhone && phone.getText().length() == 0 && savedConfig != null) {
                phone.setText(savedConfig.phoneNumber());
            }
            updateAuthControls(telegram.authStage());
        });
        findViewById(R.id.submit_password).setOnClickListener(v -> {
            telegram.submitPassword(password.getText().toString());
            password.setText("");
        });
        findViewById(R.id.resolve_bot).setOnClickListener(v -> saveTargetAndResolve());
        findViewById(R.id.permissions).setOnClickListener(v -> requestRequiredPermissions());
        findViewById(R.id.connection_done).setOnClickListener(
                v -> showPage(Page.HOME, ConnectionMode.RESUME));
        findViewById(R.id.restart_auth).setOnClickListener(
                v -> telegram.restartCurrentConfiguration());
        findViewById(R.id.dismiss_error).setOnClickListener(v -> dismissPersistentStatus());
        findViewById(R.id.logout).setOnClickListener(v -> confirmLogout());
        dashboardPrimaryAction.setOnClickListener(v -> handlePrimaryAction());
        dashboardLocalShareAction.setOnClickListener(v -> startOverlay());
        destinationAddButton.setOnClickListener(v -> showAddDestinationDialog());
        destinationOrderEditButton.setOnClickListener(v -> {
            destinationOrderEditing = !destinationOrderEditing;
            renderDestinationList();
        });
        navigationButton.setOnClickListener(v -> navigateBack());
        settingsButton.setOnClickListener(v -> showSettingsMenu());
        permissionStatus.setOnClickListener(v -> requestRequiredPermissions());
        permissionMicrophoneStatus.setOnClickListener(v -> requestRequiredPermissions());
        permissionNotificationStatus.setOnClickListener(v -> requestRequiredPermissions());
        findViewById(R.id.archive_folder_choose).setOnClickListener(v ->
                launchArchiveFolderPicker());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_OPEN_ARCHIVE_PICKER, false)) {
            intent.removeExtra(EXTRA_OPEN_ARCHIVE_PICKER);
            archiveFolderStatus.post(this::launchArchiveFolderPicker);
        }
    }

    private void launchArchiveFolderPicker() {
        archiveFolderLauncher.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
    }

    @Override protected void onResume() {
        super.onResume();
        refreshPermissionStatus();
        refreshArchiveFolderStatus();
        refreshUi();
    }

    @Override protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, serviceStateReceiver,
                new IntentFilter(FloatingVoiceService.ACTION_RUNNING_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        refreshUi();
        if (pendingTargetEditor && !FloatingVoiceService.isRunning()
                && currentPage == Page.DESTINATION_SETTINGS) {
            pendingTargetEditor = false;
            openTargetEditor();
        } else if (!FloatingVoiceService.isRunning()) {
            pendingTargetEditor = false;
        }
    }

    @Override protected void onStop() {
        authUiHandler.removeCallbacks(authCodeCountdown);
        try { unregisterReceiver(serviceStateReceiver); }
        catch (IllegalArgumentException ignored) { }
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (telegram != null) telegram.removeListener(this);
        super.onDestroy();
    }

    private void persistArchiveFolder(Uri uri, int returnedFlags) {
        ArchiveSettingsStore.Selection previous = archiveSettingsStore.selection();
        int flags = returnedFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        int requiredFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if (flags != requiredFlags) {
            presentLocalStatus(R.string.archive_folder_save_failed, true, false);
            refreshArchiveFolderStatus();
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
            if (!archiveSettingsStore.saveSelection(uri.toString(), archiveFolderLabel(uri))) {
                if (previous.treeUri() == null || !uri.toString().equals(previous.treeUri())) {
                    getContentResolver().releasePersistableUriPermission(uri, flags);
                }
                presentLocalStatus(R.string.archive_folder_save_failed, true, false);
            } else if (previous.treeUri() != null
                    && !uri.toString().equals(previous.treeUri())) {
                try {
                    getContentResolver().releasePersistableUriPermission(
                            Uri.parse(previous.treeUri()), flags);
                } catch (SecurityException ignored) { }
            }
        } catch (SecurityException failure) {
            presentLocalStatus(R.string.archive_folder_save_failed, true, false);
        }
        refreshArchiveFolderStatus();
    }

    private String archiveFolderLabel(Uri treeUri) {
        Uri documentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri));
        try (android.database.Cursor cursor = getContentResolver().query(documentUri,
                new String[] {android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException ignored) { }
        return treeUri.getLastPathSegment() == null
                ? treeUri.toString() : treeUri.getLastPathSegment();
    }

    private void refreshArchiveFolderStatus() {
        if (archiveFolderStatus == null || archiveSettingsStore == null) return;
        ArchiveSettingsStore.Selection selection = archiveSettingsStore.selection();
        int text = switch (selection.status()) {
            case NOT_SELECTED -> R.string.archive_folder_not_selected;
            case READY -> R.string.archive_folder_ready;
            case PERMISSION_LOST -> R.string.archive_folder_permission_lost;
            case PROVIDER_UNAVAILABLE -> R.string.archive_folder_provider_unavailable;
        };
        archiveFolderStatus.setText(selection.status() == ArchiveSettingsStore.Status.NOT_SELECTED
                ? getString(text) : getString(text, selection.label()));
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

    private void showPage(Page page, ConnectionMode mode) {
        currentPage = page;
        connectionMode = page == Page.CONNECTION ? mode : ConnectionMode.RESUME;
        homeScreen.setVisibility(page == Page.HOME ? View.VISIBLE : View.GONE);
        connectionScreen.setVisibility(page == Page.CONNECTION ? View.VISIBLE : View.GONE);
        appSettingsScreen.setVisibility(page == Page.APP_SETTINGS ? View.VISIBLE : View.GONE);
        destinationSettingsScreen.setVisibility(
                page == Page.DESTINATION_SETTINGS ? View.VISIBLE : View.GONE);
        telegramSettingsScreen.setVisibility(
                page == Page.TELEGRAM_SETTINGS ? View.VISIBLE : View.GONE);
        navigationButton.setVisibility(page == Page.HOME ? View.GONE : View.VISIBLE);
        settingsButton.setVisibility(page == Page.HOME ? View.VISIBLE : View.GONE);
        int title;
        switch (page) {
            case CONNECTION:
                title = mode == ConnectionMode.EDIT_TARGET
                        ? R.string.screen_destination : R.string.screen_connection;
                break;
            case APP_SETTINGS:
                title = R.string.settings_app;
                break;
            case DESTINATION_SETTINGS:
                title = R.string.screen_destination;
                break;
            case TELEGRAM_SETTINGS:
                title = R.string.screen_connection;
                break;
            case HOME:
            default:
                title = R.string.app_name;
                break;
        }
        appBarTitle.setText(title);
        View visibleScreen;
        switch (page) {
            case CONNECTION: visibleScreen = connectionScreen; break;
            case APP_SETTINGS: visibleScreen = appSettingsScreen; break;
            case DESTINATION_SETTINGS: visibleScreen = destinationSettingsScreen; break;
            case TELEGRAM_SETTINGS: visibleScreen = telegramSettingsScreen; break;
            case HOME:
            default: visibleScreen = homeScreen; break;
        }
        ViewCompat.setAccessibilityPaneTitle(visibleScreen, getString(title));
        if (page == Page.CONNECTION) refreshConnectionFlow();
        if (page == Page.DESTINATION_SETTINGS || page == Page.TELEGRAM_SETTINGS) {
            refreshSettingsSummary();
        }
    }

    private void showSettingsMenu() {
        PopupMenu menu = new PopupMenu(this, settingsButton);
        menu.inflate(R.menu.settings_menu);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_app_settings) {
                showPage(Page.APP_SETTINGS, ConnectionMode.RESUME);
                return true;
            }
            if (id == R.id.menu_destination_settings) {
                showPage(Page.DESTINATION_SETTINGS, ConnectionMode.RESUME);
                return true;
            }
            if (id == R.id.menu_telegram_settings) {
                showPage(Page.TELEGRAM_SETTINGS, ConnectionMode.RESUME);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void navigateBack() {
        boolean committedTargetEdit = false;
        if (currentPage == Page.DESTINATION_SETTINGS) pendingTargetEditor = false;
        if (currentPage == Page.CONNECTION && connectionMode == ConnectionMode.EDIT_TARGET) {
            if (pendingTargetOperation > 0L) {
                committedTargetEdit = telegram.cancelTargetResolution(pendingTargetOperation);
            }
            pendingTargetUsername = null;
            pendingTargetOperation = 0L;
        }
        Page destination = Page.HOME;
        if (currentPage == Page.CONNECTION && connectionMode == ConnectionMode.EDIT_TARGET) {
            destination = Page.DESTINATION_SETTINGS;
        } else if (currentPage == Page.CONNECTION && connectionMode == ConnectionMode.EDIT_API) {
            destination = Page.TELEGRAM_SETTINGS;
        }
        showPage(destination, ConnectionMode.RESUME);
        if (committedTargetEdit) showTargetChangeSuccess();
    }

    private void handlePrimaryAction() {
        switch (dashboardState) {
            case CONNECT_TELEGRAM:
            case AUTHENTICATING:
            case SELECT_TARGET:
                showPage(Page.CONNECTION, ConnectionMode.RESUME);
                break;
            case GRANT_PERMISSIONS:
                showPage(Page.CONNECTION, ConnectionMode.RESUME);
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
        boolean returnToSettings = connectionMode == ConnectionMode.EDIT_API;
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
        connectionMode = ConnectionMode.RESUME;
        presentLocalStatus(R.string.settings_saved_no_message, false, true);
        if (returnToSettings) {
            showPage(Page.TELEGRAM_SETTINGS, ConnectionMode.RESUME);
        } else {
            refreshUi();
        }
    }

    private void saveTargetAndResolve() {
        if (savedConfig == null) {
            showPage(Page.CONNECTION, ConnectionMode.EDIT_API);
            return;
        }
        AppConfig.ValidationResult result = AppConfig.validate(
                Integer.toString(savedConfig.apiId()), savedConfig.apiHash(),
                savedConfig.phoneNumber(), username.getText().toString());
        if (!result.isValid()) {
            presentValidationErrors(result.errors());
            return;
        }
        String candidate = result.config().botUsername();
        boolean targetEdit = connectionMode == ConnectionMode.EDIT_TARGET;
        pendingTargetUsername = targetEdit ? candidate : null;
        pendingTargetOperation = telegram.resolveTargetUsername(candidate);
        if (!targetEdit || pendingTargetOperation == 0L) {
            pendingTargetUsername = null;
            pendingTargetOperation = 0L;
        }
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
        refreshSettingsSummary();
        if (currentPage == Page.CONNECTION) refreshConnectionFlow();
    }

    private void refreshSettingsSummary() {
        if (settingsTargetSummary == null || settingsConnectionSummary == null
                || settingsApiIdValue == null || settingsApiHashValue == null
                || settingsPhoneValue == null) return;
        DestinationCatalog destinationCatalog = telegram.destinationCatalog();
        Destination defaultDestination = destinationCatalog.defaultLocalId()
                .flatMap(destinationCatalog::find).orElse(null);
        settingsTargetSummary.setText(defaultDestination == null
                ? getString(R.string.settings_default_destination_missing)
                : getString(R.string.settings_default_destination_format,
                        defaultDestination.userAlias()));
        settingsConnectionSummary.setText(telegram.authStage() == TelegramRepository.AuthStage.READY
                ? R.string.settings_connection_ready : R.string.settings_connection_not_ready);
        renderDestinationList();
        if (savedConfig == null) {
            settingsApiIdValue.setText(R.string.settings_connection_value_unavailable);
            settingsApiHashValue.setText(R.string.settings_connection_value_unavailable);
            settingsApiHashValue.setContentDescription(null);
            settingsPhoneValue.setText(R.string.settings_connection_value_unavailable);
        } else {
            settingsApiIdValue.setText(String.format(Locale.ROOT, "%d", savedConfig.apiId()));
            settingsApiHashValue.setText(
                    ConnectionInfoFormatter.maskApiHash(savedConfig.apiHash()));
            settingsApiHashValue.setContentDescription(getString(
                    R.string.settings_api_hash_accessibility_format,
                    ConnectionInfoFormatter.apiHashSuffix(savedConfig.apiHash())));
            settingsPhoneValue.setText(savedConfig.phoneNumber());
        }
    }

    private void renderDestinationList() {
        if (destinationsListContainer == null) return;
        DestinationCatalog catalog = telegram.destinationCatalog();
        String defaultLocalId = catalog.defaultLocalId().orElse(null);
        destinationsListContainer.removeAllViews();
        destinationsListEmpty.setVisibility(
                catalog.destinations().isEmpty() ? View.VISIBLE : View.GONE);
        destinationOrderEditButton.setEnabled(catalog.destinations().size() > 1);
        destinationOrderEditButton.setText(destinationOrderEditing
                ? R.string.destination_order_done : R.string.destination_order_edit);
        for (int index = 0; index < catalog.destinations().size(); index++) {
            Destination destination = catalog.destinations().get(index);
            View item = LayoutInflater.from(this).inflate(
                    R.layout.destination_list_item, destinationsListContainer, false);
            bindDestinationItem(item, destination, defaultLocalId,
                    index, catalog.destinations().size());
            destinationsListContainer.addView(item);
        }
    }

    private void bindDestinationItem(View item, Destination destination, String defaultLocalId,
                                     int index, int destinationCount) {
        TextView title = item.findViewById(R.id.destination_item_title);
        TextView badge = item.findViewById(R.id.destination_item_badge);
        TextView identity = item.findViewById(R.id.destination_item_identity);
        TextView statusView = item.findViewById(R.id.destination_item_status);

        title.setText(destination.userAlias());
        String shownUsername = destination.resolvedUsername().isEmpty()
                ? destination.configuredUsername() : destination.resolvedUsername();
        identity.setText(getString(R.string.destination_item_identity_format, shownUsername));
        statusView.setText(destinationStatusText(destination));

        boolean isDefault = destination.localId().equals(defaultLocalId);
        badge.setVisibility(View.VISIBLE);
        if (isDefault) {
            badge.setText(R.string.destination_item_default_badge);
        } else if (!destination.enabled()) {
            badge.setText(R.string.destination_item_disabled_badge);
        } else {
            badge.setVisibility(View.GONE);
        }

        long currentAccountUserId = telegram.authenticatedAccountUserId();
        boolean ownedByCurrentAccount = currentAccountUserId > 0L
                && destination.accountUserId() == currentAccountUserId;
        boolean legacyReverifyAllowed = currentAccountUserId > 0L
                && destination.accountUserId() == 0L
                && destination.verificationStatus()
                == Destination.VerificationStatus.NEEDS_REVERIFY;
        View overflow = item.findViewById(R.id.destination_action_overflow);
        overflow.setVisibility(destinationOrderEditing ? View.GONE : View.VISIBLE);
        overflow.setEnabled(ownedByCurrentAccount || legacyReverifyAllowed);
        overflow.setContentDescription(getString(
                R.string.destination_menu_content_description, destination.userAlias()));
        overflow.setOnClickListener(v -> showDestinationMenu(v, destination, isDefault,
                ownedByCurrentAccount, legacyReverifyAllowed, currentAccountUserId));
        View orderControls = item.findViewById(R.id.destination_order_controls);
        orderControls.setVisibility(destinationOrderEditing ? View.VISIBLE : View.GONE);
        View moveUp = item.findViewById(R.id.destination_action_move_up);
        moveUp.setEnabled(ownedByCurrentAccount && index > 0);
        moveUp.setOnClickListener(
                v -> { if (telegram.moveDestination(destination.localId(), true)) refreshUi(); });
        View moveDown = item.findViewById(R.id.destination_action_move_down);
        moveDown.setEnabled(ownedByCurrentAccount && index < destinationCount - 1);
        moveDown.setOnClickListener(
                v -> { if (telegram.moveDestination(destination.localId(), false)) refreshUi(); });
    }

    private void showDestinationMenu(View anchor, Destination destination, boolean isDefault,
                                     boolean ownedByCurrentAccount,
                                     boolean legacyReverifyAllowed,
                                     long currentAccountUserId) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, MENU_SET_DEFAULT, 0, R.string.destination_action_default)
                .setEnabled(ownedByCurrentAccount && !isDefault
                        && destination.selectableBy(currentAccountUserId));
        menu.getMenu().add(0, MENU_RENAME, 1, R.string.destination_action_alias)
                .setEnabled(ownedByCurrentAccount);
        menu.getMenu().add(0, MENU_REVERIFY, 2, R.string.destination_action_reverify)
                .setEnabled(ownedByCurrentAccount || legacyReverifyAllowed);
        boolean canToggle = destination.verificationStatus()
                == Destination.VerificationStatus.VERIFIED
                || destination.verificationStatus()
                == Destination.VerificationStatus.DISABLED;
        menu.getMenu().add(0, MENU_TOGGLE_ENABLED, 3, destination.enabled()
                        ? R.string.destination_action_disable : R.string.destination_action_enable)
                .setEnabled(ownedByCurrentAccount && canToggle);
        menu.getMenu().add(0, MENU_DELETE, 4, R.string.destination_action_delete)
                .setEnabled(ownedByCurrentAccount);
        menu.setOnMenuItemClickListener(menuItem -> {
            switch (menuItem.getItemId()) {
                case MENU_SET_DEFAULT: confirmSetDefault(destination); return true;
                case MENU_RENAME: showRenameDialog(destination); return true;
                case MENU_REVERIFY: confirmReverify(destination); return true;
                case MENU_TOGGLE_ENABLED: toggleDestinationEnabled(destination); return true;
                case MENU_DELETE: confirmDelete(destination); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private String destinationStatusText(Destination destination) {
        switch (destination.verificationStatus()) {
            case VERIFIED: return getString(R.string.destination_item_status_verified);
            case VERIFYING: return getString(R.string.destination_item_status_verifying);
            case NEEDS_REVERIFY: return getString(R.string.destination_item_status_needs_reverify);
            case INVALID: return getString(R.string.destination_item_status_invalid);
            case DISABLED: return getString(R.string.destination_item_status_disabled);
            default: return "";
        }
    }

    private void confirmSetDefault(Destination destination) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.destination_action_default)
                .setMessage(getString(R.string.destination_set_default_message,
                        destination.userAlias()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.destination_action_default, (dialog, which) -> {
                    telegram.setDefaultDestination(destination.localId());
                    refreshUi();
                })
                .show();
    }

    private void confirmReverify(Destination destination) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.destination_reverify_title)
                .setMessage(R.string.destination_reverify_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.destination_reverify_action, (dialog, which) -> {
                    long generation = telegram.reverifyDestinationUsername(
                            destination.localId(),
                            destination.resolvedUsername().isEmpty()
                                    ? destination.configuredUsername()
                                    : destination.resolvedUsername(),
                            destination.userAlias());
                    if (generation == 0L) refreshUi();
                })
                .show();
    }

    private void toggleDestinationEnabled(Destination destination) {
        boolean enabling = !destination.enabled();
        telegram.setDestinationEnabled(destination.localId(), enabling);
        refreshUi();
    }

    private void showRenameDialog(Destination destination) {
        EditText input = new EditText(this);
        input.setText(destination.userAlias());
        input.setHint(R.string.destination_alias_label);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.destination_alias_title)
                .setMessage(R.string.destination_alias_label)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.destination_alias_action, (dialog, which) -> {
                    String alias = input.getText().toString().trim();
                    if (!alias.isEmpty()) {
                        telegram.setDestinationAlias(destination.localId(), alias);
                        refreshUi();
                    }
                })
                .show();
    }

    private void showAddDestinationDialog() {
        if (telegram.authStage() != TelegramRepository.AuthStage.READY) {
            showPage(Page.CONNECTION, ConnectionMode.RESUME);
            return;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        content.setPaddingRelative(padding, dp(8), padding, 0);
        EditText usernameInput = new EditText(this);
        usernameInput.setHint(R.string.connection_target_supporting);
        usernameInput.setSingleLine(true);
        EditText aliasInput = new EditText(this);
        aliasInput.setHint(R.string.destination_alias_label);
        aliasInput.setSingleLine(true);
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fieldParams.topMargin = dp(12);
        content.addView(usernameInput);
        content.addView(aliasInput, fieldParams);
        new AlertDialog.Builder(this)
                .setTitle(R.string.destination_action_add)
                .setMessage(R.string.destination_add_supporting)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.destination_action_add, (dialog, which) -> {
                    String candidate = usernameInput.getText().toString().trim();
                    String alias = aliasInput.getText().toString().trim();
                    long generation = telegram.addDestinationUsername(candidate, alias);
                    if (generation == 0L) refreshUi();
                })
                .show();
    }

    private void confirmDelete(Destination destination) {
        DestinationCatalog catalog = telegram.destinationCatalog();
        boolean deletingDefault = catalog.defaultLocalId()
                .filter(destination.localId()::equals).isPresent();
        List<Destination> replacements = new ArrayList<>();
        long accountUserId = telegram.authenticatedAccountUserId();
        for (Destination candidate : catalog.destinations()) {
            if (!candidate.localId().equals(destination.localId())
                    && candidate.selectableBy(accountUserId)) {
                replacements.add(candidate);
            }
        }
        if (deletingDefault && !replacements.isEmpty()) {
            confirmDeleteDefaultWithReplacement(destination, replacements);
            return;
        }

        int retained = telegram.retainedDispatchCount(destination.localId());
        int messageResource = retained > 0
                ? R.string.destination_delete_message_with_pending
                : R.string.destination_delete_message;
        String message = retained > 0
                ? getString(messageResource, retained)
                : getString(messageResource);
        if (retained > 0) {
            message += "\n\n" + getString(R.string.destination_delete_pending_note);
        }
        if (deletingDefault) {
            message += "\n\n" + getString(R.string.destination_delete_default_no_replacement);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.destination_delete_title)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.destination_delete_action, (dialog, which) -> {
                    telegram.removeDestination(destination.localId());
                    refreshUi();
                });
        if (deletingDefault) {
            Destination reverify = firstReverifyCandidate(catalog, destination.localId());
            builder.setNeutralButton(reverify == null
                            ? R.string.destination_delete_and_add
                            : R.string.destination_delete_and_reverify,
                    (dialog, which) -> {
                        if (!telegram.removeDestination(destination.localId())) return;
                        refreshUi();
                        if (reverify == null) showAddDestinationDialog();
                        else confirmReverify(reverify);
                    });
        }
        builder.show();
    }

    private void confirmDeleteDefaultWithReplacement(
            Destination destination, List<Destination> replacements) {
        int retained = telegram.retainedDispatchCount(destination.localId());
        String message = getString(R.string.destination_delete_default_choose_replacement);
        if (retained > 0) {
            message += "\n\n" + getString(
                    R.string.destination_delete_message_with_pending, retained);
        }
        String[] labels = new String[replacements.size()];
        for (int index = 0; index < replacements.size(); index++) {
            Destination replacement = replacements.get(index);
            String username = replacement.resolvedUsername().isEmpty()
                    ? replacement.configuredUsername() : replacement.resolvedUsername();
            labels[index] = replacement.userAlias() + "  ·  @" + username;
        }
        final int[] selected = {-1};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.destination_delete_default_title)
                .setMessage(message)
                .setSingleChoiceItems(labels, -1, (ignored, which) -> selected[0] = which)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.destination_replace_and_delete_action, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    if (selected[0] < 0) {
                        Snackbar.make(destinationsListContainer,
                                R.string.destination_replacement_required,
                                Snackbar.LENGTH_SHORT).show();
                        return;
                    }
                    Destination replacement = replacements.get(selected[0]);
                    if (telegram.removeDestinationReplacingDefault(
                            destination.localId(), replacement.localId())) {
                        dialog.dismiss();
                        refreshUi();
                    }
                }));
        dialog.show();
    }

    private Destination firstReverifyCandidate(
            DestinationCatalog catalog, String excludedLocalId) {
        long accountUserId = telegram.authenticatedAccountUserId();
        for (Destination candidate : catalog.destinations()) {
            if (!candidate.localId().equals(excludedLocalId)
                    && (candidate.accountUserId() == accountUserId
                    || candidate.accountUserId() == 0L)
                    && candidate.verificationStatus()
                    == Destination.VerificationStatus.NEEDS_REVERIFY) {
                return candidate;
            }
        }
        return null;
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
        dashboardLocalShareAction.setVisibility(
                OverlayCapabilityPolicy.independentStartActionVisible(
                        running, authenticationComplete && targetConfirmed)
                        ? View.VISIBLE : View.GONE);

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
        if (connectionMode == ConnectionMode.EDIT_TARGET
                && authStage == TelegramRepository.AuthStage.READY) {
            connectionStep.setText(R.string.settings_destination);
            connectionTitle.setText(R.string.connection_target_title);
            connectionSupporting.setText(R.string.connection_target_supporting);
            targetPermissionsCard.setVisibility(View.VISIBLE);
            targetStep.setVisibility(View.VISIBLE);
            return;
        } else if (connectionMode == ConnectionMode.EDIT_TARGET) {
            connectionMode = ConnectionMode.RESUME;
            pendingTargetUsername = null;
            pendingTargetOperation = 0L;
            appBarTitle.setText(R.string.screen_connection);
        }
        boolean connectionEntryStage = authStage == TelegramRepository.AuthStage.NOT_STARTED
                || authStage == TelegramRepository.AuthStage.CLOSED;
        if (connectionMode == ConnectionMode.EDIT_API
                || dashboardState == DashboardReadiness.State.CONNECT_TELEGRAM
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

    private void requestTargetEditor() {
        TargetChangePolicy.Action action = TargetChangePolicy.evaluate(
                telegram.authStage() == TelegramRepository.AuthStage.READY,
                FloatingVoiceService.isRunning());
        switch (action) {
            case RESUME_CONNECTION:
                showPage(Page.CONNECTION, ConnectionMode.RESUME);
                break;
            case STOP_OVERLAY_THEN_EDIT:
                confirmStopOverlayForTargetChange();
                break;
            case OPEN_TARGET_EDITOR:
                openTargetEditor();
                break;
            default:
                throw new IllegalStateException(action.name());
        }
    }

    private void confirmStopOverlayForTargetChange() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.target_change_stop_title)
                .setMessage(R.string.target_change_stop_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.target_change_stop_action,
                        (dialog, which) -> stopOverlayThenOpenTargetEditor())
                .show();
    }

    private void stopOverlayThenOpenTargetEditor() {
        pendingTargetEditor = true;
        boolean requested = stopService(new Intent(this, FloatingVoiceService.class));
        if (requested) {
            if (pendingTargetEditor) {
                presentLocalStatus(R.string.target_change_waiting, false, true);
            }
        } else {
            pendingTargetEditor = false;
            openTargetEditor();
        }
    }

    private void openTargetEditor() {
        if (telegram.authStage() != TelegramRepository.AuthStage.READY) {
            showPage(Page.CONNECTION, ConnectionMode.RESUME);
            return;
        }
        TargetChat currentTarget = telegram.target();
        if (currentTarget != null) {
            username.setText(currentTarget.username());
        } else if (savedConfig != null) {
            username.setText(savedConfig.botUsername());
        }
        pendingTargetUsername = null;
        pendingTargetOperation = 0L;
        showPage(Page.CONNECTION, ConnectionMode.EDIT_TARGET);
    }

    private void startOverlay() {
        if (!permissionsGranted()) {
            presentLocalStatus(R.string.permissions_required_first, true, false);
            requestRequiredPermissions();
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

    private void showTargetChangeSuccess() {
        String status = getString(R.string.target_change_complete);
        presentStatus(status, false, false);
        View root = findViewById(R.id.root);
        root.post(() -> {
            root.announceForAccessibility(status);
            if (!isFinishing() && !isDestroyed()) {
                Snackbar.make(root, status, Snackbar.LENGTH_SHORT).show();
            }
        });
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
            boolean completedTargetEdit = TargetEditCompletionPolicy.shouldComplete(
                    currentPage == Page.CONNECTION
                            && connectionMode == ConnectionMode.EDIT_TARGET,
                    pendingTargetUsername,
                    target == null ? null : target.username(),
                    telegram.isTargetResolutionCommitted(pendingTargetOperation));
            AppConfig currentConfig = telegram.currentConfig();
            if (target != null && currentConfig != null) {
                savedConfig = currentConfig;
                showConfig(currentConfig);
            }
            targetStatus.setText(target == null
                    ? getString(R.string.target_not_confirmed)
                    : getString(R.string.target_confirmed_format, target));
            if (completedTargetEdit) {
                pendingTargetUsername = null;
                pendingTargetOperation = 0L;
                showPage(Page.DESTINATION_SETTINGS, ConnectionMode.RESUME);
                showTargetChangeSuccess();
            } else {
                refreshUi();
            }
        });
    }

    @Override public void onDestinationCatalogChanged(DestinationCatalog catalog) {
        runOnUiThread(() -> refreshUi());
    }

    @Override public void onDestinationVerificationPreview(
            TelegramRepository.DestinationVerificationPreview preview) {
        runOnUiThread(() -> showDestinationVerificationPreview(preview));
    }

    private void showDestinationVerificationPreview(
            TelegramRepository.DestinationVerificationPreview preview) {
        Destination previous = preview.previous();
        Destination candidate = preview.candidate();
        String previousUsername = previous.resolvedUsername().isEmpty()
                ? previous.configuredUsername() : previous.resolvedUsername();
        String candidateUsername = candidate.resolvedUsername().isEmpty()
                ? candidate.configuredUsername() : candidate.resolvedUsername();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.destination_preview_title)
                .setMessage(getString(R.string.destination_preview_message,
                        previous.resolvedTitle(), previousUsername,
                        candidate.resolvedTitle(), candidateUsername))
                .setNegativeButton(R.string.cancel, (ignored, which) ->
                        telegram.cancelDestinationVerificationPreview(preview.token()))
                .setPositiveButton(R.string.destination_preview_save, (ignored, which) -> {
                    telegram.confirmDestinationVerificationPreview(preview.token());
                    refreshUi();
                })
                .create();
        dialog.setOnCancelListener(ignored ->
                telegram.cancelDestinationVerificationPreview(preview.token()));
        dialog.show();
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
            showPage(currentPage, connectionMode);
        });
    }

    private void updateAuthControls(TelegramRepository.AuthStage stage) {
        boolean phoneStep = stage == TelegramRepository.AuthStage.PHONE;
        boolean codeStep = stage == TelegramRepository.AuthStage.CODE;
        if (!codeStep) editingAuthPhone = false;
        AuthCodeRecoveryPolicy.State recovery = AuthCodeRecoveryPolicy.evaluate(
                stage, telegram.authCodeHasNextType(), telegram.authCodeResendWaitSeconds());
        boolean passwordStep = stage == TelegramRepository.AuthStage.PASSWORD;
        boolean emailAddressStep = stage == TelegramRepository.AuthStage.EMAIL_ADDRESS;
        boolean emailCodeStep = stage == TelegramRepository.AuthStage.EMAIL_CODE;
        boolean restartAvailable = stage == TelegramRepository.AuthStage.PARAMETERS
                || stage == TelegramRepository.AuthStage.UNSUPPORTED;

        boolean showPhone = phoneStep || (codeStep && editingAuthPhone);
        findViewById(R.id.phone_field).setVisibility(showPhone ? View.VISIBLE : View.GONE);
        findViewById(R.id.submit_phone).setVisibility(showPhone ? View.VISIBLE : View.GONE);
        findViewById(R.id.auth_code_field).setVisibility(codeStep ? View.VISIBLE : View.GONE);
        findViewById(R.id.submit_code).setVisibility(codeStep ? View.VISIBLE : View.GONE);
        resendAuthCodeButton.setVisibility(codeStep ? View.VISIBLE : View.GONE);
        resendAuthCodeButton.setEnabled(recovery.resendEnabled());
        if (!telegram.authCodeHasNextType()) {
            resendAuthCodeButton.setText(R.string.button_resend_auth_code_unavailable);
        } else if (recovery.resendWaitSeconds() > 0L) {
            resendAuthCodeButton.setText(getString(
                    R.string.button_resend_auth_code_wait, recovery.resendWaitSeconds()));
        } else {
            resendAuthCodeButton.setText(R.string.button_resend_auth_code);
        }
        changeAuthPhoneButton.setVisibility(
                recovery.phoneCorrectionAllowed() ? View.VISIBLE : View.GONE);
        changeAuthPhoneButton.setText(editingAuthPhone
                ? R.string.button_cancel_auth_phone_change : R.string.button_change_auth_phone);
        authUiHandler.removeCallbacks(authCodeCountdown);
        if (codeStep && recovery.resendWaitSeconds() > 0L) {
            authUiHandler.postDelayed(authCodeCountdown, 1000L);
        }
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

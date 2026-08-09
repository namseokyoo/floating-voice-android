package com.sidequestlab.messvoice;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.sidequestlab.messvoice.core.AppConfig;

import java.util.Optional;

public final class MainActivity extends Activity implements TelegramRepository.Listener {
    private static final int PERMISSION_REQUEST = 200;

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

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();

        MessVoiceApp app = (MessVoiceApp) getApplication();
        telegram = app.telegram();
        settingsStore = app.settings();
        settingsStore.loadConfig().ifPresent(this::showConfig);
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
        telegram.removeListener(this);
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

    private void saveAndStart() {
        AppConfig.ValidationResult result = AppConfig.validate(
                apiId.getText().toString(), apiHash.getText().toString(),
                phone.getText().toString(), username.getText().toString());
        if (!result.isValid()) {
            operationStatus.setText(String.join("\n", result.errors()));
            return;
        }
        AppConfig config = result.config();
        settingsStore.saveConfig(config);
        showConfig(config);
        telegram.start(config);
        operationStatus.setText("설정을 암호화해 저장했습니다. 메시지는 전송되지 않았습니다.");
    }

    private void showConfig(AppConfig config) {
        apiId.setText(Integer.toString(config.apiId()));
        apiHash.setText(config.apiHash());
        phone.setText(config.phoneNumber());
        username.setText(config.botUsername());
    }

    private void requestRequiredPermissions() {
        java.util.ArrayList<String> permissions = new java.util.ArrayList<>();
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
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean notification = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText("다른 앱 위 표시: " + yesNo(Settings.canDrawOverlays(this))
                + "  마이크: " + yesNo(mic)
                + "  알림: " + yesNo(notification));
    }

    private void startOverlay() {
        if (!permissionsGranted()) {
            operationStatus.setText("다른 앱 위 표시, 마이크, 알림 권한을 먼저 허용해주세요.");
            requestRequiredPermissions();
            return;
        }
        if (!telegram.isReadyWithTarget()) {
            operationStatus.setText("Telegram 로그인과 메스 봇 대상 확정을 먼저 완료해주세요.");
            return;
        }
        Intent service = new Intent(this, FloatingVoiceService.class)
                .setAction(FloatingVoiceService.ACTION_START);
        try {
            startForegroundService(service);
            operationStatus.setText("플로팅 버튼을 시작했습니다. 한 번 누르면 녹음, 다시 누르면 전송됩니다.");
        } catch (RuntimeException e) {
            operationStatus.setText("플로팅 버튼을 시작하지 못했습니다: "
                    + e.getClass().getSimpleName());
        }
    }

    private void stopOverlay() {
        stopService(new Intent(this, FloatingVoiceService.class));
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("Telegram에서 로그아웃할까요?")
                .setMessage("이 기기의 Telegram 세션을 해제합니다. 플로팅 버튼을 먼저 종료하며, 전송 중이거나 실패한 녹음 파일은 삭제하지 않습니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("로그아웃", (dialog, which) -> {
                    stopOverlay();
                    telegram.logOutAndRevokeSession();
                })
                .show();
    }

    private static String yesNo(boolean value) { return value ? "허용됨" : "필요함"; }

    @Override public void onStatus(String status) {
        runOnUiThread(() -> operationStatus.setText(status));
    }

    @Override public void onAuthStage(TelegramRepository.AuthStage stage) {
        runOnUiThread(() -> {
            authStatus.setText("인증 상태: " + authStageLabel(stage));
            updateAuthControls(stage);
        });
    }

    @Override public void onAccountChanged(String account) {
        runOnUiThread(() -> accountStatus.setText(account));
    }

    @Override public void onTargetChanged(TargetChat target) {
        runOnUiThread(() -> targetStatus.setText(target == null
                ? "전송 대상: 확정되지 않음" : "전송 대상: " + target));
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

    private static String authStageLabel(TelegramRepository.AuthStage stage) {
        switch (stage) {
            case NOT_STARTED: return "시작 전";
            case PARAMETERS: return "연결 준비 중";
            case PHONE: return "전화번호 입력 필요";
            case EMAIL_ADDRESS: return "이메일 주소 입력 필요";
            case EMAIL_CODE: return "이메일 인증번호 입력 필요";
            case CODE: return "Telegram 인증번호 입력 필요";
            case PASSWORD: return "2단계 인증 비밀번호 입력 필요";
            case READY: return "연결 완료";
            case LOGGING_OUT: return "로그아웃 중";
            case CLOSED: return "세션 종료됨";
            case UNSUPPORTED: return "지원하지 않는 인증 단계";
            default: return stage.name();
        }
    }
}

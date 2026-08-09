package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.os.Build;

import com.sidequestlab.floatingvoice.core.AppConfig;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Owns the single TDLib user-account client, auth state machine, bot resolution and sends. */
public final class TelegramRepository {
    public enum AuthStage {
        NOT_STARTED, PARAMETERS, PHONE, EMAIL_ADDRESS, EMAIL_CODE,
        CODE, PASSWORD, READY, LOGGING_OUT, CLOSED, UNSUPPORTED
    }

    public interface Listener {
        default void onStatus(String status) { }
        default void onAuthStage(AuthStage stage) { }
        default void onAccountChanged(String account) { }
        default void onTargetChanged(TargetChat target) { }
    }

    public interface SendCallback {
        void onQueued(long temporaryMessageId);
        void onRejected(String reason);
    }

    private final Context context;
    private final SecureSettingsStore settingsStore;
    private final PendingRecordingStore pendingRecordings;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object clientLock = new Object();
    private final Object configurationLock = new Object();

    private volatile Client client;
    private volatile AppConfig config;
    private volatile TargetChat target;
    private volatile AuthStage authStage = AuthStage.NOT_STARTED;
    private volatile String accountSummary = "로그인 계정: 인증되지 않음";
    private volatile String lastStatus = "Telegram 연결을 시작하지 않았습니다.";

    public TelegramRepository(Context context, SecureSettingsStore settingsStore,
                              PendingRecordingStore pendingRecordings) {
        this.context = context.getApplicationContext();
        this.settingsStore = settingsStore;
        this.pendingRecordings = pendingRecordings;
        this.target = settingsStore.loadTarget().orElse(null);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onStatus(lastStatus);
        listener.onAuthStage(authStage);
        listener.onAccountChanged(accountSummary);
        listener.onTargetChanged(target);
    }

    public void removeListener(Listener listener) { listeners.remove(listener); }
    public AuthStage authStage() { return authStage; }
    public TargetChat target() { return target; }
    public String lastStatus() { return lastStatus; }
    public boolean isReadyWithTarget() { return authStage == AuthStage.READY && target != null; }

    public void start(AppConfig newConfig) {
        boolean clearedTarget = false;
        synchronized (configurationLock) {
            config = newConfig;
            TargetChat existingTarget = target;
            if (existingTarget != null && !existingTarget.username().equals(newConfig.botUsername())) {
                target = null;
                settingsStore.clearTarget();
                clearedTarget = true;
            }
        }
        if (clearedTarget) for (Listener listener : listeners) listener.onTargetChanged(null);
        synchronized (clientLock) {
            if (client != null) {
                status("Telegram 연결이 이미 실행 중입니다. 변경한 설정은 저장했습니다.");
                return;
            }
            stage(AuthStage.PARAMETERS);
            status("암호화된 Telegram 사용자 세션을 시작합니다…");
            client = Client.create(this::handleUpdate,
                    error -> status("Telegram 업데이트 처리 오류: " + error.getMessage()),
                    error -> status("Telegram 연결 오류: " + error.getMessage()));
        }
    }

    public void submitPhoneNumber(String phoneNumber) {
        if (authStage != AuthStage.PHONE) {
            status("현재 전화번호 입력 단계가 아닙니다.");
            return;
        }
        TdApi.PhoneNumberAuthenticationSettings phoneSettings =
                new TdApi.PhoneNumberAuthenticationSettings();
        phoneSettings.authenticationTokens = new String[0];
        TdApi.SetAuthenticationPhoneNumber request = new TdApi.SetAuthenticationPhoneNumber();
        request.phoneNumber = phoneNumber;
        request.settings = phoneSettings;
        send(request, "전화번호를 제출했습니다.");
    }

    public void submitEmailAddress(String emailAddress) {
        if (authStage != AuthStage.EMAIL_ADDRESS) {
            status("현재 이메일 주소 입력 단계가 아닙니다.");
            return;
        }
        TdApi.SetAuthenticationEmailAddress request = new TdApi.SetAuthenticationEmailAddress();
        request.emailAddress = emailAddress.trim();
        send(request, "인증 이메일 주소를 제출했습니다.");
    }

    public void submitEmailCode(String code) {
        if (authStage != AuthStage.EMAIL_CODE) {
            status("현재 이메일 인증번호 입력 단계가 아닙니다.");
            return;
        }
        TdApi.EmailAddressAuthenticationCode emailCode = new TdApi.EmailAddressAuthenticationCode();
        emailCode.code = code.trim();
        TdApi.CheckAuthenticationEmailCode request = new TdApi.CheckAuthenticationEmailCode();
        request.code = emailCode;
        send(request, "이메일 인증번호를 제출했습니다.");
    }

    public void submitCode(String code) {
        if (authStage != AuthStage.CODE) {
            status("현재 Telegram 인증번호 입력 단계가 아닙니다.");
            return;
        }
        TdApi.CheckAuthenticationCode request = new TdApi.CheckAuthenticationCode();
        request.code = code.trim();
        send(request, "Telegram 인증번호를 제출했습니다.");
    }

    public void submitPassword(String password) {
        if (authStage != AuthStage.PASSWORD) {
            status("현재 2단계 인증 비밀번호 입력 단계가 아닙니다.");
            return;
        }
        TdApi.CheckAuthenticationPassword request = new TdApi.CheckAuthenticationPassword();
        request.password = password;
        send(request, "2단계 인증 비밀번호를 제출했습니다.");
    }

    public void resolveConfiguredBot() {
        AppConfig current = config;
        if (authStage != AuthStage.READY || current == null) {
            status("메스 봇을 찾기 전에 Telegram 로그인을 완료해주세요.");
            return;
        }
        String expectedUsername = current.botUsername();
        Client expectedClient = requireClient();
        status("@" + expectedUsername + " 봇을 찾는 중…");
        TdApi.SearchPublicChat request = new TdApi.SearchPublicChat();
        request.username = expectedUsername;
        expectedClient.send(request, result -> {
            if (!isResolutionCurrent(expectedUsername, expectedClient)) {
                status("username이 변경되어 이전 봇 검색 결과를 무시했습니다.");
                return;
            }
            if (result instanceof TdApi.Error) {
                status("봇을 찾지 못했습니다: " + describeError((TdApi.Error) result));
                return;
            }
            if (!(result instanceof TdApi.Chat)) {
                status("봇 검색에서 예상하지 못한 응답을 받았습니다.");
                return;
            }
            TdApi.Chat chat = (TdApi.Chat) result;
            if (!(chat.type instanceof TdApi.ChatTypePrivate)) {
                status("검색 결과가 1:1 봇 대화가 아니어서 대상으로 저장하지 않았습니다.");
                return;
            }
            long userId = ((TdApi.ChatTypePrivate) chat.type).userId;
            TdApi.GetUser getUser = new TdApi.GetUser();
            getUser.userId = userId;
            expectedClient.send(getUser, userResult -> {
                if (!isResolutionCurrent(expectedUsername, expectedClient)) {
                    status("설정이 변경되어 이전 봇 확인 결과를 무시했습니다.");
                    return;
                }
                if (userResult instanceof TdApi.Error) {
                    status("대화는 찾았지만 봇 여부를 확인하지 못했습니다: "
                            + describeError((TdApi.Error) userResult));
                } else if (userResult instanceof TdApi.User
                        && ((TdApi.User) userResult).type instanceof TdApi.UserTypeBot) {
                    TargetChat confirmed = new TargetChat(chat.id, chat.title, expectedUsername);
                    synchronized (configurationLock) {
                        if (!isResolutionCurrent(expectedUsername, expectedClient)) {
                            status("설정이 변경되어 이전 봇 확인 결과를 무시했습니다.");
                            return;
                        }
                        settingsStore.saveTarget(confirmed);
                        target = confirmed;
                    }
                    status("고정 전송 대상을 확정했습니다: " + confirmed);
                    for (Listener listener : listeners) listener.onTargetChanged(confirmed);
                } else {
                    status("검색한 사용자가 Telegram 봇이 아니어서 대상으로 저장하지 않았습니다.");
                }
            });
        });
    }

    public void sendVoiceNote(File recording, int durationSeconds, SendCallback callback) {
        TargetChat fixedTarget = target;
        AppConfig currentConfig = config;
        if (authStage != AuthStage.READY || fixedTarget == null || currentConfig == null
                || !fixedTarget.username().equals(currentConfig.botUsername())) {
            callback.onRejected("Telegram 연결 또는 대상 확정이 완료되지 않았습니다. 녹음 파일 보관 위치: "
                    + recording.getAbsolutePath());
            return;
        }
        if (!recording.isFile() || recording.length() == 0) {
            callback.onRejected("녹음 파일이 없거나 비어 있어 전송하지 않았습니다.");
            return;
        }

        TdApi.InputVoiceNote voice = new TdApi.InputVoiceNote();
        voice.voiceNote = new TdApi.InputFileLocal(recording.getAbsolutePath());
        voice.duration = Math.max(1, durationSeconds);
        voice.waveform = new byte[0];

        TdApi.InputMessageVoiceNote content = new TdApi.InputMessageVoiceNote();
        content.voiceNote = voice;
        content.caption = new TdApi.FormattedText("", new TdApi.TextEntity[0]);
        content.selfDestructType = null;

        TdApi.SendMessage request = new TdApi.SendMessage();
        request.chatId = fixedTarget.chatId();
        request.topicId = null;
        request.replyTo = null;
        request.options = new TdApi.MessageSendOptions();
        request.replyMarkup = null;
        request.inputMessageContent = content;

        synchronized (configurationLock) {
            if (config != currentConfig || target != fixedTarget) {
                callback.onRejected("전송 직전에 대상 설정이 변경되어 보내지 않았습니다. 녹음 파일 보관 위치: "
                        + recording.getAbsolutePath());
                return;
            }
            status(fixedTarget.title() + "에 음성 메시지를 전송 대기열에 넣는 중…");
            requireClient().send(request, result -> {
                if (result instanceof TdApi.Message) {
                    long temporaryId = ((TdApi.Message) result).id;
                    pendingRecordings.put(temporaryId, recording.getAbsolutePath());
                    status("음성 메시지가 전송 대기 중입니다(임시 메시지 " + temporaryId
                            + "). 성공 확인 전까지 파일을 보관합니다.");
                    callback.onQueued(temporaryId);
                } else if (result instanceof TdApi.Error) {
                    String reason = "음성 메시지 전송이 거부되었습니다: "
                            + describeError((TdApi.Error) result) + ". 녹음 파일 보관 위치: "
                            + recording.getAbsolutePath();
                    status(reason);
                    callback.onRejected(reason);
                } else {
                    String reason = "예상하지 못한 전송 응답입니다. 녹음 파일 보관 위치: "
                            + recording.getAbsolutePath();
                    status(reason);
                    callback.onRejected(reason);
                }
            });
        }
    }

    public void logOutAndRevokeSession() {
        Client active = client;
        if (active == null) {
            status("로그아웃할 Telegram 세션이 없습니다.");
            return;
        }
        stage(AuthStage.LOGGING_OUT);
        status("Telegram에서 로그아웃하고 이 기기의 세션을 해제하는 중…");
        active.send(new TdApi.LogOut(), result -> {
            if (result instanceof TdApi.Error) {
                if (authStage == AuthStage.LOGGING_OUT) stage(AuthStage.READY);
                status("로그아웃하지 못했습니다: " + describeError((TdApi.Error) result));
            } else {
                status("로그아웃 요청이 접수되었습니다. 세션 종료를 기다리는 중입니다.");
            }
        });
    }

    private boolean isResolutionCurrent(String expectedUsername, Client expectedClient) {
        synchronized (configurationLock) {
            AppConfig current = config;
            return authStage == AuthStage.READY
                    && client == expectedClient
                    && current != null
                    && expectedUsername.equals(current.botUsername());
        }
    }

    private void handleUpdate(TdApi.Object object) {
        if (object instanceof TdApi.UpdateAuthorizationState) {
            handleAuthorizationState(((TdApi.UpdateAuthorizationState) object).authorizationState);
        } else if (object instanceof TdApi.UpdateMessageSendSucceeded) {
            TdApi.UpdateMessageSendSucceeded update = (TdApi.UpdateMessageSendSucceeded) object;
            String path = pendingRecordings.take(update.oldMessageId);
            if (path != null) {
                File recording = new File(path);
                if (!recording.exists() || recording.delete()) {
                    status("음성 메시지 전송 완료 — 로컬 녹음 파일을 삭제했습니다.");
                } else {
                    status("음성 메시지는 전송됐지만 로컬 파일을 삭제하지 못했습니다: " + path);
                }
            }
        } else if (object instanceof TdApi.UpdateMessageSendFailed) {
            TdApi.UpdateMessageSendFailed update = (TdApi.UpdateMessageSendFailed) object;
            String path = pendingRecordings.take(update.oldMessageId);
            status("음성 메시지 전송 실패: " + describeError(update.error)
                    + (path == null ? "." : ". 녹음 파일 보관 위치: " + path));
        }
    }

    private void handleAuthorizationState(TdApi.AuthorizationState state) {
        if (state instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            stage(AuthStage.PARAMETERS);
            sendTdlibParameters();
        } else if (state instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            stage(AuthStage.PHONE);
            status("Telegram 계정 전화번호를 입력한 뒤 ‘전화번호 제출’을 눌러주세요.");
        } else if (state instanceof TdApi.AuthorizationStateWaitEmailAddress) {
            stage(AuthStage.EMAIL_ADDRESS);
            status("Telegram이 이메일 주소를 요구합니다. 이메일은 저장하지 않습니다.");
        } else if (state instanceof TdApi.AuthorizationStateWaitEmailCode) {
            stage(AuthStage.EMAIL_CODE);
            status("이메일로 받은 인증번호를 입력해주세요. 인증번호는 저장하지 않습니다.");
        } else if (state instanceof TdApi.AuthorizationStateWaitCode) {
            stage(AuthStage.CODE);
            status("Telegram으로 받은 인증번호를 입력해주세요. 인증번호는 저장하지 않습니다.");
        } else if (state instanceof TdApi.AuthorizationStateWaitPassword) {
            stage(AuthStage.PASSWORD);
            status("Telegram 2단계 인증 비밀번호를 입력해주세요. 비밀번호는 저장하지 않습니다.");
        } else if (state instanceof TdApi.AuthorizationStateReady) {
            stage(AuthStage.READY);
            loadCurrentAccount();
            status(target == null ? "Telegram 로그인 완료 — 메스 봇을 찾아 전송 대상을 확정해주세요."
                    : "Telegram 연결 완료 — 고정 전송 대상: " + target);
        } else if (state instanceof TdApi.AuthorizationStateLoggingOut
                || state instanceof TdApi.AuthorizationStateClosing) {
            stage(AuthStage.LOGGING_OUT);
            status("Telegram 세션을 종료하는 중…");
        } else if (state instanceof TdApi.AuthorizationStateClosed) {
            synchronized (clientLock) { client = null; }
            accountSummary = "로그인 계정: 세션 종료됨";
            for (Listener listener : listeners) listener.onAccountChanged(accountSummary);
            stage(AuthStage.CLOSED);
            status("Telegram 세션이 종료되었습니다. 다시 사용하려면 로그인해야 합니다.");
        } else {
            stage(AuthStage.UNSUPPORTED);
            status("현재 앱이 지원하지 않는 Telegram 인증 단계입니다: "
                    + state.getClass().getSimpleName());
        }
    }

    private void loadCurrentAccount() {
        requireClient().send(new TdApi.GetMe(), result -> {
            if (!(result instanceof TdApi.User)) return;
            TdApi.User user = (TdApi.User) result;
            String name = (user.firstName + " " + user.lastName).trim();
            accountSummary = "로그인 계정: " + (name.isEmpty() ? "Telegram 사용자" : name)
                    + " (사용자 ID " + user.id + ")";
            for (Listener listener : listeners) listener.onAccountChanged(accountSummary);
        });
    }

    private void sendTdlibParameters() {
        AppConfig current = config;
        if (current == null) {
            status("Telegram API ID와 API Hash가 필요합니다.");
            return;
        }
        File database = new File(context.getFilesDir(), "tdlib/database");
        File files = new File(context.getFilesDir(), "tdlib/files");
        if (!database.mkdirs() && !database.isDirectory()) {
            status("Telegram 데이터베이스 폴더를 만들 수 없습니다.");
            return;
        }
        if (!files.mkdirs() && !files.isDirectory()) {
            status("Telegram 파일 폴더를 만들 수 없습니다.");
            return;
        }

        TdApi.SetTdlibParameters request = new TdApi.SetTdlibParameters();
        request.useTestDc = false;
        request.databaseDirectory = database.getAbsolutePath();
        request.filesDirectory = files.getAbsolutePath();
        request.databaseEncryptionKey = settingsStore.getOrCreateDatabaseKey();
        request.useFileDatabase = true;
        request.useChatInfoDatabase = true;
        request.useMessageDatabase = true;
        request.useSecretChats = false;
        request.apiId = current.apiId();
        request.apiHash = current.apiHash();
        request.systemLanguageCode = "ko";
        request.deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
        request.systemVersion = "Android " + Build.VERSION.RELEASE;
        request.applicationVersion = "0.2.0";
        send(request, "Telegram 연결 정보를 제출했습니다.");
    }

    private void send(TdApi.Function request, String acceptedStatus) {
        requireClient().send(request, result -> {
            if (result instanceof TdApi.Error) {
                status(describeError((TdApi.Error) result));
            } else {
                status(acceptedStatus);
            }
        });
    }

    private Client requireClient() {
        Client active = client;
        if (active == null) throw new IllegalStateException("Telegram 연결이 실행 중이 아닙니다");
        return active;
    }

    private static String describeError(TdApi.Error error) {
        return "Telegram 오류 " + error.code + ": " + error.message;
    }

    private void stage(AuthStage next) {
        authStage = next;
        for (Listener listener : listeners) listener.onAuthStage(next);
    }

    private void status(String next) {
        lastStatus = next;
        for (Listener listener : listeners) listener.onStatus(next);
    }
}

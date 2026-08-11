package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.os.Build;

import com.sidequestlab.floatingvoice.core.AppConfig;
import com.sidequestlab.floatingvoice.core.PendingMessageKey;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Owns the single TDLib user-account client, auth state machine, bot resolution and sends. */
public final class TelegramRepository {
    public enum AuthStage {
        NOT_STARTED, PARAMETERS, PHONE, EMAIL_ADDRESS, EMAIL_CODE,
        CODE, PASSWORD, READY, LOGGING_OUT, CLOSED, UNSUPPORTED
    }

    public interface Listener {
        default void onStatus(String status) { }
        default void onStatus(String status, boolean persistent) { onStatus(status); }
        default void onAuthStage(AuthStage stage) { }
        default void onAccountChanged(String account) { }
        default void onTargetChanged(TargetChat target) { }
        default void onLocaleChanged() { }
    }

    public interface SendCallback {
        void onQueued(long temporaryMessageId);
        void onRejected(String reason);
    }

    public interface TextSendCallback {
        void onQueued(long temporaryMessageId);
        void onDelivered();
        void onRejected(String reason);
    }

    private enum AccountState { NOT_AUTHENTICATED, USER, CLOSED }

    private static final class StatusMessage {
        private final int resourceId;
        private final Object[] arguments;

        private StatusMessage(int resourceId, Object... arguments) {
            this.resourceId = resourceId;
            this.arguments = Arrays.copyOf(arguments, arguments.length);
        }
    }

    private final Context context;
    private final SecureSettingsStore settingsStore;
    private final PendingRecordingStore pendingRecordings;
    private final PendingTextSendStore pendingTextMessages;
    private final ConcurrentHashMap<PendingMessageKey, TextSendCallback> pendingTextSends =
            new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object clientLock = new Object();
    private final Object configurationLock = new Object();
    private final Object stateDeliveryLock = new Object();

    private volatile Client client;
    private volatile AppConfig config;
    private volatile TargetChat target;
    private volatile AuthStage authStage = AuthStage.NOT_STARTED;
    private volatile StatusMessage lastStatus = new StatusMessage(R.string.repo_not_started);
    private volatile StatusMessage lastPersistentStatus;
    private volatile boolean restartAfterClose;
    private volatile AccountState accountState = AccountState.NOT_AUTHENTICATED;
    private volatile String accountName;
    private volatile long accountUserId;
    private long targetResolutionGeneration;
    private long lastCommittedTargetResolutionGeneration;

    public TelegramRepository(Context context, SecureSettingsStore settingsStore,
                              PendingRecordingStore pendingRecordings,
                              PendingTextSendStore pendingTextMessages) {
        this.context = context.getApplicationContext();
        this.settingsStore = settingsStore;
        this.pendingRecordings = pendingRecordings;
        this.pendingTextMessages = pendingTextMessages;
        this.target = settingsStore.loadTarget().orElse(null);
        if (target != null) {
            pendingRecordings.migrateLegacy(target.chatId());
            pendingTextMessages.migrateLegacy(target.chatId());
        }
    }

    public void addListener(Listener listener) {
        synchronized (stateDeliveryLock) {
            listeners.add(listener);
            listener.onStatus(render(lastStatus), isPersistentStatus(lastStatus.resourceId));
            StatusMessage persistent = lastPersistentStatus;
            if (persistent != null && persistent != lastStatus) {
                listener.onStatus(render(persistent), true);
            }
            listener.onAuthStage(authStage);
            listener.onAccountChanged(accountSummary());
            listener.onTargetChanged(target);
        }
    }

    public void removeListener(Listener listener) { listeners.remove(listener); }
    public AuthStage authStage() { return authStage; }
    public AppConfig currentConfig() { return config; }
    public TargetChat target() { return target; }
    public String lastStatus() {
        synchronized (stateDeliveryLock) {
            return render(lastStatus);
        }
    }
    public String lastPersistentStatus() {
        synchronized (stateDeliveryLock) {
            return lastPersistentStatus == null ? null : render(lastPersistentStatus);
        }
    }
    public boolean isReadyWithTarget() {
        AppConfig current = config;
        TargetChat fixedTarget = target;
        return authStage == AuthStage.READY && current != null && current.hasBotUsername()
                && fixedTarget != null
                && fixedTarget.username().equals(current.botUsername());
    }

    public void clearPersistentStatus() { lastPersistentStatus = null; }

    public void restartCurrentConfiguration() {
        AppConfig current = config;
        if (current == null) {
            status(R.string.repo_api_credentials_required);
            return;
        }
        Client active = client;
        if (active == null) {
            start(current);
            return;
        }
        synchronized (configurationLock) {
            targetResolutionGeneration++;
        }
        restartAfterClose = true;
        status(R.string.repo_restarting_session);
        active.send(new TdApi.Close(), result -> {
            if (result instanceof TdApi.Error) {
                restartAfterClose = false;
                TdApi.Error error = (TdApi.Error) result;
                status(R.string.repo_restart_failed, error.code, error.message);
            }
        });
    }

    public void refreshLocalizedState() {
        synchronized (stateDeliveryLock) {
            for (Listener listener : listeners) listener.onLocaleChanged();
        }
    }

    public void start(AppConfig newConfig) {
        boolean clearedTarget = false;
        synchronized (configurationLock) {
            targetResolutionGeneration++;
            config = newConfig;
            TargetChat existingTarget = target;
            if (existingTarget != null && !existingTarget.username().equals(newConfig.botUsername())) {
                target = null;
                settingsStore.clearTarget();
                clearedTarget = true;
            }
        }
        if (clearedTarget) notifyTargetChanged(null);
        synchronized (clientLock) {
            if (client != null) {
                status(R.string.repo_connection_already_running);
                return;
            }
            stage(AuthStage.PARAMETERS);
            status(R.string.repo_starting_session);
            client = Client.create(this::handleUpdate,
                    error -> status(R.string.repo_update_error, error.getMessage()),
                    error -> status(R.string.repo_connection_error, error.getMessage()));
        }
    }

    public void submitPhoneNumber(String phoneNumber) {
        if (authStage != AuthStage.PHONE) {
            status(R.string.repo_not_phone_stage);
            return;
        }
        TdApi.PhoneNumberAuthenticationSettings phoneSettings =
                new TdApi.PhoneNumberAuthenticationSettings();
        phoneSettings.authenticationTokens = new String[0];
        TdApi.SetAuthenticationPhoneNumber request = new TdApi.SetAuthenticationPhoneNumber();
        request.phoneNumber = phoneNumber;
        request.settings = phoneSettings;
        send(request, R.string.repo_phone_submitted);
    }

    public void submitEmailAddress(String emailAddress) {
        if (authStage != AuthStage.EMAIL_ADDRESS) {
            status(R.string.repo_not_email_address_stage);
            return;
        }
        TdApi.SetAuthenticationEmailAddress request = new TdApi.SetAuthenticationEmailAddress();
        request.emailAddress = emailAddress.trim();
        send(request, R.string.repo_email_address_submitted);
    }

    public void submitEmailCode(String code) {
        if (authStage != AuthStage.EMAIL_CODE) {
            status(R.string.repo_not_email_code_stage);
            return;
        }
        TdApi.EmailAddressAuthenticationCode emailCode = new TdApi.EmailAddressAuthenticationCode();
        emailCode.code = code.trim();
        TdApi.CheckAuthenticationEmailCode request = new TdApi.CheckAuthenticationEmailCode();
        request.code = emailCode;
        send(request, R.string.repo_email_code_submitted);
    }

    public void submitCode(String code) {
        if (authStage != AuthStage.CODE) {
            status(R.string.repo_not_auth_code_stage);
            return;
        }
        TdApi.CheckAuthenticationCode request = new TdApi.CheckAuthenticationCode();
        request.code = code.trim();
        send(request, R.string.repo_auth_code_submitted);
    }

    public void submitPassword(String password) {
        if (authStage != AuthStage.PASSWORD) {
            status(R.string.repo_not_password_stage);
            return;
        }
        TdApi.CheckAuthenticationPassword request = new TdApi.CheckAuthenticationPassword();
        request.password = password;
        send(request, R.string.repo_password_submitted);
    }

    /** Resolves and commits a replacement only after the candidate is verified as a bot. */
    public long resolveTargetUsername(String username) {
        final AppConfig expectedConfig;
        final AppConfig candidateConfig;
        final Client expectedClient;
        final long generation;
        synchronized (configurationLock) {
            expectedConfig = config;
            expectedClient = client;
            if (authStage != AuthStage.READY || expectedConfig == null || expectedClient == null) {
                status(R.string.repo_login_before_resolve);
                return 0L;
            }
            candidateConfig = expectedConfig.withBotUsername(username);
            if (!candidateConfig.hasBotUsername()) {
                status(R.string.validation_invalid_username);
                return 0L;
            }
            generation = ++targetResolutionGeneration;
        }

        String expectedUsername = candidateConfig.botUsername();
        status(R.string.repo_searching_bot, expectedUsername);
        TdApi.SearchPublicChat request = new TdApi.SearchPublicChat();
        request.username = expectedUsername;
        expectedClient.send(request, result -> {
            if (!isResolutionCurrent(generation, expectedConfig, expectedClient)) {
                status(R.string.repo_stale_search_ignored);
                return;
            }
            if (result instanceof TdApi.Error) {
                TdApi.Error error = (TdApi.Error) result;
                status(R.string.repo_bot_not_found, error.code, error.message);
                return;
            }
            if (!(result instanceof TdApi.Chat)) {
                status(R.string.repo_unexpected_search_response);
                return;
            }
            TdApi.Chat chat = (TdApi.Chat) result;
            if (!(chat.type instanceof TdApi.ChatTypePrivate)) {
                status(R.string.repo_not_private_chat);
                return;
            }
            long userId = ((TdApi.ChatTypePrivate) chat.type).userId;
            TdApi.GetUser getUser = new TdApi.GetUser();
            getUser.userId = userId;
            expectedClient.send(getUser, userResult -> {
                if (!isResolutionCurrent(generation, expectedConfig, expectedClient)) {
                    status(R.string.repo_stale_confirmation_ignored);
                    return;
                }
                if (userResult instanceof TdApi.Error) {
                    TdApi.Error error = (TdApi.Error) userResult;
                    status(R.string.repo_bot_check_failed, error.code, error.message);
                } else if (userResult instanceof TdApi.User
                        && ((TdApi.User) userResult).type instanceof TdApi.UserTypeBot) {
                    TargetChat confirmed = new TargetChat(chat.id, chat.title, expectedUsername);
                    synchronized (configurationLock) {
                        if (!isResolutionCurrent(generation, expectedConfig, expectedClient)) {
                            status(R.string.repo_stale_confirmation_ignored);
                            return;
                        }
                        try {
                            settingsStore.saveConfigAndTarget(candidateConfig, confirmed);
                        } catch (RuntimeException e) {
                            status(R.string.repo_target_save_failed);
                            return;
                        }
                        config = candidateConfig;
                        target = confirmed;
                        lastCommittedTargetResolutionGeneration = generation;
                    }
                    status(R.string.repo_target_confirmed, confirmed);
                    notifyTargetChanged(confirmed);
                } else {
                    status(R.string.repo_user_not_bot);
                }
            });
        });
        return generation;
    }

    /** Invalidates an in-flight destination lookup without changing the active route. */
    public boolean cancelTargetResolution(long generation) {
        synchronized (configurationLock) {
            boolean committed = generation > 0L
                    && lastCommittedTargetResolutionGeneration == generation;
            if (!committed && targetResolutionGeneration == generation) {
                targetResolutionGeneration++;
            }
            return committed;
        }
    }

    public boolean isTargetResolutionCommitted(long generation) {
        synchronized (configurationLock) {
            return generation > 0L && lastCommittedTargetResolutionGeneration == generation;
        }
    }

    public void sendVoiceNote(File recording, int durationSeconds, SendCallback callback) {
        TargetChat fixedTarget = target;
        AppConfig currentConfig = config;
        if (authStage != AuthStage.READY || fixedTarget == null || currentConfig == null
                || !fixedTarget.username().equals(currentConfig.botUsername())) {
            callback.onRejected(text(R.string.repo_connection_target_incomplete_retained,
                    recording.getAbsolutePath()));
            return;
        }
        if (!recording.isFile() || recording.length() == 0) {
            callback.onRejected(text(R.string.repo_recording_empty));
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
                callback.onRejected(text(R.string.repo_target_changed_retained,
                        recording.getAbsolutePath()));
                return;
            }
            status(R.string.repo_queuing_for_target, fixedTarget.title());
            requireClient().send(request, result -> {
                if (result instanceof TdApi.Message) {
                    TdApi.Message sent = (TdApi.Message) result;
                    long temporaryId = sent.id;
                    pendingRecordings.put(new PendingMessageKey(sent.chatId, temporaryId),
                            recording.getAbsolutePath());
                    status(R.string.repo_queued_temporary, temporaryId);
                    callback.onQueued(temporaryId);
                } else if (result instanceof TdApi.Error) {
                    TdApi.Error error = (TdApi.Error) result;
                    String reason = text(R.string.repo_send_rejected_retained,
                            error.code, error.message, recording.getAbsolutePath());
                    status(R.string.repo_send_rejected_retained,
                            error.code, error.message, recording.getAbsolutePath());
                    callback.onRejected(reason);
                } else {
                    String reason = text(R.string.repo_unexpected_send_response_retained,
                            recording.getAbsolutePath());
                    status(R.string.repo_unexpected_send_response_retained,
                            recording.getAbsolutePath());
                    callback.onRejected(reason);
                }
            });
        }
    }

    public void sendText(String message, TextSendCallback callback) {
        String textMessage = message == null ? "" : message.trim();
        TargetChat fixedTarget = target;
        AppConfig currentConfig = config;
        if (textMessage.isEmpty() || authStage != AuthStage.READY || fixedTarget == null
                || currentConfig == null
                || !fixedTarget.username().equals(currentConfig.botUsername())) {
            callback.onRejected(text(R.string.repo_text_connection_target_required));
            return;
        }

        TdApi.InputMessageText content = new TdApi.InputMessageText(
                new TdApi.FormattedText(textMessage, new TdApi.TextEntity[0]), null, false);
        TdApi.SendMessage request = new TdApi.SendMessage();
        request.chatId = fixedTarget.chatId();
        request.topicId = null;
        request.replyTo = null;
        request.options = new TdApi.MessageSendOptions();
        request.replyMarkup = null;
        request.inputMessageContent = content;

        synchronized (configurationLock) {
            if (config != currentConfig || target != fixedTarget) {
                callback.onRejected(text(R.string.repo_text_connection_target_required));
                return;
            }
            status(R.string.repo_text_queuing, fixedTarget.title());
            requireClient().send(request, result -> {
                if (result instanceof TdApi.Message sent) {
                    PendingMessageKey pending = new PendingMessageKey(sent.chatId, sent.id);
                    pendingTextMessages.put(pending);
                    pendingTextSends.put(pending, callback);
                    status(R.string.repo_text_queued);
                    callback.onQueued(sent.id);
                } else if (result instanceof TdApi.Error error) {
                    String reason = text(R.string.repo_text_rejected, error.code, error.message);
                    status(R.string.repo_text_rejected, error.code, error.message);
                    callback.onRejected(reason);
                } else {
                    String reason = text(R.string.repo_text_unexpected_response);
                    status(R.string.repo_text_unexpected_response);
                    callback.onRejected(reason);
                }
            });
        }
    }

    public void logOutAndRevokeSession() {
        Client active = client;
        if (active == null) {
            status(R.string.repo_no_session_to_logout);
            return;
        }
        stage(AuthStage.LOGGING_OUT);
        status(R.string.repo_logging_out);
        active.send(new TdApi.LogOut(), result -> {
            if (result instanceof TdApi.Error) {
                if (authStage == AuthStage.LOGGING_OUT) stage(AuthStage.READY);
                TdApi.Error error = (TdApi.Error) result;
                status(R.string.repo_logout_failed, error.code, error.message);
            } else {
                status(R.string.repo_logout_accepted);
            }
        });
    }

    private boolean isResolutionCurrent(long generation, AppConfig expectedConfig,
                                        Client expectedClient) {
        synchronized (configurationLock) {
            return targetResolutionGeneration == generation
                    && authStage == AuthStage.READY
                    && client == expectedClient
                    && config == expectedConfig;
        }
    }

    private void handleUpdate(TdApi.Object object) {
        if (object instanceof TdApi.UpdateAuthorizationState) {
            handleAuthorizationState(((TdApi.UpdateAuthorizationState) object).authorizationState);
        } else if (object instanceof TdApi.UpdateMessageSendSucceeded) {
            TdApi.UpdateMessageSendSucceeded update = (TdApi.UpdateMessageSendSucceeded) object;
            TdApi.Message message = update.message;
            if (message == null) return;
            PendingMessageKey pending = new PendingMessageKey(message.chatId, update.oldMessageId);
            if (message.content instanceof TdApi.MessageText
                    || message.content instanceof TdApi.MessageAnimatedEmoji) {
                TextSendCallback textCallback = pendingTextSends.remove(pending);
                boolean persistedTextSend = pendingTextMessages.take(pending);
                if (textCallback != null || persistedTextSend) {
                    status(R.string.repo_text_delivered);
                    if (textCallback != null) textCallback.onDelivered();
                }
            } else if (message.content instanceof TdApi.MessageVoiceNote) {
                String path = pendingRecordings.take(pending);
                if (path != null) {
                    File recording = new File(path);
                    if (!recording.exists() || recording.delete()) {
                        status(R.string.repo_send_complete_deleted);
                    } else {
                        status(R.string.repo_send_complete_delete_failed, path);
                    }
                }
            }
        } else if (object instanceof TdApi.UpdateMessageSendFailed) {
            TdApi.UpdateMessageSendFailed update = (TdApi.UpdateMessageSendFailed) object;
            TdApi.Message message = update.message;
            if (message == null) return;
            PendingMessageKey pending = new PendingMessageKey(message.chatId, update.oldMessageId);
            if (message.content instanceof TdApi.MessageText
                    || message.content instanceof TdApi.MessageAnimatedEmoji) {
                TextSendCallback textCallback = pendingTextSends.remove(pending);
                boolean persistedTextSend = pendingTextMessages.take(pending);
                if (textCallback != null || persistedTextSend) {
                    String reason = text(R.string.repo_text_failed,
                            update.error.code, update.error.message);
                    status(R.string.repo_text_failed, update.error.code, update.error.message);
                    if (textCallback != null) textCallback.onRejected(reason);
                }
            } else if (message.content instanceof TdApi.MessageVoiceNote) {
                String path = pendingRecordings.take(pending);
                if (path == null) {
                    status(R.string.repo_send_failed_no_path,
                            update.error.code, update.error.message);
                } else {
                    status(R.string.repo_send_failed_retained,
                            update.error.code, update.error.message, path);
                }
            }
        }
    }

    private void handleAuthorizationState(TdApi.AuthorizationState state) {
        if (state instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            stage(AuthStage.PARAMETERS);
            sendTdlibParameters();
        } else if (state instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            stage(AuthStage.PHONE);
            status(R.string.repo_enter_phone);
        } else if (state instanceof TdApi.AuthorizationStateWaitEmailAddress) {
            stage(AuthStage.EMAIL_ADDRESS);
            status(R.string.repo_enter_email);
        } else if (state instanceof TdApi.AuthorizationStateWaitEmailCode) {
            stage(AuthStage.EMAIL_CODE);
            status(R.string.repo_enter_email_code);
        } else if (state instanceof TdApi.AuthorizationStateWaitCode) {
            stage(AuthStage.CODE);
            status(R.string.repo_enter_auth_code);
        } else if (state instanceof TdApi.AuthorizationStateWaitPassword) {
            stage(AuthStage.PASSWORD);
            status(R.string.repo_enter_password);
        } else if (state instanceof TdApi.AuthorizationStateReady) {
            stage(AuthStage.READY);
            loadCurrentAccount();
            if (target == null) {
                status(R.string.repo_login_complete_resolve);
            } else {
                status(R.string.repo_connected_target, target);
            }
        } else if (state instanceof TdApi.AuthorizationStateLoggingOut
                || state instanceof TdApi.AuthorizationStateClosing) {
            stage(AuthStage.LOGGING_OUT);
            status(R.string.repo_closing_session);
        } else if (state instanceof TdApi.AuthorizationStateClosed) {
            synchronized (clientLock) { client = null; }
            accountState = AccountState.CLOSED;
            notifyAccountChanged();
            stage(AuthStage.CLOSED);
            if (restartAfterClose) {
                restartAfterClose = false;
                start(config);
            } else {
                status(R.string.repo_session_closed);
            }
        } else {
            stage(AuthStage.UNSUPPORTED);
            status(R.string.repo_unsupported_auth_stage, state.getClass().getSimpleName());
        }
    }

    private void loadCurrentAccount() {
        requireClient().send(new TdApi.GetMe(), result -> {
            if (!(result instanceof TdApi.User)) return;
            TdApi.User user = (TdApi.User) result;
            String name = (user.firstName + " " + user.lastName).trim();
            accountName = name;
            accountUserId = user.id;
            accountState = AccountState.USER;
            notifyAccountChanged();
        });
    }

    private void sendTdlibParameters() {
        AppConfig current = config;
        if (current == null) {
            status(R.string.repo_api_credentials_required);
            return;
        }
        File database = new File(context.getFilesDir(), "tdlib/database");
        File files = new File(context.getFilesDir(), "tdlib/files");
        if (!database.mkdirs() && !database.isDirectory()) {
            status(R.string.repo_database_folder_failed);
            return;
        }
        if (!files.mkdirs() && !files.isDirectory()) {
            status(R.string.repo_files_folder_failed);
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
        request.systemLanguageCode = LocalizedStrings.effectiveLanguageCode(context);
        request.deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
        request.systemVersion = "Android " + Build.VERSION.RELEASE;
        request.applicationVersion = BuildConfig.VERSION_NAME;
        send(request, R.string.repo_parameters_submitted);
    }

    private void send(TdApi.Function request, int acceptedStatusResource) {
        requireClient().send(request, result -> {
            if (result instanceof TdApi.Error) {
                TdApi.Error error = (TdApi.Error) result;
                status(R.string.repo_error_format, error.code, error.message);
            } else {
                status(acceptedStatusResource);
            }
        });
    }

    private Client requireClient() {
        Client active = client;
        if (active == null) throw new IllegalStateException("CLIENT_NOT_RUNNING");
        return active;
    }


    private String accountSummary() {
        switch (accountState) {
            case CLOSED:
                return text(R.string.account_session_closed);
            case USER:
                String displayName = accountName == null || accountName.isEmpty()
                        ? text(R.string.account_telegram_user) : accountName;
                return text(R.string.account_summary, displayName, accountUserId);
            case NOT_AUTHENTICATED:
            default:
                return text(R.string.account_not_authenticated);
        }
    }

    private void notifyAccountChanged() {
        synchronized (stateDeliveryLock) {
            String summary = accountSummary();
            for (Listener listener : listeners) listener.onAccountChanged(summary);
        }
    }

    private void notifyTargetChanged(TargetChat next) {
        synchronized (stateDeliveryLock) {
            for (Listener listener : listeners) listener.onTargetChanged(next);
        }
    }

    private String text(int resourceId, Object... arguments) {
        return LocalizedStrings.get(context, resourceId, arguments);
    }

    private String render(StatusMessage message) {
        return text(message.resourceId, message.arguments);
    }

    private void stage(AuthStage next) {
        synchronized (configurationLock) {
            if (authStage == AuthStage.READY && next != AuthStage.READY) {
                targetResolutionGeneration++;
            }
            authStage = next;
        }
        synchronized (stateDeliveryLock) {
            for (Listener listener : listeners) listener.onAuthStage(next);
        }
    }

    private void status(int resourceId, Object... arguments) {
        synchronized (stateDeliveryLock) {
            StatusMessage next = new StatusMessage(resourceId, arguments);
            lastStatus = next;
            String localized = render(next);
            boolean persistent = isPersistentStatus(resourceId);
            if (persistent) lastPersistentStatus = next;
            for (Listener listener : listeners) listener.onStatus(localized, persistent);
        }
    }

    private static boolean isPersistentStatus(int resourceId) {
        return resourceId == R.string.repo_update_error
                || resourceId == R.string.repo_connection_error
                || resourceId == R.string.repo_bot_not_found
                || resourceId == R.string.repo_unexpected_search_response
                || resourceId == R.string.repo_not_private_chat
                || resourceId == R.string.repo_bot_check_failed
                || resourceId == R.string.repo_user_not_bot
                || resourceId == R.string.repo_recording_empty
                || resourceId == R.string.repo_connection_target_incomplete_retained
                || resourceId == R.string.repo_target_changed_retained
                || resourceId == R.string.repo_send_rejected_retained
                || resourceId == R.string.repo_unexpected_send_response_retained
                || resourceId == R.string.repo_logout_failed
                || resourceId == R.string.repo_target_save_failed
                || resourceId == R.string.repo_send_complete_delete_failed
                || resourceId == R.string.repo_send_failed_no_path
                || resourceId == R.string.repo_send_failed_retained
                || resourceId == R.string.repo_text_rejected
                || resourceId == R.string.repo_text_unexpected_response
                || resourceId == R.string.repo_text_failed
                || resourceId == R.string.repo_unsupported_auth_stage
                || resourceId == R.string.repo_database_folder_failed
                || resourceId == R.string.repo_files_folder_failed
                || resourceId == R.string.repo_error_format
                || resourceId == R.string.repo_restart_failed;
    }

}

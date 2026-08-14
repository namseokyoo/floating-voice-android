package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.os.Build;

import com.sidequestlab.floatingvoice.core.AppConfig;
import com.sidequestlab.floatingvoice.core.DispatchState;
import com.sidequestlab.floatingvoice.core.DispatchTargetSnapshot;
import com.sidequestlab.floatingvoice.core.Destination;
import com.sidequestlab.floatingvoice.core.DestinationCatalog;
import com.sidequestlab.floatingvoice.core.DestinationEditPolicy;
import com.sidequestlab.floatingvoice.core.PendingDispatch;
import com.sidequestlab.floatingvoice.core.PendingMessageKey;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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
        default void onDestinationCatalogChanged(DestinationCatalog catalog) { }
        default void onDestinationVerificationPreview(DestinationVerificationPreview preview) { }
        default void onLocaleChanged() { }
    }

    public record DestinationVerificationPreview(
            long token, Destination previous, Destination candidate) { }

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
    private final DestinationStore destinations;
    private final DestinationResolver destinationResolver = new DestinationResolver();
    private final DestinationLookupAdapter destinationLookupAdapter =
            new DestinationLookupAdapter();
    private final PendingRecordingStore pendingRecordings;
    private final PendingTextSendStore pendingTextMessages;
    private final PendingDispatchStore pendingDispatches;
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
    private volatile long clientGeneration;
    private volatile long authCodeResendAvailableAtMillis;
    private volatile boolean authCodeHasNextType;
    private long targetResolutionGeneration;
    private volatile long lastCommittedTargetResolutionGeneration;
    private long voiceSendGeneration = 1L;
    private volatile DestinationVerificationPreview pendingDestinationPreview;

    public TelegramRepository(Context context, SecureSettingsStore settingsStore,
                              DestinationStore destinations,
                              PendingRecordingStore pendingRecordings,
                              PendingTextSendStore pendingTextMessages,
                              PendingDispatchStore pendingDispatches) {
        this.context = context.getApplicationContext();
        this.settingsStore = settingsStore;
        this.destinations = destinations;
        this.pendingRecordings = pendingRecordings;
        this.pendingTextMessages = pendingTextMessages;
        this.pendingDispatches = pendingDispatches;
        this.target = settingsStore.loadTarget().orElse(null);
        if (target != null) {
            pendingRecordings.migrateLegacy(target.chatId());
            pendingTextMessages.migrateLegacy(target.chatId());
        }
        pendingDispatches.markUncertainAfterRestart();
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
            listener.onDestinationCatalogChanged(destinationCatalog());
            DestinationVerificationPreview preview = pendingDestinationPreview;
            if (preview != null) listener.onDestinationVerificationPreview(preview);
        }
    }

    public void removeListener(Listener listener) { listeners.remove(listener); }
    public AuthStage authStage() { return authStage; }
    public long authenticatedAccountUserId() { return accountUserId; }
    public boolean authCodeHasNextType() { return authCodeHasNextType; }
    public long authCodeResendWaitSeconds() {
        long remaining = authCodeResendAvailableAtMillis - android.os.SystemClock.elapsedRealtime();
        return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
    }
    public AppConfig currentConfig() { return config; }
    public TargetChat target() { return target; }
    public DestinationCatalog destinationCatalog() {
        return destinations.publishedCatalog().orElseGet(DestinationCatalog::empty);
    }
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
            destinationResolver.cancelCurrent();
            pendingDestinationPreview = null;
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
            destinationResolver.cancelCurrent();
            pendingDestinationPreview = null;
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
            long generation = ++clientGeneration;
            client = Client.create(object -> handleUpdate(generation, object),
                    error -> status(R.string.repo_update_error, error.getMessage()),
                    error -> status(R.string.repo_connection_error, error.getMessage()));
        }
    }

    public void submitPhoneNumber(String phoneNumber) {
        if (authStage != AuthStage.PHONE && authStage != AuthStage.CODE) {
            status(R.string.repo_not_phone_stage);
            return;
        }
        TdApi.SetAuthenticationPhoneNumber request =
                AuthCodeRecoveryRequests.changePhone(phoneNumber);
        send(request, R.string.repo_phone_submitted);
    }

    public void resendAuthenticationCode() {
        if (authStage != AuthStage.CODE || !authCodeHasNextType) {
            status(R.string.repo_auth_code_resend_unavailable);
            return;
        }
        long waitSeconds = authCodeResendWaitSeconds();
        if (waitSeconds > 0L) {
            status(R.string.repo_auth_code_resend_wait, waitSeconds);
            return;
        }
        send(AuthCodeRecoveryRequests.resend(), R.string.repo_auth_code_resent);
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
        return generation > 0L && lastCommittedTargetResolutionGeneration == generation;
    }

    /** Starts an additive private-bot lookup. It never sends a test message. */
    public long addDestinationUsername(String username, String alias) {
        DestinationResolver.StartResult start;
        synchronized (configurationLock) {
            pendingDestinationPreview = null;
            start = destinationResolver.startAdd(accountUserId, username, alias);
        }
        return beginDestinationResolution(start);
    }

    /** Re-verifies one stored destination without rebinding a known canonical identity. */
    public long reverifyDestinationUsername(String localId, String username, String alias) {
        DestinationResolver.StartResult start;
        synchronized (configurationLock) {
            pendingDestinationPreview = null;
            start = destinationResolver.startReverify(accountUserId, localId, username, alias);
        }
        return beginDestinationResolution(start);
    }

    private long beginDestinationResolution(DestinationResolver.StartResult start) {
        if (!start.started()) {
            statusDestinationRejection(start.rejection());
            return 0L;
        }
        DestinationResolver.Request request = start.request();
        final Client expectedClient;
        boolean accountMismatch = false;
        synchronized (configurationLock) {
            expectedClient = client;
            if (authStage != AuthStage.READY || expectedClient == null
                    || accountUserId != request.accountUserId()) {
                destinationResolver.reject(request,
                        DestinationResolver.Rejection.ACCOUNT_MISMATCH);
                accountMismatch = true;
            }
        }
        if (accountMismatch) {
            statusDestinationRejection(DestinationResolver.Rejection.ACCOUNT_MISMATCH);
            return 0L;
        }

        status(R.string.repo_searching_bot, request.configuredUsername());
        destinationLookupAdapter.resolve(
                request,
                (function, handler) ->
                        expectedClient.send(function, handler::onResult),
                new DestinationLookupAdapter.Callback() {
                    @Override
                    public boolean isCurrent(DestinationResolver.Request candidate) {
                        return isDestinationResolutionCurrent(candidate, expectedClient);
                    }

                    @Override
                    public void onRejected(
                            DestinationResolver.Request candidate,
                            DestinationResolver.Rejection rejection,
                            TdApi.Error error) {
                        DestinationResolver.Rejection actual = destinationResolver.reject(
                                candidate, rejection).rejection();
                        if (actual == DestinationResolver.Rejection.STALE_REQUEST) {
                            statusDestinationRejection(actual);
                        } else if (actual == DestinationResolver.Rejection.SEARCH_FAILED
                                && error != null) {
                            status(R.string.repo_bot_not_found, error.code, error.message);
                        } else if (actual == DestinationResolver.Rejection.USER_LOOKUP_FAILED
                                && error != null) {
                            status(R.string.repo_bot_check_failed, error.code, error.message);
                        } else {
                            statusDestinationRejection(actual);
                        }
                    }

                    @Override
                    public void onResolved(
                            DestinationResolver.Request candidate,
                            DestinationResolver.ResolvedPeer peer) {
                        finishDestinationResolution(candidate, expectedClient, peer);
                    }
                });
        return request.token();
    }

    private boolean isDestinationResolutionCurrent(
            DestinationResolver.Request request, Client expectedClient) {
        synchronized (configurationLock) {
            return destinationResolver.isCurrent(request)
                    && authStage == AuthStage.READY
                    && client == expectedClient
                    && accountUserId == request.accountUserId();
        }
    }

    private void finishDestinationResolution(
            DestinationResolver.Request request,
            Client expectedClient,
            DestinationResolver.ResolvedPeer peer) {
        DestinationResolver.Resolution result;
        DestinationCatalog updated = null;
        DestinationVerificationPreview preview = null;
        boolean saveFailed = false;
        synchronized (configurationLock) {
            if (!destinationResolver.isCurrent(request)
                    || authStage != AuthStage.READY
                    || client != expectedClient
                    || accountUserId != request.accountUserId()) {
                result = destinationResolver.reject(
                        request, DestinationResolver.Rejection.STALE_REQUEST);
            } else {
                DestinationCatalog currentCatalog = destinationCatalog();
                result = destinationResolver.complete(
                        request, accountUserId, currentCatalog, peer,
                        System.currentTimeMillis());
                if (result.accepted()) {
                    Destination verified = result.destination();
                    if (request.mode() == DestinationResolver.Mode.REVERIFY) {
                        Destination previous = currentCatalog.find(request.localId()).orElse(null);
                        if (previous == null) {
                            result = new DestinationResolver.Resolution(
                                    null, DestinationResolver.Rejection.DESTINATION_MISSING);
                        } else {
                            preview = new DestinationVerificationPreview(
                                    request.token(), previous, verified);
                            pendingDestinationPreview = preview;
                        }
                    } else {
                        try {
                            updated = destinations.update(
                                    catalog -> catalog.withDestination(verified));
                        } catch (RuntimeException error) {
                            saveFailed = true;
                        }
                    }
                }
            }
        }
        if (!result.accepted()) {
            statusDestinationRejection(result.rejection());
            return;
        }
        Destination verified = result.destination();
        if (preview != null) {
            status(R.string.repo_destination_preview_ready,
                    verified.resolvedTitle(), verified.resolvedUsername());
            notifyDestinationVerificationPreview(preview);
            return;
        }
        if (saveFailed || updated == null) {
            status(R.string.repo_target_save_failed);
            return;
        }
        status(R.string.repo_destination_verified,
                verified.resolvedTitle(), verified.resolvedUsername());
        notifyDestinationCatalogChanged(updated);
    }

    /** Commits an explicitly accepted re-verification preview if its source row is unchanged. */
    public boolean confirmDestinationVerificationPreview(long token) {
        DestinationCatalog updated = null;
        Destination verified = null;
        int failureResource = 0;
        synchronized (configurationLock) {
            DestinationVerificationPreview preview = pendingDestinationPreview;
            if (preview == null || preview.token() != token) {
                failureResource = R.string.repo_stale_confirmation_ignored;
            } else if (authStage != AuthStage.READY
                    || preview.candidate().accountUserId() != accountUserId) {
                pendingDestinationPreview = null;
                failureResource = R.string.repo_destination_account_required;
            } else {
                try {
                    updated = destinations.update(catalog -> {
                        Destination current = catalog.find(
                                preview.previous().localId()).orElse(null);
                        DestinationEditPolicy.requirePreviewCurrent(
                                preview.previous(), current);
                        return catalog.withDestination(preview.candidate());
                    });
                    verified = preview.candidate();
                    pendingDestinationPreview = null;
                } catch (IllegalArgumentException | IllegalStateException error) {
                    pendingDestinationPreview = null;
                    failureResource = R.string.repo_stale_confirmation_ignored;
                } catch (RuntimeException error) {
                    failureResource = R.string.repo_target_save_failed;
                }
            }
        }
        if (failureResource != 0 || updated == null || verified == null) {
            status(failureResource == 0 ? R.string.repo_target_save_failed : failureResource);
            return false;
        }
        status(R.string.repo_destination_verified,
                verified.resolvedTitle(), verified.resolvedUsername());
        notifyDestinationCatalogChanged(updated);
        return true;
    }

    public void cancelDestinationVerificationPreview(long token) {
        synchronized (configurationLock) {
            if (pendingDestinationPreview != null
                    && pendingDestinationPreview.token() == token) {
                pendingDestinationPreview = null;
            }
        }
    }

    /** Renames a stored destination. It never rebinds or touches canonical identity. */
    public boolean setDestinationAlias(String localId, String alias) {
        return editDestination(localId,
                R.string.repo_destination_alias_saved,
                R.string.repo_target_save_failed,
                (catalog, existing) -> catalog.withDestination(
                        DestinationEditPolicy.withAlias(existing, alias)));
    }

    /** Disables or re-enables a stored destination without touching canonical identity. */
    public boolean setDestinationEnabled(String localId, boolean enabled) {
        return editDestination(localId,
                enabled ? R.string.repo_destination_enabled
                        : R.string.repo_destination_disabled,
                enabled ? R.string.repo_destination_enable_refused
                        : R.string.repo_target_save_failed,
                (catalog, existing) -> {
                    long now = System.currentTimeMillis();
                    Destination mutated = enabled
                            ? DestinationEditPolicy.enable(existing, now)
                            : DestinationEditPolicy.disable(existing, now);
                    return catalog.withDestination(mutated);
                });
    }

    /** Makes one stored destination the persistent default route. */
    public boolean setDefaultDestination(String localId) {
        return editDestination(localId,
                R.string.repo_destination_default_saved,
                R.string.repo_target_save_failed,
                (catalog, existing) -> catalog.withDefault(localId, accountUserId));
    }

    /**
     * Removes one destination without choosing a fallback. Deleting the current default is
     * allowed only when no other selectable destination exists.
     */
    public boolean removeDestination(String localId) {
        return editDestination(localId,
                R.string.repo_destination_removed,
                R.string.repo_target_save_failed,
                (catalog, existing) -> catalog.defaultLocalId()
                        .filter(localId::equals)
                        .map(ignored -> catalog.withoutDefaultDestination(
                                localId, accountUserId))
                        .orElseGet(() -> catalog.withoutDestination(localId)));
    }

    /** Atomically commits an explicit replacement default and deletion under one lock/write. */
    public boolean removeDestinationReplacingDefault(
            String localId, String replacementLocalId) {
        return editDestination(localId,
                R.string.repo_destination_removed_with_replacement,
                R.string.repo_target_save_failed,
                (catalog, existing) -> catalog.replacingDefaultAndRemoving(
                        localId, replacementLocalId, accountUserId));
    }

    /** Counts pending/failed/unknown dispatch records frozen to one destination snapshot. */
    public int retainedDispatchCount(String localId) {
        return pendingDispatches.retainedCountForDestination(localId);
    }

    /** Moves one destination one position up or down in the stored order. */
    public boolean moveDestination(String localId, boolean up) {
        DestinationCatalog updated = null;
        int failureResource = 0;
        synchronized (configurationLock) {
            if (authStage != AuthStage.READY) {
                failureResource = R.string.repo_destination_account_required;
            } else {
                DestinationCatalog catalog = destinationCatalog();
                Destination existing = catalog.find(localId).orElse(null);
                if (existing == null || !belongsToCurrentAccount(existing)) {
                    failureResource = R.string.repo_destination_missing;
                } else {
                    List<String> order = new java.util.ArrayList<>();
                    for (Destination destination : catalog.destinations()) {
                        order.add(destination.localId());
                    }
                    int index = order.indexOf(localId);
                    int target = up ? index - 1 : index + 1;
                    if (index < 0 || target < 0 || target >= order.size()) return false;
                    String displaced = order.get(target);
                    order.set(index, displaced);
                    order.set(target, localId);
                    try {
                        updated = destinations.update(c -> c.reordered(order));
                    } catch (RuntimeException error) {
                        failureResource = R.string.repo_target_save_failed;
                    }
                }
            }
        }
        if (failureResource != 0 || updated == null) {
            status(failureResource == 0 ? R.string.repo_target_save_failed : failureResource);
            return false;
        }
        status(R.string.repo_destination_order_saved);
        notifyDestinationCatalogChanged(updated);
        return true;
    }

    private boolean editDestination(String localId, int successResource,
                                    int policyFailureResource, CatalogEdit edit) {
        DestinationCatalog updated = null;
        int failureResource = 0;
        synchronized (configurationLock) {
            if (authStage != AuthStage.READY) {
                failureResource = R.string.repo_destination_account_required;
            } else {
                Destination existing = destinationCatalog().find(localId).orElse(null);
                if (existing == null || !belongsToCurrentAccount(existing)) {
                    failureResource = R.string.repo_destination_missing;
                } else {
                    try {
                        updated = destinations.update(catalog -> edit.apply(catalog, existing));
                    } catch (IllegalArgumentException error) {
                        failureResource = policyFailureResource;
                    } catch (RuntimeException error) {
                        failureResource = R.string.repo_target_save_failed;
                    }
                }
            }
        }
        if (failureResource != 0 || updated == null) {
            status(failureResource == 0 ? R.string.repo_target_save_failed : failureResource);
            return false;
        }
        status(successResource);
        notifyDestinationCatalogChanged(updated);
        return true;
    }

    private interface CatalogEdit {
        DestinationCatalog apply(DestinationCatalog catalog, Destination existing);
    }

    private boolean belongsToCurrentAccount(Destination destination) {
        return destination.accountUserId() == accountUserId;
    }

    private void statusDestinationRejection(DestinationResolver.Rejection rejection) {
        if (rejection == null) return;
        switch (rejection) {
            case INVALID_USERNAME -> status(R.string.validation_invalid_username);
            case ACCOUNT_REQUIRED, ACCOUNT_MISMATCH ->
                    status(R.string.repo_destination_account_required);
            case STALE_REQUEST -> status(R.string.repo_stale_confirmation_ignored);
            case NOT_PRIVATE_CHAT -> status(R.string.repo_not_private_chat);
            case NOT_BOT -> status(R.string.repo_user_not_bot);
            case INCOMPLETE_IDENTITY -> status(R.string.repo_destination_identity_incomplete);
            case MISSING_CANONICAL_USERNAME ->
                    status(R.string.repo_destination_canonical_username_missing);
            case DESTINATION_MISSING -> status(R.string.repo_destination_missing);
            case DUPLICATE_DESTINATION -> status(R.string.repo_destination_duplicate);
            case IDENTITY_CHANGED -> status(R.string.repo_destination_identity_changed);
            case SEARCH_FAILED -> status(R.string.repo_destination_search_failed);
            case USER_LOOKUP_FAILED -> status(R.string.repo_destination_user_lookup_failed);
            case UNEXPECTED_RESPONSE -> status(R.string.repo_unexpected_search_response);
        }
    }

    public void sendVoiceNote(File recording, int durationSeconds, SendCallback callback) {
        TargetChat fixedTarget = target;
        AppConfig currentConfig = config;
        long currentAccountUserId = accountUserId;
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
            if (config != currentConfig || target != fixedTarget
                    || authStage != AuthStage.READY
                    || accountUserId != currentAccountUserId) {
                callback.onRejected(text(R.string.repo_target_changed_retained,
                        recording.getAbsolutePath()));
                return;
            }
            DispatchTargetSnapshot snapshot = DispatchTargetSnapshot.legacy(
                    voiceSendGeneration++,
                    fixedTarget.username(),
                    currentAccountUserId,
                    fixedTarget.chatId(),
                    fixedTarget.title());
            PendingDispatch prepared = PendingDispatch.prepare(
                    "voice-" + UUID.randomUUID(), snapshot, recording.getAbsolutePath(),
                    Math.max(1, durationSeconds), System.currentTimeMillis());
            if (!pendingDispatches.prepare(prepared)) {
                status(R.string.repo_send_rejected_retained,
                        -1, "DISPATCH_PERSIST_FAILED", recording.getAbsolutePath());
                callback.onRejected(text(R.string.repo_send_rejected_retained,
                        -1, "DISPATCH_PERSIST_FAILED", recording.getAbsolutePath()));
                return;
            }
            status(R.string.repo_queuing_for_target, fixedTarget.title());
            String dispatchId = prepared.dispatchId();
            Client expectedClient = requireClient();
            long expectedGeneration = clientGeneration;
            expectedClient.send(request, result -> handleVoiceSendResponse(
                    expectedClient, expectedGeneration, dispatchId,
                    recording.getAbsolutePath(), callback, result));
        }
    }

    /** Sends to the immutable route snapshot frozen by RouteStateMachine. */
    public void sendVoiceNote(File recording, int durationSeconds,
                              DispatchTargetSnapshot snapshot, SendCallback callback) {
        Objects.requireNonNull(recording, "recording");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(callback, "callback");
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
        request.chatId = snapshot.chatId();
        request.topicId = null;
        request.replyTo = null;
        request.options = new TdApi.MessageSendOptions();
        request.replyMarkup = null;
        request.inputMessageContent = content;

        Client expectedClient;
        long expectedGeneration;
        PendingDispatch prepared = null;
        String immediateRejection = null;
        synchronized (configurationLock) {
            expectedClient = client;
            expectedGeneration = clientGeneration;
            if (authStage != AuthStage.READY || expectedClient == null
                    || snapshot.accountUserId() != accountUserId
                    || snapshot.chatId() == 0L) {
                immediateRejection = text(R.string.repo_target_changed_retained,
                        recording.getAbsolutePath());
            } else {
                PendingDispatch candidate = PendingDispatch.prepare(
                        "voice-" + UUID.randomUUID(), snapshot, recording.getAbsolutePath(),
                        Math.max(1, durationSeconds), System.currentTimeMillis());
                if (pendingDispatches.prepare(candidate)) {
                    prepared = candidate;
                } else {
                    immediateRejection = text(R.string.repo_send_rejected_retained,
                            -1, "DISPATCH_PERSIST_FAILED", recording.getAbsolutePath());
                }
            }
        }
        if (immediateRejection != null || prepared == null || expectedClient == null) {
            callback.onRejected(immediateRejection == null
                    ? text(R.string.repo_target_changed_retained, recording.getAbsolutePath())
                    : immediateRejection);
            return;
        }

        String dispatchId = prepared.dispatchId();
        status(R.string.repo_queuing_for_target, snapshot.userAlias());
        expectedClient.send(request, result -> handleVoiceSendResponse(
                expectedClient, expectedGeneration, dispatchId,
                recording.getAbsolutePath(), callback, result));
    }

    private void handleVoiceSendResponse(Client expectedClient, long expectedGeneration,
                                         String dispatchId, String absolutePath,
                                         SendCallback callback, TdApi.Object result) {
        synchronized (configurationLock) {
            if (!currentClient(expectedClient, expectedGeneration)) {
                pendingDispatches.markUncertain(dispatchId);
                callback.onRejected(text(R.string.repo_target_changed_retained, absolutePath));
                return;
            }
            if (result instanceof TdApi.Message sent) {
                PendingMessageKey key = new PendingMessageKey(sent.chatId, sent.id);
                finishQueuePersistence(dispatchId, key, absolutePath, callback);
            } else if (result instanceof TdApi.Error error) {
                pendingDispatches.markRejected(dispatchId, error.code, error.message);
                String reason = text(R.string.repo_send_rejected_retained,
                        error.code, error.message, absolutePath);
                status(R.string.repo_send_rejected_retained,
                        error.code, error.message, absolutePath);
                callback.onRejected(reason);
            } else {
                pendingDispatches.markRejected(dispatchId, -1, "UNEXPECTED_RESPONSE");
                String reason = text(R.string.repo_unexpected_send_response_retained,
                        absolutePath);
                status(R.string.repo_unexpected_send_response_retained, absolutePath);
                callback.onRejected(reason);
            }
        }
    }

    private void finishQueuePersistence(String dispatchId, PendingMessageKey key,
                                        String absolutePath, SendCallback callback) {
        if (pendingDispatches.markQueued(dispatchId, key)) {
            status(R.string.repo_queued_temporary, key.temporaryMessageId());
            callback.onQueued(key.temporaryMessageId());
            return;
        }
        pendingDispatches.markQueuePersistenceUnknown(
                dispatchId, key.temporaryMessageId());
        String reason = text(R.string.repo_queue_persistence_failed_retained, absolutePath);
        status(R.string.repo_queue_persistence_failed_retained, absolutePath);
        callback.onRejected(reason);
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
            Client expectedClient = client;
            long expectedGeneration = clientGeneration;
            if (config != currentConfig || target != fixedTarget
                    || authStage != AuthStage.READY || expectedClient == null) {
                callback.onRejected(text(R.string.repo_text_connection_target_required));
                return;
            }
            status(R.string.repo_text_queuing, fixedTarget.title());
            expectedClient.send(request, result -> handleTextSendResponse(
                    expectedClient, expectedGeneration, fixedTarget.chatId(), callback, result));
        }
    }

    /** Sends reviewed text only to the frozen verified destination snapshot. */
    public void sendText(String message, DispatchTargetSnapshot targetSnapshot,
                         TextSendCallback callback) {
        Objects.requireNonNull(callback, "callback");
        synchronized (configurationLock) {
            Client expectedClient = client;
            long expectedGeneration = clientGeneration;
            if (authStage != AuthStage.READY || expectedClient == null) {
                callback.onRejected(text(R.string.repo_text_connection_target_required));
                return;
            }
            VerifiedTextDispatch.Result prepared = VerifiedTextDispatch.prepare(
                    message, targetSnapshot, accountUserId, destinationCatalog());
            if (!prepared.accepted()) {
                callback.onRejected(text(R.string.repo_text_connection_target_required));
                return;
            }
            status(R.string.repo_text_queuing, targetSnapshot.userAlias());
            expectedClient.send(prepared.request(), result -> handleTextSendResponse(
                    expectedClient, expectedGeneration, targetSnapshot.chatId(),
                    callback, result));
        }
    }

    private void handleTextSendResponse(Client expectedClient, long expectedGeneration,
                                        long expectedChatId, TextSendCallback callback,
                                        TdApi.Object result) {
        synchronized (configurationLock) {
            if (!currentClient(expectedClient, expectedGeneration)) {
                callback.onRejected(text(R.string.repo_text_connection_target_required));
            } else if (result instanceof TdApi.Message sent && sent.chatId == expectedChatId) {
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

    private void handleUpdate(long generation, TdApi.Object object) {
        if (!ClientGenerationGate.current(generation, clientGeneration)) return;
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
                PendingDispatch durable = pendingDispatches.findByMessage(pending).orElse(null);
                if (durable != null) {
                    String path = durable.absolutePath();
                    if (!pendingDispatches.markCompleted(durable.dispatchId(), pending, false)) {
                        status(R.string.repo_completion_persistence_failed_retained, path);
                        return;
                    }
                    // Cleanup only; durable dispatch state above owns the V7 truth.
                    pendingRecordings.take(pending);
                    File recording = new File(path);
                    boolean deleted = !recording.exists() || recording.delete();
                    if (deleted) {
                        if (pendingDispatches.markFileDeleted(durable.dispatchId())) {
                            status(R.string.repo_send_complete_deleted);
                        } else {
                            status(R.string.repo_send_complete_deleted_state_pending, path);
                        }
                    } else {
                        status(R.string.repo_send_complete_delete_failed, path);
                    }
                    return;
                }

                // Backward compatibility for pre-dispatch-store pending mappings.
                String legacyPath = pendingRecordings.take(pending);
                if (legacyPath != null) {
                    File recording = new File(legacyPath);
                    boolean deleted = !recording.exists() || recording.delete();
                    if (deleted) {
                        status(R.string.repo_send_complete_deleted);
                    } else {
                        status(R.string.repo_send_complete_delete_failed, legacyPath);
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
                PendingDispatch durable = pendingDispatches.findByMessage(pending).orElse(null);
                String path = durable == null
                        ? pendingRecordings.get(pending) : durable.absolutePath();
                boolean canRetry = false;
                int retryAfterSeconds = 0;
                if (message.sendingState instanceof TdApi.MessageSendingStateFailed failed) {
                    canRetry = failed.canRetry;
                    retryAfterSeconds = (int) Math.max(0, Math.ceil(failed.retryAfter));
                }
                int errorCode = update.error != null ? update.error.code : -1;
                String errorMessage = update.error != null ? update.error.message : "";
                boolean failureSaved = durable != null && pendingDispatches.markFailed(
                        durable.dispatchId(), pending, errorCode, errorMessage,
                        canRetry, retryAfterSeconds);
                if (durable != null && !failureSaved) {
                    status(R.string.repo_failure_persistence_failed_retained,
                            path, errorCode, errorMessage);
                } else if (path == null) {
                    status(R.string.repo_send_failed_no_path,
                            errorCode, errorMessage);
                } else {
                    status(R.string.repo_send_failed_retained,
                            errorCode, errorMessage, path);
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
            TdApi.AuthenticationCodeInfo codeInfo =
                    ((TdApi.AuthorizationStateWaitCode) state).codeInfo;
            authCodeHasNextType = codeInfo != null && codeInfo.nextType != null;
            int timeoutSeconds = codeInfo == null ? 0 : Math.max(0, codeInfo.timeout);
            authCodeResendAvailableAtMillis = android.os.SystemClock.elapsedRealtime()
                    + timeoutSeconds * 1000L;
            stage(AuthStage.CODE);
            status(R.string.repo_enter_auth_code);
        } else if (state instanceof TdApi.AuthorizationStateWaitPassword) {
            stage(AuthStage.PASSWORD);
            status(R.string.repo_enter_password);
        } else if (state instanceof TdApi.AuthorizationStateReady) {
            authCodeHasNextType = false;
            authCodeResendAvailableAtMillis = 0L;
            synchronized (configurationLock) {
                accountUserId = 0L;
                accountName = null;
                accountState = AccountState.NOT_AUTHENTICATED;
                destinationResolver.cancelCurrent();
            }
            stage(AuthStage.READY);
            loadCurrentAccount();
            if (target == null) {
                status(R.string.repo_login_complete_resolve);
            } else {
                status(R.string.repo_connected_target, target);
            }
        } else if (state instanceof TdApi.AuthorizationStateLoggingOut
                || state instanceof TdApi.AuthorizationStateClosing) {
            authCodeHasNextType = false;
            authCodeResendAvailableAtMillis = 0L;
            stage(AuthStage.LOGGING_OUT);
            status(R.string.repo_closing_session);
        } else if (state instanceof TdApi.AuthorizationStateClosed) {
            synchronized (clientLock) {
                client = null;
                clientGeneration++;
            }
            synchronized (configurationLock) {
                accountUserId = 0L;
                accountName = null;
                accountState = AccountState.CLOSED;
                destinationResolver.cancelCurrent();
                pendingDispatches.markUncertainAndDetachMessages();
                pendingRecordings.clearMappings();
                pendingTextMessages.clearMappings();
                pendingTextSends.clear();
            }
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
        Client expectedClient = requireClient();
        expectedClient.send(new TdApi.GetMe(), result -> {
            if (!(result instanceof TdApi.User)) return;
            TdApi.User user = (TdApi.User) result;
            String name = (user.firstName + " " + user.lastName).trim();
            synchronized (configurationLock) {
                if (client != expectedClient || authStage != AuthStage.READY) return;
                if (accountUserId > 0L && accountUserId != user.id) {
                    destinationResolver.cancelCurrent();
                }
                accountName = name;
                accountUserId = user.id;
                accountState = AccountState.USER;
            }
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

    private boolean currentClient(Client expectedClient, long expectedGeneration) {
        return expectedClient != null && client == expectedClient
                && authStage == AuthStage.READY
                && ClientGenerationGate.current(expectedGeneration, clientGeneration);
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

    private void notifyDestinationCatalogChanged(DestinationCatalog catalog) {
        synchronized (stateDeliveryLock) {
            for (Listener listener : listeners) listener.onDestinationCatalogChanged(catalog);
        }
    }

    private void notifyDestinationVerificationPreview(DestinationVerificationPreview preview) {
        synchronized (stateDeliveryLock) {
            for (Listener listener : listeners) {
                listener.onDestinationVerificationPreview(preview);
            }
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
                destinationResolver.cancelCurrent();
                pendingDestinationPreview = null;
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
                || resourceId == R.string.repo_restart_failed
                || resourceId == R.string.repo_destination_account_required
                || resourceId == R.string.repo_destination_identity_incomplete
                || resourceId == R.string.repo_destination_canonical_username_missing
                || resourceId == R.string.repo_destination_missing
                || resourceId == R.string.repo_destination_duplicate
                || resourceId == R.string.repo_destination_identity_changed
                || resourceId == R.string.repo_destination_search_failed
                || resourceId == R.string.repo_destination_user_lookup_failed;
    }

}

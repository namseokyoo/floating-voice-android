package com.sidequestlab.floatingvoice;

import org.drinkless.tdlib.TdApi;

/** TDLib-only adapter for private-bot lookup. It has no message-send capability. */
final class DestinationLookupAdapter {
    interface ResultHandler {
        void onResult(TdApi.Object result);
    }

    interface ClientPort {
        void send(TdApi.Function function, ResultHandler handler);
    }

    interface Callback {
        boolean isCurrent(DestinationResolver.Request request);

        void onRejected(
                DestinationResolver.Request request,
                DestinationResolver.Rejection rejection,
                TdApi.Error error);

        void onResolved(
                DestinationResolver.Request request,
                DestinationResolver.ResolvedPeer peer);
    }

    void resolve(
            DestinationResolver.Request request,
            ClientPort client,
            Callback callback) {
        TdApi.SearchPublicChat search = new TdApi.SearchPublicChat();
        search.username = request.configuredUsername();
        client.send(search, searchResult -> {
            if (!callback.isCurrent(request)) {
                callback.onRejected(
                        request, DestinationResolver.Rejection.STALE_REQUEST, null);
                return;
            }
            if (searchResult instanceof TdApi.Error error) {
                callback.onRejected(
                        request, DestinationResolver.Rejection.SEARCH_FAILED, error);
                return;
            }
            if (!(searchResult instanceof TdApi.Chat chat)) {
                callback.onRejected(
                        request, DestinationResolver.Rejection.UNEXPECTED_RESPONSE, null);
                return;
            }
            if (!(chat.type instanceof TdApi.ChatTypePrivate privateChat)) {
                callback.onResolved(request, new DestinationResolver.ResolvedPeer(
                        false, false, chat.id, 0L, "", safeText(chat.title)));
                return;
            }

            TdApi.GetUser getUser = new TdApi.GetUser();
            getUser.userId = privateChat.userId;
            client.send(getUser, userResult -> {
                if (!callback.isCurrent(request)) {
                    callback.onRejected(
                            request, DestinationResolver.Rejection.STALE_REQUEST, null);
                    return;
                }
                if (userResult instanceof TdApi.Error error) {
                    callback.onRejected(
                            request, DestinationResolver.Rejection.USER_LOOKUP_FAILED, error);
                    return;
                }
                if (!(userResult instanceof TdApi.User user)) {
                    callback.onRejected(
                            request, DestinationResolver.Rejection.UNEXPECTED_RESPONSE, null);
                    return;
                }
                if (user.id != privateChat.userId) {
                    callback.onRejected(
                            request, DestinationResolver.Rejection.UNEXPECTED_RESPONSE, null);
                    return;
                }
                callback.onResolved(request, new DestinationResolver.ResolvedPeer(
                        true,
                        user.type instanceof TdApi.UserTypeBot,
                        chat.id,
                        user.id,
                        primaryUsername(user),
                        safeText(chat.title)));
            });
        });
    }

    private static String primaryUsername(TdApi.User user) {
        if (user.usernames == null || user.usernames.activeUsernames == null
                || user.usernames.activeUsernames.length == 0) return "";
        return safeText(user.usernames.activeUsernames[0]);
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }
}

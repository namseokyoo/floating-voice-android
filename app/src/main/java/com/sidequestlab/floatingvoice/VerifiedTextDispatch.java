package com.sidequestlab.floatingvoice;

import com.sidequestlab.floatingvoice.core.DispatchTargetSnapshot;
import com.sidequestlab.floatingvoice.core.DestinationCatalog;

import org.drinkless.tdlib.TdApi;

/** Pure validation/request seam for one frozen verified Telegram text output. */
final class VerifiedTextDispatch {
    enum Rejection { NONE, BLANK_TEXT, ACCOUNT_MISMATCH, DESTINATION_STALE }

    static final class Result {
        private final TdApi.SendMessage request;
        private final Rejection rejection;

        private Result(TdApi.SendMessage request, Rejection rejection) {
            this.request = request;
            this.rejection = rejection;
        }

        static Result accepted(TdApi.SendMessage request) {
            return new Result(request, Rejection.NONE);
        }

        static Result rejected(Rejection rejection) {
            return new Result(null, rejection);
        }

        boolean accepted() { return request != null; }
        TdApi.SendMessage request() { return request; }
        Rejection rejection() { return rejection; }
    }

    private VerifiedTextDispatch() { }

    static Result prepare(String text, DispatchTargetSnapshot target,
                          long authenticatedAccountUserId, DestinationCatalog catalog) {
        if (text == null || text.isBlank()) {
            return Result.rejected(Rejection.BLANK_TEXT);
        }
        if (target == null || target.accountUserId() != authenticatedAccountUserId) {
            return Result.rejected(Rejection.ACCOUNT_MISMATCH);
        }
        if (catalog == null) return Result.rejected(Rejection.DESTINATION_STALE);
        com.sidequestlab.floatingvoice.core.Destination latest = catalog.selectable(
                target.localId(), authenticatedAccountUserId).orElse(null);
        if (latest == null
                || latest.accountUserId() != target.accountUserId()
                || latest.chatId() != target.chatId()
                || latest.peerUserId() != target.peerUserId()
                || latest.verificationRevision() != target.verificationRevision()) {
            return Result.rejected(Rejection.DESTINATION_STALE);
        }

        TdApi.InputMessageText content = new TdApi.InputMessageText(
                new TdApi.FormattedText(text, new TdApi.TextEntity[0]), null, false);
        TdApi.SendMessage request = new TdApi.SendMessage();
        request.chatId = target.chatId();
        request.topicId = null;
        request.replyTo = null;
        request.options = new TdApi.MessageSendOptions();
        request.replyMarkup = null;
        request.inputMessageContent = content;
        return Result.accepted(request);
    }
}

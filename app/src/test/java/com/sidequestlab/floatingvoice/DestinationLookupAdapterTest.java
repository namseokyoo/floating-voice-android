package com.sidequestlab.floatingvoice;

import com.sidequestlab.floatingvoice.core.Destination;
import com.sidequestlab.floatingvoice.core.DestinationCatalog;

import org.drinkless.tdlib.TdApi;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class DestinationLookupAdapterTest {
    @Test
    public void searchAndUserFailuresPersistNothingAndNeverSendMessage() {
        Harness searchFailure = new Harness(error(404, "not found"), null);
        searchFailure.start("missing_bot");
        assertEquals(DestinationResolver.Rejection.SEARCH_FAILED, searchFailure.rejection);
        assertEquals(0, searchFailure.persistCount);
        assertEquals(0, searchFailure.sendMessageCount());

        Harness userFailure = new Harness(privateChat(100L, 500L), error(500, "user failed"));
        userFailure.start("broken_bot");
        assertEquals(DestinationResolver.Rejection.USER_LOOKUP_FAILED, userFailure.rejection);
        assertEquals(0, userFailure.persistCount);
        assertEquals(0, userFailure.sendMessageCount());
    }

    @Test
    public void nonPrivateAndNonBotResultsPersistNothingAndNeverSendMessage() {
        Harness nonPrivate = new Harness(nonPrivateChat(100L), null);
        nonPrivate.start("group_bot");
        assertEquals(DestinationResolver.Rejection.NOT_PRIVATE_CHAT, nonPrivate.rejection);
        assertEquals(0, nonPrivate.persistCount);
        assertEquals(0, nonPrivate.sendMessageCount());

        Harness nonBot = new Harness(privateChat(101L, 501L), user(501L, false, "person_user"));
        nonBot.start("person_user");
        assertEquals(DestinationResolver.Rejection.NOT_BOT, nonBot.rejection);
        assertEquals(0, nonBot.persistCount);
        assertEquals(0, nonBot.sendMessageCount());
    }

    @Test
    public void validPrivateBotPersistsOnceAndNeverSendsMessage() {
        Harness valid = new Harness(privateChat(100L, 500L), user(500L, true, "family_bot"));
        valid.start("family_bot");

        assertNull(valid.rejection);
        assertEquals(1, valid.persistCount);
        assertEquals(2, valid.functions.size());
        assertTrue(valid.functions.get(0) instanceof TdApi.SearchPublicChat);
        assertTrue(valid.functions.get(1) instanceof TdApi.GetUser);
        assertEquals(0, valid.sendMessageCount());
    }

    @Test
    public void mismatchedPrivateChatAndUserIdentityIsRejected() {
        Harness mismatch = new Harness(
                privateChat(100L, 500L), user(999L, true, "family_bot"));
        mismatch.start("family_bot");

        assertEquals(DestinationResolver.Rejection.UNEXPECTED_RESPONSE,
                mismatch.rejection);
        assertEquals(0, mismatch.persistCount);
        assertEquals(0, mismatch.sendMessageCount());
    }

    @Test
    public void reverifyUsesTheSameLookupOnlyPathAndNeverSendsMessage() {
        Harness reverify = new Harness(
                privateChat(100L, 500L), user(500L, true, "family_bot"));
        reverify.startReverify("family_bot");

        assertNull(reverify.rejection);
        assertEquals(1, reverify.persistCount);
        assertEquals(0, reverify.sendMessageCount());
    }

    private static final class Harness implements DestinationLookupAdapter.ClientPort,
            DestinationLookupAdapter.Callback {
        private final TdApi.Object searchResult;
        private final TdApi.Object userResult;
        private final DestinationResolver resolver = new DestinationResolver();
        private final List<TdApi.Function> functions = new ArrayList<>();
        private DestinationCatalog catalog = DestinationCatalog.empty();
        private DestinationResolver.Request request;
        private DestinationResolver.Rejection rejection;
        private int persistCount;

        private Harness(TdApi.Object searchResult, TdApi.Object userResult) {
            this.searchResult = searchResult;
            this.userResult = userResult;
        }

        private void start(String username) {
            DestinationResolver.StartResult start = resolver.startAdd(7L, username, "Alias");
            request = start.request();
            new DestinationLookupAdapter().resolve(request, this, this);
        }

        private void startReverify(String username) {
            Destination existing = new Destination(
                    "existing", 7L, 100L, 500L,
                    username, username, "Private bot", "Alias",
                    Destination.VerificationStatus.VERIFIED,
                    1L, 1_700_000_000_000L, true);
            catalog = DestinationCatalog.restore(List.of(existing), existing.localId());
            DestinationResolver.StartResult start = resolver.startReverify(
                    7L, existing.localId(), username, "Alias");
            request = start.request();
            new DestinationLookupAdapter().resolve(request, this, this);
        }

        @Override
        public void send(TdApi.Function function, DestinationLookupAdapter.ResultHandler handler) {
            functions.add(function);
            if (function instanceof TdApi.SearchPublicChat) {
                handler.onResult(searchResult);
            } else if (function instanceof TdApi.GetUser) {
                handler.onResult(userResult);
            } else {
                fail("Unexpected TDLib function: " + function.getClass().getSimpleName());
            }
        }

        @Override
        public boolean isCurrent(DestinationResolver.Request candidate) {
            return resolver.isCurrent(candidate);
        }

        @Override
        public void onRejected(DestinationResolver.Request candidate,
                               DestinationResolver.Rejection reason,
                               TdApi.Error error) {
            rejection = resolver.reject(candidate, reason).rejection();
        }

        @Override
        public void onResolved(DestinationResolver.Request candidate,
                               DestinationResolver.ResolvedPeer peer) {
            DestinationResolver.Resolution result = resolver.complete(
                    candidate, 7L, catalog, peer,
                    1_700_000_000_000L);
            rejection = result.rejection();
            if (result.accepted()) persistCount++;
        }

        private int sendMessageCount() {
            int count = 0;
            for (TdApi.Function function : functions) {
                if (function instanceof TdApi.SendMessage) count++;
            }
            return count;
        }
    }

    private static TdApi.Error error(int code, String message) {
        TdApi.Error value = new TdApi.Error();
        value.code = code;
        value.message = message;
        return value;
    }

    private static TdApi.Chat nonPrivateChat(long chatId) {
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = chatId;
        chat.title = "Not private";
        chat.type = null;
        return chat;
    }

    private static TdApi.Chat privateChat(long chatId, long userId) {
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = chatId;
        chat.title = "Private bot";
        TdApi.ChatTypePrivate type = new TdApi.ChatTypePrivate();
        type.userId = userId;
        chat.type = type;
        return chat;
    }

    private static TdApi.User user(long userId, boolean bot, String username) {
        TdApi.User user = new TdApi.User();
        user.id = userId;
        user.type = bot ? new TdApi.UserTypeBot() : new TdApi.UserTypeRegular();
        user.usernames = new TdApi.Usernames();
        user.usernames.activeUsernames = new String[]{username};
        return user;
    }
}

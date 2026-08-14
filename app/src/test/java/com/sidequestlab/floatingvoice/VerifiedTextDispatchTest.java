package com.sidequestlab.floatingvoice;

import com.sidequestlab.floatingvoice.core.DispatchTargetSnapshot;
import com.sidequestlab.floatingvoice.core.Destination;
import com.sidequestlab.floatingvoice.core.DestinationCatalog;

import org.drinkless.tdlib.TdApi;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VerifiedTextDispatchTest {
    @Test
    public void frozenVerifiedTargetBuildsRequestForItsExactChatAndPreservesReviewedText() {
        Destination secondary = destination("secondary", 200L, 600L, 5L);
        DestinationCatalog catalog = new DestinationCatalog(List.of(secondary));
        DispatchTargetSnapshot target = DispatchTargetSnapshot.restore(
                9L, secondary.localId(), secondary.accountUserId(), secondary.chatId(),
                secondary.peerUserId(), secondary.configuredUsername(),
                secondary.resolvedUsername(), secondary.resolvedTitle(), secondary.userAlias(),
                secondary.verificationRevision(), secondary.verifiedAtEpochMillis());
        String reviewed = "  둘째 봇으로 보낼 한국어 · Unicode 문장  ";

        VerifiedTextDispatch.Result result = VerifiedTextDispatch.prepare(
                reviewed, target, 7L, catalog);

        assertTrue(result.accepted());
        TdApi.SendMessage request = result.request();
        assertEquals(200L, request.chatId);
        TdApi.InputMessageText content = (TdApi.InputMessageText) request.inputMessageContent;
        assertEquals(reviewed, content.text.text);
    }

    @Test
    public void changedAccountOrVerificationRevisionRejectsWithoutAnotherDestinationFallback() {
        Destination captured = destination("secondary", 200L, 600L, 5L);
        DispatchTargetSnapshot target = DispatchTargetSnapshot.restore(
                9L, captured.localId(), captured.accountUserId(), captured.chatId(),
                captured.peerUserId(), captured.configuredUsername(),
                captured.resolvedUsername(), captured.resolvedTitle(), captured.userAlias(),
                captured.verificationRevision(), captured.verifiedAtEpochMillis());

        VerifiedTextDispatch.Result changedAccount = VerifiedTextDispatch.prepare(
                "보낼 문장", target, 8L, new DestinationCatalog(List.of(captured)));
        VerifiedTextDispatch.Result changedRevision = VerifiedTextDispatch.prepare(
                "보낼 문장", target, 7L,
                new DestinationCatalog(List.of(destination("secondary", 200L, 600L, 6L))));

        assertFalse(changedAccount.accepted());
        assertEquals(VerifiedTextDispatch.Rejection.ACCOUNT_MISMATCH,
                changedAccount.rejection());
        assertFalse(changedRevision.accepted());
        assertEquals(VerifiedTextDispatch.Rejection.DESTINATION_STALE,
                changedRevision.rejection());
    }

    private static Destination destination(
            String localId, long chatId, long peerUserId, long revision) {
        return new Destination(
                localId, 7L, chatId, peerUserId,
                localId + "_configured", localId + "_resolved",
                "Resolved " + localId, "Alias " + localId,
                Destination.VerificationStatus.VERIFIED,
                revision, 1_700_000_000_000L + revision, true);
    }
}

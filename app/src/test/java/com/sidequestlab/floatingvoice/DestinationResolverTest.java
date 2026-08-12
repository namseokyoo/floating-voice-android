package com.sidequestlab.floatingvoice;

import com.sidequestlab.floatingvoice.core.Destination;
import com.sidequestlab.floatingvoice.core.DestinationCatalog;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class DestinationResolverTest {
    @Test
    public void laterRequestMakesEarlierAsyncCompletionStale() {
        DestinationResolver resolver = new DestinationResolver();
        DestinationResolver.StartResult first = resolver.startAdd(
                7L, "@first_bot", "First");
        DestinationResolver.StartResult second = resolver.startAdd(
                7L, "@second_bot", "Second");

        assertTrue(first.started());
        assertTrue(second.started());
        DestinationResolver.Resolution stale = resolver.complete(
                first.request(), 7L, DestinationCatalog.empty(),
                new DestinationResolver.ResolvedPeer(
                        true, true, 100L, 500L,
                        "first_bot", "First Bot"),
                1_700_000_000_000L);

        assertFalse(stale.accepted());
        assertEquals(DestinationResolver.Rejection.STALE_REQUEST, stale.rejection());
        assertNull(stale.destination());
    }

    @Test
    public void validPrivateBotProducesCompleteVerifiedIdentityWithoutSending() {
        DestinationResolver resolver = new DestinationResolver();
        DestinationResolver.StartResult start = resolver.startAdd(
                7L, "https://t.me/Family_Bot", "가족 봇");

        DestinationResolver.Resolution result = resolver.complete(
                start.request(), 7L, DestinationCatalog.empty(),
                new DestinationResolver.ResolvedPeer(
                        true, true, -10042L, 500L,
                        "family_bot", "Family Bot"),
                1_700_000_000_000L);

        assertTrue(result.accepted());
        assertNull(result.rejection());
        assertEquals("bot:7:500", result.destination().localId());
        assertEquals(7L, result.destination().accountUserId());
        assertEquals(-10042L, result.destination().chatId());
        assertEquals(500L, result.destination().peerUserId());
        assertEquals("family_bot", result.destination().configuredUsername());
        assertEquals("family_bot", result.destination().resolvedUsername());
        assertEquals("Family Bot", result.destination().resolvedTitle());
        assertEquals("가족 봇", result.destination().userAlias());
        assertEquals(1L, result.destination().verificationRevision());
        assertEquals(1_700_000_000_000L, result.destination().verifiedAtEpochMillis());
        assertTrue(result.destination().selectableBy(7L));
    }

    @Test
    public void nonPrivateSearchResultIsRejectedBeforeAnyDestinationExists() {
        DestinationResolver resolver = new DestinationResolver();
        DestinationResolver.StartResult start = resolver.startAdd(7L, "group_bot", "Group");

        DestinationResolver.Resolution result = resolver.complete(
                start.request(), 7L, DestinationCatalog.empty(),
                new DestinationResolver.ResolvedPeer(
                        false, true, -100L, 500L, "group_bot", "Group"),
                1_700_000_000_000L);

        assertFalse(result.accepted());
        assertEquals(DestinationResolver.Rejection.NOT_PRIVATE_CHAT, result.rejection());
    }

    @Test
    public void nonBotAndMissingCanonicalUsernameAreRejected() {
        DestinationResolver resolver = new DestinationResolver();
        DestinationResolver.StartResult nonBot = resolver.startAdd(7L, "person_user", "Person");
        DestinationResolver.Resolution nonBotResult = resolver.complete(
                nonBot.request(), 7L, DestinationCatalog.empty(),
                new DestinationResolver.ResolvedPeer(
                        true, false, 101L, 501L, "person_user", "Person"),
                1_700_000_000_000L);

        DestinationResolver.StartResult missingName = resolver.startAdd(7L, "public_bot", "Bot");
        DestinationResolver.Resolution missingNameResult = resolver.complete(
                missingName.request(), 7L, DestinationCatalog.empty(),
                new DestinationResolver.ResolvedPeer(
                        true, true, 102L, 502L, "", "Bot"),
                1_700_000_000_000L);

        assertEquals(DestinationResolver.Rejection.NOT_BOT, nonBotResult.rejection());
        assertEquals(DestinationResolver.Rejection.MISSING_CANONICAL_USERNAME,
                missingNameResult.rejection());
    }

    @Test
    public void accountChangeDuringLookupRejectsTheResult() {
        DestinationResolver resolver = new DestinationResolver();
        DestinationResolver.StartResult start = resolver.startAdd(7L, "family_bot", "Family");

        DestinationResolver.Resolution result = resolver.complete(
                start.request(), 8L, DestinationCatalog.empty(),
                new DestinationResolver.ResolvedPeer(
                        true, true, 100L, 500L, "family_bot", "Family"),
                1_700_000_000_000L);

        assertFalse(result.accepted());
        assertEquals(DestinationResolver.Rejection.ACCOUNT_MISMATCH, result.rejection());
    }

    @Test
    public void legacyReverifyFillsUnknownIdentityOnceAndKeepsLocalId() {
        Destination legacy = new Destination(
                "legacy-chat:100", 0L, 100L, 0L,
                "family_bot", "family_bot", "Family", "Family",
                Destination.VerificationStatus.NEEDS_REVERIFY,
                0L, 0L, true);
        DestinationCatalog catalog = DestinationCatalog.restore(
                List.of(legacy), legacy.localId());
        DestinationResolver resolver = new DestinationResolver();
        DestinationResolver.StartResult start = resolver.startReverify(
                7L, legacy.localId(), "family_bot", "가족 봇");

        DestinationResolver.Resolution result = resolver.complete(
                start.request(), 7L, catalog,
                new DestinationResolver.ResolvedPeer(
                        true, true, 100L, 500L, "family_bot", "Family Bot"),
                1_700_000_000_000L);

        assertTrue(result.accepted());
        assertEquals(legacy.localId(), result.destination().localId());
        assertEquals(7L, result.destination().accountUserId());
        assertEquals(500L, result.destination().peerUserId());
        assertEquals(1L, result.destination().verificationRevision());
    }

    @Test
    public void duplicateAddAndIdentityChangingReverifyAreBlocked() {
        Destination existing = verified("primary", 100L, 500L, "same_title");
        DestinationCatalog catalog = DestinationCatalog.restore(
                List.of(existing), existing.localId());
        DestinationResolver resolver = new DestinationResolver();

        DestinationResolver.StartResult duplicate = resolver.startAdd(
                7L, "renamed_bot", "Duplicate");
        DestinationResolver.Resolution duplicateResult = resolver.complete(
                duplicate.request(), 7L, catalog,
                new DestinationResolver.ResolvedPeer(
                        true, true, 100L, 500L, "renamed_bot", "Same title"),
                1_700_000_100_000L);

        DestinationResolver.StartResult changed = resolver.startReverify(
                7L, existing.localId(), "primary_bot", "Changed");
        DestinationResolver.Resolution changedResult = resolver.complete(
                changed.request(), 7L, catalog,
                new DestinationResolver.ResolvedPeer(
                        true, true, 999L, 999L, "primary_bot", "Same title"),
                1_700_000_100_000L);

        assertEquals(DestinationResolver.Rejection.DUPLICATE_DESTINATION,
                duplicateResult.rejection());
        assertEquals(DestinationResolver.Rejection.IDENTITY_CHANGED,
                changedResult.rejection());
    }

    @Test
    public void equalTitlesDoNotMergeDifferentCanonicalBots() {
        Destination first = verified("first", 100L, 500L, "Same title");
        DestinationCatalog catalog = DestinationCatalog.restore(
                List.of(first), first.localId());
        DestinationResolver resolver = new DestinationResolver();
        DestinationResolver.StartResult second = resolver.startAdd(
                7L, "second_bot", "Second alias");

        DestinationResolver.Resolution result = resolver.complete(
                second.request(), 7L, catalog,
                new DestinationResolver.ResolvedPeer(
                        true, true, 200L, 600L, "second_bot", "Same title"),
                1_700_000_100_000L);

        assertTrue(result.accepted());
        assertEquals("bot:7:600", result.destination().localId());
        assertEquals("Same title", result.destination().resolvedTitle());
    }

    @Test
    public void adapterFailureTerminatesOnlyTheCurrentRequest() {
        DestinationResolver resolver = new DestinationResolver();
        DestinationResolver.StartResult first = resolver.startAdd(7L, "first_bot", "First");
        DestinationResolver.StartResult second = resolver.startAdd(7L, "second_bot", "Second");

        DestinationResolver.Resolution staleFailure = resolver.reject(
                first.request(), DestinationResolver.Rejection.SEARCH_FAILED);
        assertEquals(DestinationResolver.Rejection.STALE_REQUEST, staleFailure.rejection());
        assertTrue(resolver.isCurrent(second.request()));

        DestinationResolver.Resolution currentFailure = resolver.reject(
                second.request(), DestinationResolver.Rejection.SEARCH_FAILED);
        assertEquals(DestinationResolver.Rejection.SEARCH_FAILED, currentFailure.rejection());
        assertFalse(resolver.isCurrent(second.request()));
    }

    @Test
    public void incompleteCanonicalIdsAreRejectedInsteadOfThrowing() {
        DestinationResolver resolver = new DestinationResolver();
        DestinationResolver.StartResult start = resolver.startAdd(7L, "broken_bot", "Broken");

        DestinationResolver.Resolution result = resolver.complete(
                start.request(), 7L, DestinationCatalog.empty(),
                new DestinationResolver.ResolvedPeer(
                        true, true, 0L, 0L, "broken_bot", "Broken"),
                1_700_000_000_000L);

        assertFalse(result.accepted());
        assertEquals(DestinationResolver.Rejection.INCOMPLETE_IDENTITY, result.rejection());
    }

    @Test
    public void newerInvalidAttemptInvalidatesAnOlderLookup() {
        DestinationResolver resolver = new DestinationResolver();
        DestinationResolver.StartResult older = resolver.startAdd(
                7L, "older_bot", "Older");

        DestinationResolver.StartResult invalid = resolver.startAdd(
                7L, "not a username", "Invalid");
        DestinationResolver.Resolution late = resolver.complete(
                older.request(), 7L, DestinationCatalog.empty(),
                new DestinationResolver.ResolvedPeer(
                        true, true, 100L, 500L, "older_bot", "Older"),
                1_700_000_000_000L);

        assertFalse(invalid.started());
        assertEquals(DestinationResolver.Rejection.INVALID_USERNAME, invalid.rejection());
        assertEquals(DestinationResolver.Rejection.STALE_REQUEST, late.rejection());
    }

    @Test
    public void reverifyPreservesDisabledState() {
        Destination disabled = new Destination(
                "disabled", 7L, 100L, 500L,
                "disabled_bot", "disabled_bot", "Disabled", "Disabled",
                Destination.VerificationStatus.DISABLED,
                1L, 1_700_000_000_000L, false);
        DestinationCatalog catalog = DestinationCatalog.restore(List.of(disabled), null);
        DestinationResolver resolver = new DestinationResolver();
        DestinationResolver.StartResult start = resolver.startReverify(
                7L, disabled.localId(), "disabled_bot", "Disabled alias");

        DestinationResolver.Resolution result = resolver.complete(
                start.request(), 7L, catalog,
                new DestinationResolver.ResolvedPeer(
                        true, true, 100L, 500L, "disabled_bot", "Disabled"),
                1_700_000_100_000L);

        assertTrue(result.accepted());
        assertEquals(Destination.VerificationStatus.DISABLED,
                result.destination().verificationStatus());
        assertFalse(result.destination().enabled());
        assertFalse(result.destination().selectableBy(7L));
    }

    private static Destination verified(
            String localId, long chatId, long peerUserId, String title) {
        return new Destination(
                localId, 7L, chatId, peerUserId,
                localId + "_bot", localId + "_bot", title, title,
                Destination.VerificationStatus.VERIFIED,
                1L, 1_700_000_000_000L, true);
    }
}

package com.sidequestlab.floatingvoice.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DestinationMigrationTest {
    private static final String API_HASH = "0123456789abcdef0123456789abcdef";

    @Test
    void migratesValidLegacyAccountAndTargetAsBlockedCandidate() {
        LegacyConfigSnapshot snapshot = new LegacyConfigSnapshot(
                "123456", API_HASH, "+12025550123", "@Legacy_Bot",
                "4242", "Legacy Bot", "legacy_bot", "/private/pending/voice.ogg");

        DestinationMigration.Result result = DestinationMigration.migrate(
                snapshot, DestinationMigration.State.empty());

        assertTrue(result.isMigratable());
        assertEquals(123456, result.state().account().orElseThrow().apiId());
        assertEquals(API_HASH, result.state().account().orElseThrow().apiHash());
        assertEquals("+12025550123", result.state().account().orElseThrow().phoneNumber());
        assertEquals(1, result.state().candidates().size());

        DestinationMigration.LegacyDestinationCandidate candidate =
                result.state().candidates().get(0);
        assertEquals("legacy-chat:4242", candidate.migrationKey());
        assertEquals(4242L, candidate.chatId());
        assertEquals("Legacy Bot", candidate.title());
        assertEquals("legacy_bot", candidate.username());
        assertEquals(DestinationMigration.VerificationStatus.NEEDS_REVERIFY,
                candidate.verificationStatus());
        assertEquals(candidate.migrationKey(),
                result.state().defaultCandidateMigrationKey().orElseThrow());
        assertTrue(result.retainsLegacyKeys());
        assertTrue(result.preservesPendingRecordings());
        assertEquals("/private/pending/voice.ogg", snapshot.pendingRecordingPath());
    }

    @Test
    void preservesValidAccountWhenLegacyTargetIsAbsent() {
        LegacyConfigSnapshot snapshot = new LegacyConfigSnapshot(
                "123456", API_HASH, "+12025550123", "legacy_bot",
                null, null, null, null);

        DestinationMigration.Result result = DestinationMigration.migrate(
                snapshot, DestinationMigration.State.empty());

        assertTrue(result.isMigratable());
        assertEquals(123456, result.state().account().orElseThrow().apiId());
        assertTrue(result.state().candidates().isEmpty());
    }

    @Test
    void accountOnlySnapshotClearsCandidateFromAnEarlierUncommittedAttempt() {
        DestinationMigration.State earlierAttempt = validPlan().state();
        LegacyConfigSnapshot accountOnly = new LegacyConfigSnapshot(
                "123456", API_HASH, "+12025550123", "legacy_bot",
                null, null, null, null);

        DestinationMigration.Result result =
                DestinationMigration.migrate(accountOnly, earlierAttempt);

        assertTrue(result.isMigratable());
        assertTrue(result.state().candidates().isEmpty());
    }

    @Test
    void preservesAccountWithoutSchemaCommitWhenLegacyTargetIsPartial() {
        LegacyConfigSnapshot snapshot = new LegacyConfigSnapshot(
                "123456", API_HASH, "+12025550123", "legacy_bot",
                "4242", null, "legacy_bot", null);

        DestinationMigration.Result result = DestinationMigration.migrate(
                snapshot, DestinationMigration.State.empty());

        assertFalse(result.isMigratable());
        assertEquals(123456, result.state().account().orElseThrow().apiId());
        assertTrue(result.state().candidates().isEmpty());
        assertEquals(java.util.List.of(DestinationMigration.Error.INVALID_LEGACY_TARGET),
                result.errors());
        assertTrue(result.accountPersistenceEligible());
        assertFalse(result.schemaMarkerEligible());

        AccountOnlyReloadingSink sink = new AccountOnlyReloadingSink();
        DestinationMigration.CommitResult persisted =
                DestinationMigration.persist(result, sink);
        assertEquals(DestinationMigration.CommitStatus.ACCOUNT_ONLY_WRITTEN,
                persisted.status());
        assertEquals(java.util.List.of("write", "readBack"), sink.calls);
    }

    @Test
    void rejectsMalformedIdAndIdentityMismatchedTargetFixtures() {
        java.util.List<LegacyConfigSnapshot> corruptFixtures = java.util.List.of(
                new LegacyConfigSnapshot(
                        "123456", API_HASH, "+12025550123", "legacy_bot",
                        "not-a-chat-id", "Legacy Bot", "legacy_bot", null),
                new LegacyConfigSnapshot(
                        "123456", API_HASH, "+12025550123", "legacy_bot",
                        "4242", "Other Bot", "other_bot", null));

        for (LegacyConfigSnapshot fixture : corruptFixtures) {
            DestinationMigration.Result result = DestinationMigration.migrate(
                    fixture, DestinationMigration.State.empty());
            assertFalse(result.isMigratable());
            assertTrue(result.accountPersistenceEligible());
            assertEquals(java.util.List.of(
                    DestinationMigration.Error.INVALID_LEGACY_TARGET), result.errors());
            assertTrue(result.state().candidates().isEmpty());
        }
    }

    @Test
    void rerunningMigrationDoesNotDuplicateLegacyCandidate() {
        LegacyConfigSnapshot snapshot = new LegacyConfigSnapshot(
                "123456", API_HASH, "+12025550123", "legacy_bot",
                "4242", "Legacy Bot", "legacy_bot", null);

        DestinationMigration.Result first = DestinationMigration.migrate(
                snapshot, DestinationMigration.State.empty());
        DestinationMigration.Result second = DestinationMigration.migrate(
                snapshot, first.state());

        assertTrue(second.isMigratable());
        assertEquals(1, second.state().candidates().size());
        assertEquals(first.state().candidates().get(0).migrationKey(),
                second.state().candidates().get(0).migrationKey());
    }

    @Test
    void reloadedStateRemainsIdempotentAfterMarkerFailureAndUsernameChange() {
        DestinationMigration.Result firstPlan = validPlan();
        ReloadingSink sink = new ReloadingSink();
        sink.failMarkerWrite = true;

        DestinationMigration.CommitResult firstCommit =
                DestinationMigration.persist(firstPlan, sink);
        assertEquals(DestinationMigration.CommitStatus.MARKER_WRITE_FAILED,
                firstCommit.status());

        LegacyConfigSnapshot renamedUsername = new LegacyConfigSnapshot(
                "123456", API_HASH, "+12025550123", "renamed_bot",
                "4242", "Renamed Bot", "renamed_bot", null);
        DestinationMigration.Result rerun = DestinationMigration.migrate(
                renamedUsername, sink.reloadState());

        assertEquals(1, rerun.state().candidates().size());
        assertEquals("legacy-chat:4242",
                rerun.state().candidates().get(0).migrationKey());
        assertEquals("renamed_bot", rerun.state().candidates().get(0).username());
        assertEquals("legacy-chat:4242",
                rerun.state().defaultCandidateMigrationKey().orElseThrow());
    }

    @Test
    void marksSchemaCommittedOnlyAfterSuccessfulWriteAndReadBack() {
        DestinationMigration.Result plan = validPlan();
        RecordingSink sink = new RecordingSink();

        DestinationMigration.CommitResult committed =
                DestinationMigration.persist(plan, sink);

        assertEquals(DestinationMigration.CommitStatus.COMMITTED, committed.status());
        assertEquals(java.util.List.of("write", "readBack", "markCommitted"), sink.calls);
    }

    @Test
    void writeFailureNeverReadsBackOrMarksSchemaCommitted() {
        RecordingSink sink = new RecordingSink();
        java.util.Map<String, String> legacyBefore =
                java.util.Map.copyOf(sink.legacyKeys);
        java.util.Map<String, String> pendingBefore =
                java.util.Map.copyOf(sink.pendingRecordings);
        sink.failWrite = true;

        DestinationMigration.CommitResult failed =
                DestinationMigration.persist(validPlan(), sink);

        assertEquals(DestinationMigration.CommitStatus.WRITE_FAILED, failed.status());
        assertEquals(java.util.List.of("write"), sink.calls);
        assertTrue(validPlan().retainsLegacyKeys());
        assertEquals(legacyBefore, sink.legacyKeys);
        assertEquals(pendingBefore, sink.pendingRecordings);
    }

    @Test
    void readBackMismatchNeverMarksSchemaCommitted() {
        RecordingSink sink = new RecordingSink();
        sink.readBackMatches = false;

        DestinationMigration.CommitResult failed =
                DestinationMigration.persist(validPlan(), sink);

        assertEquals(DestinationMigration.CommitStatus.READ_BACK_FAILED, failed.status());
        assertEquals(java.util.List.of("write", "readBack"), sink.calls);
    }

    @Test
    void truncatedReloadStateFailsBeforeSchemaMarker() {
        TruncatingSink sink = new TruncatingSink();

        DestinationMigration.CommitResult failed =
                DestinationMigration.persist(validPlan(), sink);

        assertEquals(DestinationMigration.CommitStatus.READ_BACK_FAILED, failed.status());
        assertEquals(java.util.List.of("write", "readBack"), sink.calls);
    }

    @Test
    void invalidAccountNeverTouchesPersistence() {
        DestinationMigration.Result invalidPlan = DestinationMigration.migrate(
                new LegacyConfigSnapshot(
                        "bad", API_HASH, "+12025550123", "legacy_bot",
                        "4242", "Legacy Bot", "legacy_bot", null),
                DestinationMigration.State.empty());
        RecordingSink sink = new RecordingSink();

        DestinationMigration.CommitResult blocked =
                DestinationMigration.persist(invalidPlan, sink);

        assertEquals(DestinationMigration.CommitStatus.NOT_ELIGIBLE, blocked.status());
        assertTrue(sink.calls.isEmpty());
    }

    @Test
    void schemaMarkerWriteFailureIsNotReportedAsCommitted() {
        RecordingSink sink = new RecordingSink();
        sink.failMarkerWrite = true;

        DestinationMigration.CommitResult failed =
                DestinationMigration.persist(validPlan(), sink);

        assertEquals(DestinationMigration.CommitStatus.MARKER_WRITE_FAILED, failed.status());
        assertEquals(java.util.List.of("write", "readBack", "markCommitted"), sink.calls);
    }

    private static DestinationMigration.Result validPlan() {
        return DestinationMigration.migrate(new LegacyConfigSnapshot(
                        "123456", API_HASH, "+12025550123", "legacy_bot",
                        "4242", "Legacy Bot", "legacy_bot", null),
                DestinationMigration.State.empty());
    }

    private static final class RecordingSink implements DestinationMigration.MigrationSink {
        private final java.util.List<String> calls = new java.util.ArrayList<>();
        private final java.util.Map<String, String> legacyKeys = new java.util.HashMap<>(
                java.util.Map.of("bot_username", "legacy_bot",
                        "target_chat_id", "4242"));
        private final java.util.Map<String, String> pendingRecordings =
                new java.util.HashMap<>(java.util.Map.of(
                        "pending-1", "/private/pending/voice.ogg"));
        private final java.util.Map<String, String> newState = new java.util.HashMap<>();
        private boolean failWrite;
        private boolean readBackMatches = true;
        private boolean failMarkerWrite;

        @Override public void writeNewStateRetainingLegacyData(
                DestinationMigration.State state) {
            calls.add("write");
            newState.put("account_api_id",
                    Integer.toString(state.account().orElseThrow().apiId()));
            if (failWrite) throw new IllegalStateException("injected write failure");
        }

        @Override public boolean readBackMatches(DestinationMigration.State state) {
            calls.add("readBack");
            return readBackMatches;
        }

        @Override public void markSchemaVersionCommitted() {
            calls.add("markCommitted");
            if (failMarkerWrite) throw new IllegalStateException("injected marker failure");
        }
    }

    private static final class AccountOnlyReloadingSink
            implements DestinationMigration.MigrationSink {
        private final java.util.List<String> calls = new java.util.ArrayList<>();
        private int apiId;
        private String apiHash;
        private String phoneNumber;

        @Override public void writeNewStateRetainingLegacyData(
                DestinationMigration.State state) {
            calls.add("write");
            TelegramAccountConfig account = state.account().orElseThrow();
            apiId = account.apiId();
            apiHash = account.apiHash();
            phoneNumber = account.phoneNumber();
            assertTrue(state.candidates().isEmpty());
            assertTrue(state.defaultCandidateMigrationKey().isEmpty());
        }

        @Override public boolean readBackMatches(DestinationMigration.State state) {
            calls.add("readBack");
            DestinationMigration.State reloaded =
                    DestinationMigration.State.restoreAccountOnly(
                            new TelegramAccountConfig(apiId, apiHash, phoneNumber));
            return reloaded.account().orElseThrow().apiId()
                    == state.account().orElseThrow().apiId()
                    && reloaded.candidates().isEmpty()
                    && reloaded.defaultCandidateMigrationKey().isEmpty();
        }

        @Override public void markSchemaVersionCommitted() {
            throw new AssertionError("account-only migration must not mark schema committed");
        }
    }

    private static final class ReloadingSink implements DestinationMigration.MigrationSink {
        private int apiId;
        private String apiHash;
        private String phoneNumber;
        private String migrationKey;
        private long chatId;
        private String title;
        private String username;
        private String defaultKey;
        private boolean failMarkerWrite;

        @Override public void writeNewStateRetainingLegacyData(
                DestinationMigration.State state) {
            TelegramAccountConfig account = state.account().orElseThrow();
            DestinationMigration.LegacyDestinationCandidate candidate =
                    state.candidates().get(0);
            apiId = account.apiId();
            apiHash = account.apiHash();
            phoneNumber = account.phoneNumber();
            migrationKey = candidate.migrationKey();
            chatId = candidate.chatId();
            title = candidate.title();
            username = candidate.username();
            defaultKey = state.defaultCandidateMigrationKey().orElseThrow();
        }

        @Override public boolean readBackMatches(DestinationMigration.State state) {
            DestinationMigration.State reloaded = reloadState();
            TelegramAccountConfig expectedAccount = state.account().orElseThrow();
            TelegramAccountConfig actualAccount = reloaded.account().orElseThrow();
            if (expectedAccount.apiId() != actualAccount.apiId()
                    || !expectedAccount.apiHash().equals(actualAccount.apiHash())
                    || !expectedAccount.phoneNumber().equals(actualAccount.phoneNumber())
                    || state.candidates().size() != 1
                    || reloaded.candidates().size() != 1) {
                return false;
            }
            DestinationMigration.LegacyDestinationCandidate expected =
                    state.candidates().get(0);
            DestinationMigration.LegacyDestinationCandidate actual =
                    reloaded.candidates().get(0);
            return expected.migrationKey().equals(actual.migrationKey())
                    && expected.chatId() == actual.chatId()
                    && expected.title().equals(actual.title())
                    && expected.username().equals(actual.username())
                    && expected.verificationStatus() == actual.verificationStatus()
                    && reloaded.defaultCandidateMigrationKey()
                            .equals(state.defaultCandidateMigrationKey());
        }

        @Override public void markSchemaVersionCommitted() {
            if (failMarkerWrite) throw new IllegalStateException("injected marker failure");
        }

        DestinationMigration.State reloadState() {
            TelegramAccountConfig account =
                    new TelegramAccountConfig(apiId, apiHash, phoneNumber);
            DestinationMigration.LegacyDestinationCandidate candidate =
                    new DestinationMigration.LegacyDestinationCandidate(
                            migrationKey, chatId, title, username,
                            DestinationMigration.VerificationStatus.NEEDS_REVERIFY);
            return DestinationMigration.State.restore(
                    account, java.util.List.of(candidate), defaultKey);
        }
    }

    private static final class TruncatingSink implements DestinationMigration.MigrationSink {
        private final java.util.List<String> calls = new java.util.ArrayList<>();
        private TelegramAccountConfig account;

        @Override public void writeNewStateRetainingLegacyData(
                DestinationMigration.State state) {
            calls.add("write");
            TelegramAccountConfig original = state.account().orElseThrow();
            account = new TelegramAccountConfig(
                    original.apiId(), original.apiHash(), original.phoneNumber());
        }

        @Override public boolean readBackMatches(DestinationMigration.State state) {
            calls.add("readBack");
            DestinationMigration.State.restore(
                    account, java.util.List.of(), "legacy-chat:4242");
            return false;
        }

        @Override public void markSchemaVersionCommitted() {
            throw new AssertionError("marker must not be written");
        }
    }
}

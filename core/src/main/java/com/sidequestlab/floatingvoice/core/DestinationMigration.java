package com.sidequestlab.floatingvoice.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure migration decision; persistence is connected only after the model is verified. */
public final class DestinationMigration {
    public enum VerificationStatus { NEEDS_REVERIFY }
    public enum Error { INVALID_ACCOUNT, INVALID_LEGACY_TARGET }
    public enum CommitStatus {
        COMMITTED, ACCOUNT_ONLY_WRITTEN, WRITE_FAILED, READ_BACK_FAILED,
        MARKER_WRITE_FAILED, NOT_ELIGIBLE
    }

    public interface MigrationSink {
        /** Additively writes new state without deleting legacy keys or pending recordings. */
        void writeNewStateRetainingLegacyData(State state);
        boolean readBackMatches(State state);
        void markSchemaVersionCommitted();
    }

    private DestinationMigration() {}

    public static CommitResult persist(Result plan, MigrationSink sink) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(sink);
        if (!plan.accountPersistenceEligible()) {
            return new CommitResult(CommitStatus.NOT_ELIGIBLE);
        }
        try {
            sink.writeNewStateRetainingLegacyData(plan.state());
        } catch (RuntimeException error) {
            return new CommitResult(CommitStatus.WRITE_FAILED);
        }
        try {
            if (!sink.readBackMatches(plan.state())) {
                return new CommitResult(CommitStatus.READ_BACK_FAILED);
            }
        } catch (RuntimeException error) {
            return new CommitResult(CommitStatus.READ_BACK_FAILED);
        }
        if (!plan.schemaMarkerEligible()) {
            return new CommitResult(CommitStatus.ACCOUNT_ONLY_WRITTEN);
        }
        try {
            sink.markSchemaVersionCommitted();
        } catch (RuntimeException error) {
            return new CommitResult(CommitStatus.MARKER_WRITE_FAILED);
        }
        return new CommitResult(CommitStatus.COMMITTED);
    }

    public static Result migrate(LegacyConfigSnapshot snapshot, State current) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(current);

        AppConfig.ValidationResult connection = AppConfig.validateConnection(
                snapshot.apiIdText(), snapshot.apiHash(), snapshot.phoneNumber());
        if (!connection.isValid()) {
            return new Result(false, false, current, List.of(Error.INVALID_ACCOUNT));
        }

        AppConfig config = connection.config();
        TelegramAccountConfig account = new TelegramAccountConfig(
                config.apiId(), config.apiHash(), config.phoneNumber());
        if (isBlank(snapshot.targetChatIdText())
                && isBlank(snapshot.targetTitle())
                && isBlank(snapshot.targetUsername())) {
            return new Result(true, true, current.withAccount(account), List.of());
        }
        String username = UsernameNormalizer.normalize(snapshot.targetUsername());
        String configuredUsername = UsernameNormalizer.normalize(snapshot.botUsername());
        long chatId;
        try {
            chatId = Long.parseLong(snapshot.targetChatIdText());
        } catch (RuntimeException error) {
            return invalidTarget(current, account);
        }
        if (chatId == 0L || snapshot.targetTitle() == null
                || snapshot.targetTitle().isBlank()
                || !UsernameNormalizer.isValid(username)
                || !username.equals(configuredUsername)) {
            return invalidTarget(current, account);
        }

        LegacyDestinationCandidate candidate = new LegacyDestinationCandidate(
                "legacy-chat:" + chatId,
                chatId, snapshot.targetTitle(), username,
                VerificationStatus.NEEDS_REVERIFY);
        return new Result(true, true, current.with(account, candidate), List.of());
    }

    private static Result invalidTarget(State current, TelegramAccountConfig account) {
        return new Result(false, true, current.withAccount(account),
                List.of(Error.INVALID_LEGACY_TARGET));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static final class State {
        private final TelegramAccountConfig account;
        private final List<LegacyDestinationCandidate> candidates;
        private final String defaultCandidateMigrationKey;

        private State(TelegramAccountConfig account,
                      List<LegacyDestinationCandidate> candidates,
                      String defaultCandidateMigrationKey) {
            this.account = account;
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            this.defaultCandidateMigrationKey = defaultCandidateMigrationKey;
        }

        public static State empty() { return new State(null, List.of(), null); }
        static State restoreAccountOnly(TelegramAccountConfig account) {
            return new State(Objects.requireNonNull(account), List.of(), null);
        }
        static State restore(TelegramAccountConfig account,
                             List<LegacyDestinationCandidate> candidates,
                             String defaultCandidateMigrationKey) {
            Objects.requireNonNull(account);
            Objects.requireNonNull(candidates);
            if (defaultCandidateMigrationKey == null
                    || candidates.stream().noneMatch(candidate ->
                            candidate.migrationKey().equals(defaultCandidateMigrationKey))) {
                throw new IllegalArgumentException("Default candidate is missing");
            }
            return new State(account, candidates, defaultCandidateMigrationKey);
        }
        public Optional<TelegramAccountConfig> account() { return Optional.ofNullable(account); }
        public List<LegacyDestinationCandidate> candidates() { return candidates; }
        public Optional<String> defaultCandidateMigrationKey() {
            return Optional.ofNullable(defaultCandidateMigrationKey);
        }

        private State withAccount(TelegramAccountConfig replacementAccount) {
            return new State(replacementAccount, List.of(), null);
        }

        private State with(TelegramAccountConfig replacementAccount,
                           LegacyDestinationCandidate candidate) {
            return new State(replacementAccount, List.of(candidate), candidate.migrationKey());
        }
    }

    public static final class LegacyDestinationCandidate {
        private final String migrationKey;
        private final long chatId;
        private final String title;
        private final String username;
        private final VerificationStatus verificationStatus;

        LegacyDestinationCandidate(String migrationKey, long chatId, String title,
                                   String username,
                                   VerificationStatus verificationStatus) {
            this.migrationKey = migrationKey;
            this.chatId = chatId;
            this.title = title;
            this.username = username;
            this.verificationStatus = verificationStatus;
        }

        public String migrationKey() { return migrationKey; }
        public long chatId() { return chatId; }
        public String title() { return title; }
        public String username() { return username; }
        public VerificationStatus verificationStatus() { return verificationStatus; }
    }

    public static final class Result {
        private final boolean migratable;
        private final boolean accountPersistenceEligible;
        private final State state;
        private final List<Error> errors;

        private Result(boolean migratable, boolean accountPersistenceEligible,
                       State state, List<Error> errors) {
            this.migratable = migratable;
            this.accountPersistenceEligible = accountPersistenceEligible;
            this.state = state;
            this.errors = List.copyOf(errors);
        }

        public boolean isMigratable() { return migratable; }
        public boolean accountPersistenceEligible() { return accountPersistenceEligible; }
        public State state() { return state; }
        public List<Error> errors() { return errors; }
        public boolean schemaMarkerEligible() { return migratable; }
        public boolean retainsLegacyKeys() { return true; }
        public boolean preservesPendingRecordings() { return true; }
    }

    public static final class CommitResult {
        private final CommitStatus status;

        private CommitResult(CommitStatus status) {
            this.status = status;
        }

        public CommitStatus status() { return status; }
    }
}

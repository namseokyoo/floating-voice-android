package com.sidequestlab.floatingvoice.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Pure-Java validated runtime configuration. Authentication codes/passwords are intentionally absent. */
public final class AppConfig {
    private static final Pattern API_HASH = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Pattern PHONE = Pattern.compile("\\+[1-9][0-9]{6,14}");

    public enum ValidationError {
        INVALID_API_ID,
        INVALID_API_HASH,
        INVALID_PHONE_NUMBER,
        INVALID_BOT_USERNAME
    }

    private final int apiId;
    private final String apiHash;
    private final String phoneNumber;
    private final String botUsername;

    public AppConfig(int apiId, String apiHash, String phoneNumber, String botUsername) {
        this.apiId = apiId;
        this.apiHash = Objects.requireNonNull(apiHash).trim();
        this.phoneNumber = Objects.requireNonNull(phoneNumber).trim();
        this.botUsername = UsernameNormalizer.normalize(botUsername);
    }

    public static ValidationResult validate(String apiIdText, String apiHash, String phoneNumber,
                                            String botUsername) {
        ValidationResult connection = validateConnection(apiIdText, apiHash, phoneNumber);
        List<ValidationError> errors = new ArrayList<>(connection.errors());
        String normalizedUsername = UsernameNormalizer.normalize(botUsername);
        if (!UsernameNormalizer.isValid(normalizedUsername)) {
            errors.add(ValidationError.INVALID_BOT_USERNAME);
        }
        AppConfig config = errors.isEmpty()
                ? new AppConfig(connection.config().apiId(), connection.config().apiHash(),
                        connection.config().phoneNumber(), normalizedUsername)
                : null;
        return new ValidationResult(config, errors);
    }

    public static ValidationResult validateConnection(String apiIdText, String apiHash,
                                                      String phoneNumber) {
        List<ValidationError> errors = new ArrayList<>();
        int parsedApiId = 0;
        try {
            parsedApiId = Integer.parseInt(apiIdText == null ? "" : apiIdText.trim());
            if (parsedApiId <= 0) {
                errors.add(ValidationError.INVALID_API_ID);
            }
        } catch (NumberFormatException e) {
            errors.add(ValidationError.INVALID_API_ID);
        }

        String normalizedHash = apiHash == null ? "" : apiHash.trim();
        if (!API_HASH.matcher(normalizedHash).matches()) {
            errors.add(ValidationError.INVALID_API_HASH);
        }

        String normalizedPhone = phoneNumber == null ? "" : phoneNumber.replaceAll("[\\s()-]", "");
        if (!PHONE.matcher(normalizedPhone).matches()) {
            errors.add(ValidationError.INVALID_PHONE_NUMBER);
        }

        AppConfig config = errors.isEmpty()
                ? new AppConfig(parsedApiId, normalizedHash, normalizedPhone, "")
                : null;
        return new ValidationResult(config, errors);
    }

    public int apiId() { return apiId; }
    public String apiHash() { return apiHash; }
    public String phoneNumber() { return phoneNumber; }
    public String botUsername() { return botUsername; }
    public AppConfig withBotUsername(String username) {
        return new AppConfig(apiId, apiHash, phoneNumber, username);
    }
    public boolean hasBotUsername() { return UsernameNormalizer.isValid(botUsername); }
    public boolean hasSameConnection(AppConfig other) {
        return other != null && apiId == other.apiId
                && apiHash.equalsIgnoreCase(other.apiHash)
                && phoneNumber.equals(other.phoneNumber);
    }

    public static final class ValidationResult {
        private final AppConfig config;
        private final List<ValidationError> errors;

        private ValidationResult(AppConfig config, List<ValidationError> errors) {
            this.config = config;
            this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
        }

        public boolean isValid() { return config != null; }
        public AppConfig config() {
            if (config == null) throw new IllegalStateException(ValidationError.class.getSimpleName());
            return config;
        }
        public List<ValidationError> errors() { return errors; }
    }
}

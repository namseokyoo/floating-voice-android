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
        List<String> errors = new ArrayList<>();
        int parsedApiId = 0;
        try {
            parsedApiId = Integer.parseInt(apiIdText == null ? "" : apiIdText.trim());
            if (parsedApiId <= 0) {
                errors.add("API ID는 0보다 큰 숫자여야 합니다.");
            }
        } catch (NumberFormatException e) {
            errors.add("API ID는 0보다 큰 숫자여야 합니다.");
        }

        String normalizedHash = apiHash == null ? "" : apiHash.trim();
        if (!API_HASH.matcher(normalizedHash).matches()) {
            errors.add("API Hash는 영문·숫자로 된 정확히 32자리 값이어야 합니다.");
        }

        String normalizedPhone = phoneNumber == null ? "" : phoneNumber.replaceAll("[\\s()-]", "");
        if (!PHONE.matcher(normalizedPhone).matches()) {
            errors.add("전화번호는 국가번호 형식으로 입력해주세요. 예: +821012345678");
        }

        String normalizedUsername = UsernameNormalizer.normalize(botUsername);
        if (!UsernameNormalizer.isValid(normalizedUsername)) {
            errors.add("봇 username은 영문, 숫자, 밑줄로 된 5~32자리여야 합니다.");
        }

        AppConfig config = errors.isEmpty()
                ? new AppConfig(parsedApiId, normalizedHash, normalizedPhone, normalizedUsername)
                : null;
        return new ValidationResult(config, errors);
    }

    public int apiId() { return apiId; }
    public String apiHash() { return apiHash; }
    public String phoneNumber() { return phoneNumber; }
    public String botUsername() { return botUsername; }

    public static final class ValidationResult {
        private final AppConfig config;
        private final List<String> errors;

        private ValidationResult(AppConfig config, List<String> errors) {
            this.config = config;
            this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
        }

        public boolean isValid() { return config != null; }
        public AppConfig config() {
            if (config == null) throw new IllegalStateException("설정값이 올바르지 않습니다");
            return config;
        }
        public List<String> errors() { return errors; }
    }
}

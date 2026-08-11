package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;

import com.sidequestlab.floatingvoice.core.AppConfig;
import com.sidequestlab.floatingvoice.core.DestinationCatalogPersistence;
import com.sidequestlab.floatingvoice.core.LegacyConfigSnapshot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts persisted runtime configuration with a non-exportable Android Keystore key. */
public final class SecureSettingsStore {
    private static final String PREFS = "encrypted_runtime_settings";
    private static final String KEY_ALIAS = "floatingvoice.runtime.v1";
    private static final int ROUTING_SNAPSHOT_VERSION = 1;
    private static final int DESTINATION_SCHEMA_VERSION = 1;
    private static final int MAX_ROUTING_BLOB_BYTES = 1024 * 1024;

    private final SharedPreferences preferences;
    private final AtomicFile routingSnapshotFile;
    private final AtomicFile destinationCatalogFile;

    public SecureSettingsStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        routingSnapshotFile = new AtomicFile(
                new File(context.getFilesDir(), "secure-routing-v2.bin"));
        destinationCatalogFile = new AtomicFile(
                new File(context.getFilesDir(), "secure-destination-catalog-v1.bin"));
    }

    public synchronized void saveConfig(AppConfig config) {
        Optional<RoutingSnapshot> snapshot = readRoutingSnapshot();
        boolean matchesSnapshot = snapshot.isPresent()
                && snapshot.get().config.hasSameConnection(config)
                && snapshot.get().target.username().equals(config.botUsername());
        if (!matchesSnapshot) routingSnapshotFile.delete();

        put("api_id", Integer.toString(config.apiId()));
        put("api_hash", config.apiHash());
        put("phone", config.phoneNumber());
        put("bot_username", config.botUsername());
        Optional<TargetChat> legacyTarget = loadLegacyTarget();
        if (legacyTarget.isPresent()
                && !legacyTarget.get().username().equals(config.botUsername())) {
            clearLegacyTarget();
        }
    }

    public synchronized Optional<AppConfig> loadConfig() {
        Optional<RoutingSnapshot> snapshot = readRoutingSnapshot();
        return snapshot.isPresent() ? Optional.of(snapshot.get().config) : loadLegacyConfig();
    }

    /**
     * Replaces routing as one encrypted AtomicFile snapshot. A failed write restores the previous
     * file and never publishes a partial candidate through SharedPreferences.
     */
    public synchronized void saveConfigAndTarget(AppConfig config, TargetChat target) {
        if (!config.botUsername().equals(target.username())) {
            throw new IllegalArgumentException("Target does not match configuration");
        }
        byte[] encoded = encodeRoutingSnapshot(config, target);
        FileOutputStream output = null;
        try {
            output = routingSnapshotFile.startWrite();
            output.write(encoded);
            routingSnapshotFile.finishWrite(output);
            output = null;
            Optional<RoutingSnapshot> persisted = readRoutingSnapshot();
            if (persisted.isEmpty() || !persisted.get().matches(config, target)) {
                throw new IllegalStateException("Routing snapshot read-back mismatch");
            }
        } catch (Exception e) {
            if (output != null) routingSnapshotFile.failWrite(output);
            throw new IllegalStateException("Unable to persist target replacement", e);
        }
    }

    public synchronized Optional<TargetChat> loadTarget() {
        Optional<RoutingSnapshot> snapshot = readRoutingSnapshot();
        return snapshot.isPresent() ? Optional.of(snapshot.get().target) : loadLegacyTarget();
    }

    DestinationCatalogPersistence.BlobStore destinationCatalogBlobStore() {
        return new DestinationCatalogPersistence.BlobStore() {
            @Override public Optional<byte[]> read() {
                return readDestinationCatalogBlob();
            }

            @Override public void writeAtomically(byte[] next) {
                writeDestinationCatalogBlob(next);
            }
        };
    }

    DestinationStore.LegacySnapshotSource destinationLegacySnapshotSource() {
        return new DestinationStore.LegacySnapshotSource() {
            @Override public DestinationStore.LegacyInput read() {
                return readLegacyMigrationInput();
            }

            @Override public boolean schemaVersionCommitted() {
                return destinationSchemaVersionCommitted();
            }

            @Override public void markSchemaVersionCommitted() {
                markDestinationSchemaVersionCommitted();
            }
        };
    }

    private synchronized DestinationStore.LegacyInput readLegacyMigrationInput() {
        try {
            RoutingSnapshot routing = readRoutingSnapshotStrict();
            return DestinationStore.LegacyInput.available(new LegacyConfigSnapshot(
                    Integer.toString(routing.config.apiId()),
                    routing.config.apiHash(),
                    routing.config.phoneNumber(),
                    routing.config.botUsername(),
                    Long.toString(routing.target.chatId()),
                    routing.target.title(),
                    routing.target.username(),
                    null));
        } catch (FileNotFoundException missingRouting) {
            return readLegacyPreferencesForMigration();
        } catch (Exception corruptRouting) {
            return DestinationStore.LegacyInput.corrupt();
        }
    }

    private DestinationStore.LegacyInput readLegacyPreferencesForMigration() {
        LegacyValue apiId = readPreserving("api_id");
        LegacyValue apiHash = readPreserving("api_hash");
        LegacyValue phone = readPreserving("phone");
        LegacyValue bot = readPreserving("bot_username");
        LegacyValue targetId = readPreserving("target_chat_id");
        LegacyValue targetTitle = readPreserving("target_title");
        LegacyValue targetUsername = readPreserving("target_username");
        LegacyValue[] values = {
                apiId, apiHash, phone, bot, targetId, targetTitle, targetUsername};
        for (LegacyValue value : values) {
            if (value.status == LegacyValueStatus.CORRUPT) {
                return DestinationStore.LegacyInput.corrupt();
            }
        }
        boolean allMissing = true;
        for (LegacyValue value : values) {
            allMissing &= value.status == LegacyValueStatus.MISSING;
        }
        if (allMissing) return DestinationStore.LegacyInput.missing();
        return DestinationStore.LegacyInput.available(new LegacyConfigSnapshot(
                apiId.value, apiHash.value, phone.value, bot.value,
                targetId.value, targetTitle.value, targetUsername.value, null));
    }

    private synchronized boolean destinationSchemaVersionCommitted() {
        LegacyValue marker = readPreserving("destination_schema_version");
        return marker.status == LegacyValueStatus.AVAILABLE
                && Integer.toString(DESTINATION_SCHEMA_VERSION).equals(marker.value);
    }

    private synchronized void markDestinationSchemaVersionCommitted() {
        put("destination_schema_version",
                Integer.toString(DESTINATION_SCHEMA_VERSION));
        if (!destinationSchemaVersionCommitted()) {
            throw new IllegalStateException("Destination schema marker read-back failed");
        }
    }

    @FunctionalInterface
    interface CatalogBlobReader {
        byte[] read() throws Exception;
    }

    static Optional<byte[]> readOptionalCatalogBlob(CatalogBlobReader reader) {
        try {
            return Optional.of(reader.read());
        } catch (FileNotFoundException missing) {
            return Optional.empty();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Destination catalog exists but cannot be decrypted", e);
        }
    }

    private synchronized Optional<byte[]> readDestinationCatalogBlob() {
        return readOptionalCatalogBlob(() -> readEncryptedBlob(destinationCatalogFile));
    }

    private synchronized void writeDestinationCatalogBlob(byte[] plaintext) {
        byte[] encoded;
        try {
            encoded = encryptBlob(plaintext);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encrypt destination catalog", e);
        }
        FileOutputStream output = null;
        try {
            output = destinationCatalogFile.startWrite();
            output.write(encoded);
            destinationCatalogFile.finishWrite(output);
            output = null;
        } catch (Exception e) {
            if (output != null) destinationCatalogFile.failWrite(output);
            throw new IllegalStateException("Unable to persist destination catalog", e);
        }
    }

    public synchronized byte[] getOrCreateDatabaseKey() {
        String encoded = get("database_key");
        if (encoded != null) return Base64.decode(encoded, Base64.NO_WRAP);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        put("database_key", Base64.encodeToString(key, Base64.NO_WRAP));
        return key;
    }

    public synchronized void clearTarget() {
        routingSnapshotFile.delete();
        clearLegacyTarget();
    }

    private Optional<AppConfig> loadLegacyConfig() {
        try {
            String apiId = get("api_id");
            String apiHash = get("api_hash");
            String phone = get("phone");
            String bot = get("bot_username");
            AppConfig.ValidationResult result = bot == null || bot.isBlank()
                    ? AppConfig.validateConnection(apiId, apiHash, phone)
                    : AppConfig.validate(apiId, apiHash, phone, bot);
            return result.isValid() ? Optional.of(result.config()) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<TargetChat> loadLegacyTarget() {
        try {
            String id = get("target_chat_id");
            String title = get("target_title");
            String username = get("target_username");
            if (id == null || title == null || username == null) return Optional.empty();
            return Optional.of(new TargetChat(Long.parseLong(id), title, username));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private void clearLegacyTarget() {
        preferences.edit().remove("target_chat_id").remove("target_title")
                .remove("target_username").apply();
    }

    private byte[] encodeRoutingSnapshot(AppConfig config, TargetChat target) {
        try {
            ByteArrayOutputStream plaintextBytes = new ByteArrayOutputStream();
            try (DataOutputStream plaintext = new DataOutputStream(plaintextBytes)) {
                plaintext.writeInt(ROUTING_SNAPSHOT_VERSION);
                plaintext.writeInt(config.apiId());
                plaintext.writeUTF(config.apiHash());
                plaintext.writeUTF(config.phoneNumber());
                plaintext.writeUTF(config.botUsername());
                plaintext.writeLong(target.chatId());
                plaintext.writeUTF(target.title());
                plaintext.writeUTF(target.username());
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(plaintextBytes.toByteArray());
            ByteArrayOutputStream encodedBytes = new ByteArrayOutputStream();
            try (DataOutputStream encoded = new DataOutputStream(encodedBytes)) {
                encoded.writeInt(cipher.getIV().length);
                encoded.write(cipher.getIV());
                encoded.writeInt(encrypted.length);
                encoded.write(encrypted);
            }
            return encodedBytes.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encrypt routing snapshot", e);
        }
    }

    private Optional<RoutingSnapshot> readRoutingSnapshot() {
        try {
            return Optional.of(readRoutingSnapshotStrict());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private RoutingSnapshot readRoutingSnapshotStrict() throws Exception {
        try (FileInputStream input = routingSnapshotFile.openRead();
             DataInputStream encoded = new DataInputStream(input)) {
            int ivLength = encoded.readInt();
            if (ivLength < 12 || ivLength > 32) {
                throw new IllegalArgumentException("Invalid routing snapshot IV");
            }
            byte[] iv = new byte[ivLength];
            encoded.readFully(iv);
            int encryptedLength = encoded.readInt();
            if (encryptedLength <= 0 || encryptedLength > MAX_ROUTING_BLOB_BYTES) {
                throw new IllegalArgumentException("Invalid routing snapshot length");
            }
            byte[] encrypted = new byte[encryptedLength];
            encoded.readFully(encrypted);
            if (encoded.read() != -1) {
                throw new IllegalArgumentException("Trailing routing snapshot bytes");
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            byte[] plaintextBytes = cipher.doFinal(encrypted);
            try (DataInputStream plaintext = new DataInputStream(
                    new ByteArrayInputStream(plaintextBytes))) {
                if (plaintext.readInt() != ROUTING_SNAPSHOT_VERSION) {
                    throw new IllegalArgumentException("Unsupported routing snapshot version");
                }
                String apiId = Integer.toString(plaintext.readInt());
                String apiHash = plaintext.readUTF();
                String phone = plaintext.readUTF();
                String bot = plaintext.readUTF();
                long chatId = plaintext.readLong();
                String title = plaintext.readUTF();
                String targetUsername = plaintext.readUTF();
                if (plaintext.read() != -1) {
                    throw new IllegalArgumentException("Trailing routing plaintext bytes");
                }
                AppConfig.ValidationResult validated = AppConfig.validate(
                        apiId, apiHash, phone, bot);
                if (!validated.isValid()
                        || !validated.config().botUsername().equals(targetUsername)) {
                    throw new IllegalArgumentException("Invalid routing snapshot identity");
                }
                return new RoutingSnapshot(validated.config(),
                        new TargetChat(chatId, title, targetUsername));
            }
        }
    }

    private byte[] encryptBlob(byte[] plaintext) throws Exception {
        if (plaintext == null || plaintext.length > MAX_ROUTING_BLOB_BYTES) {
            throw new IllegalArgumentException("Invalid destination catalog blob size");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(plaintext);
        ByteArrayOutputStream encodedBytes = new ByteArrayOutputStream();
        try (DataOutputStream encoded = new DataOutputStream(encodedBytes)) {
            encoded.writeInt(cipher.getIV().length);
            encoded.write(cipher.getIV());
            encoded.writeInt(encrypted.length);
            encoded.write(encrypted);
        }
        return encodedBytes.toByteArray();
    }

    private byte[] readEncryptedBlob(AtomicFile file) throws Exception {
        try (FileInputStream input = file.openRead();
             DataInputStream encoded = new DataInputStream(input)) {
            int ivLength = encoded.readInt();
            if (ivLength < 12 || ivLength > 32) {
                throw new IllegalArgumentException("Invalid encrypted blob IV");
            }
            byte[] iv = new byte[ivLength];
            encoded.readFully(iv);
            int encryptedLength = encoded.readInt();
            if (encryptedLength <= 0 || encryptedLength > MAX_ROUTING_BLOB_BYTES + 64) {
                throw new IllegalArgumentException("Invalid encrypted blob length");
            }
            byte[] encrypted = new byte[encryptedLength];
            encoded.readFully(encrypted);
            if (encoded.read() != -1) {
                throw new IllegalArgumentException("Trailing encrypted blob bytes");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                    new GCMParameterSpec(128, iv));
            byte[] plaintext = cipher.doFinal(encrypted);
            if (plaintext.length > MAX_ROUTING_BLOB_BYTES) {
                throw new IllegalArgumentException("Decrypted blob exceeds maximum size");
            }
            return Arrays.copyOf(plaintext, plaintext.length);
        }
    }

    private void put(String name, String value) {
        preferences.edit().putString(name, encrypt(value)).apply();
    }

    private String encrypt(String value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encrypt runtime settings", e);
        }
    }

    private String get(String name) {
        LegacyValue value = readPreserving(name);
        return value.status == LegacyValueStatus.AVAILABLE ? value.value : null;
    }

    private LegacyValue readPreserving(String name) {
        LegacyValue raw = readRawPreferencePreserving(
                () -> preferences.getString(name, null));
        if (raw.status != LegacyValueStatus.AVAILABLE) return raw;
        String blob = raw.value;
        try {
            String[] pieces = blob.split(":", 2);
            if (pieces.length != 2) return LegacyValue.corrupt();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                    new GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)));
            return LegacyValue.available(new String(
                    cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)),
                    StandardCharsets.UTF_8));
        } catch (Exception e) {
            return LegacyValue.corrupt();
        }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    @FunctionalInterface
    interface PreferenceStringReader {
        String read();
    }

    static LegacyValue readRawPreferencePreserving(PreferenceStringReader reader) {
        try {
            String blob = reader.read();
            return blob == null ? LegacyValue.missing() : LegacyValue.available(blob);
        } catch (ClassCastException wrongType) {
            return LegacyValue.corrupt();
        }
    }

    enum LegacyValueStatus { MISSING, AVAILABLE, CORRUPT }

    static final class LegacyValue {
        private final LegacyValueStatus status;
        private final String value;

        private LegacyValue(LegacyValueStatus status, String value) {
            this.status = status;
            this.value = value;
        }

        LegacyValueStatus status() { return status; }

        private static LegacyValue missing() {
            return new LegacyValue(LegacyValueStatus.MISSING, null);
        }

        private static LegacyValue available(String value) {
            return new LegacyValue(LegacyValueStatus.AVAILABLE, value);
        }

        private static LegacyValue corrupt() {
            return new LegacyValue(LegacyValueStatus.CORRUPT, null);
        }
    }

    private static final class RoutingSnapshot {
        private final AppConfig config;
        private final TargetChat target;

        private RoutingSnapshot(AppConfig config, TargetChat target) {
            this.config = config;
            this.target = target;
        }

        private boolean matches(AppConfig expectedConfig, TargetChat expectedTarget) {
            return config.hasSameConnection(expectedConfig)
                    && config.botUsername().equals(expectedConfig.botUsername())
                    && target.chatId() == expectedTarget.chatId()
                    && target.title().equals(expectedTarget.title())
                    && target.username().equals(expectedTarget.username());
        }
    }
}

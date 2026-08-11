package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;

import com.sidequestlab.floatingvoice.core.AppConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
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
    private static final int MAX_ROUTING_BLOB_BYTES = 1024 * 1024;

    private final SharedPreferences preferences;
    private final AtomicFile routingSnapshotFile;

    public SecureSettingsStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        routingSnapshotFile = new AtomicFile(
                new File(context.getFilesDir(), "secure-routing-v2.bin"));
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
        try (FileInputStream input = routingSnapshotFile.openRead();
             DataInputStream encoded = new DataInputStream(input)) {
            int ivLength = encoded.readInt();
            if (ivLength < 12 || ivLength > 32) return Optional.empty();
            byte[] iv = new byte[ivLength];
            encoded.readFully(iv);
            int encryptedLength = encoded.readInt();
            if (encryptedLength <= 0 || encryptedLength > MAX_ROUTING_BLOB_BYTES) {
                return Optional.empty();
            }
            byte[] encrypted = new byte[encryptedLength];
            encoded.readFully(encrypted);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            byte[] plaintextBytes = cipher.doFinal(encrypted);
            try (DataInputStream plaintext = new DataInputStream(
                    new ByteArrayInputStream(plaintextBytes))) {
                if (plaintext.readInt() != ROUTING_SNAPSHOT_VERSION) return Optional.empty();
                String apiId = Integer.toString(plaintext.readInt());
                String apiHash = plaintext.readUTF();
                String phone = plaintext.readUTF();
                String bot = plaintext.readUTF();
                long chatId = plaintext.readLong();
                String title = plaintext.readUTF();
                String targetUsername = plaintext.readUTF();
                AppConfig.ValidationResult validated = AppConfig.validate(
                        apiId, apiHash, phone, bot);
                if (!validated.isValid()
                        || !validated.config().botUsername().equals(targetUsername)) {
                    return Optional.empty();
                }
                return Optional.of(new RoutingSnapshot(validated.config(),
                        new TargetChat(chatId, title, targetUsername)));
            }
        } catch (Exception e) {
            return Optional.empty();
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
        String blob = preferences.getString(name, null);
        if (blob == null) return null;
        try {
            String[] pieces = blob.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                    new GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            preferences.edit().remove(name).apply();
            return null;
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

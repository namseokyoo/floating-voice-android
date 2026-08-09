package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.sidequestlab.floatingvoice.core.AppConfig;

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
    private final SharedPreferences preferences;

    public SecureSettingsStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void saveConfig(AppConfig config) {
        put("api_id", Integer.toString(config.apiId()));
        put("api_hash", config.apiHash());
        put("phone", config.phoneNumber());
        put("bot_username", config.botUsername());
        Optional<TargetChat> target = loadTarget();
        if (target.isPresent() && !target.get().username().equals(config.botUsername())) clearTarget();
    }

    public synchronized Optional<AppConfig> loadConfig() {
        try {
            String apiId = get("api_id");
            String apiHash = get("api_hash");
            String phone = get("phone");
            String bot = get("bot_username");
            AppConfig.ValidationResult result = AppConfig.validate(apiId, apiHash, phone, bot);
            return result.isValid() ? Optional.of(result.config()) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public synchronized void saveTarget(TargetChat target) {
        put("target_chat_id", Long.toString(target.chatId()));
        put("target_title", target.title());
        put("target_username", target.username());
    }

    public synchronized Optional<TargetChat> loadTarget() {
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

    public synchronized byte[] getOrCreateDatabaseKey() {
        String encoded = get("database_key");
        if (encoded != null) return Base64.decode(encoded, Base64.NO_WRAP);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        put("database_key", Base64.encodeToString(key, Base64.NO_WRAP));
        return key;
    }

    public synchronized void clearTarget() {
        preferences.edit().remove("target_chat_id").remove("target_title")
                .remove("target_username").apply();
    }

    private void put(String name, String value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String blob = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            preferences.edit().putString(name, blob).apply();
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
}

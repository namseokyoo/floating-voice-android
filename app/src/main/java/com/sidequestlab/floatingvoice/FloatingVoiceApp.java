package com.sidequestlab.floatingvoice;

import android.app.Application;
import android.content.res.Configuration;

public final class FloatingVoiceApp extends Application {
    private SecureSettingsStore settings;
    private TelegramRepository telegram;
    private boolean automaticStartAttempted;

    @Override public void onCreate() {
        super.onCreate();
        settings = new SecureSettingsStore(this);
        telegram = new TelegramRepository(this, settings, new PendingRecordingStore(this));
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (telegram != null) telegram.refreshLocalizedState();
    }

    public synchronized void startTelegramIfConfigured() {
        if (automaticStartAttempted) return;
        automaticStartAttempted = true;
        settings.loadConfig().ifPresent(telegram::start);
    }

    public SecureSettingsStore settings() { return settings; }
    public TelegramRepository telegram() { return telegram; }
}

package com.sidequestlab.messvoice;

import android.app.Application;

public final class MessVoiceApp extends Application {
    private SecureSettingsStore settings;
    private TelegramRepository telegram;

    @Override public void onCreate() {
        super.onCreate();
        settings = new SecureSettingsStore(this);
        telegram = new TelegramRepository(this, settings, new PendingRecordingStore(this));
        settings.loadConfig().ifPresent(telegram::start);
    }

    public SecureSettingsStore settings() { return settings; }
    public TelegramRepository telegram() { return telegram; }
}

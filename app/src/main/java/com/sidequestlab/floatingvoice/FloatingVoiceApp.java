package com.sidequestlab.floatingvoice;

import android.app.Application;
import android.content.res.Configuration;

import com.sidequestlab.floatingvoice.core.DestinationCatalogPersistence;

import java.io.File;

public final class FloatingVoiceApp extends Application {
    private SecureSettingsStore settings;
    private DestinationStore destinations;
    private DestinationCatalogPersistence.LoadResult destinationLoadResult;
    private TelegramRepository telegram;
    private AudioCaptureCoordinator audioCaptureCoordinator;
    private SpeechTelegramHandoffRegistry speechTelegramHandoffs;
    private RetainedAudioShareStore retainedAudioShares;
    private boolean automaticStartAttempted;

    @Override public void onCreate() {
        super.onCreate();
        settings = new SecureSettingsStore(this);
        destinations = new DestinationStore(settings);
        destinationLoadResult = destinations.load();
        audioCaptureCoordinator = new AudioCaptureCoordinator();
        speechTelegramHandoffs = new SpeechTelegramHandoffRegistry();
        retainedAudioShares = new RetainedAudioShareStore(
                new File(getFilesDir(), "audio_share"));
        telegram = new TelegramRepository(this, settings, destinations,
                new PendingRecordingStore(this), new PendingTextSendStore(this),
                new PendingDispatchStore(this));
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
    public DestinationStore destinations() { return destinations; }
    public DestinationCatalogPersistence.LoadResult destinationLoadResult() {
        return destinationLoadResult;
    }
    public TelegramRepository telegram() { return telegram; }
    SpeechTelegramHandoffRegistry speechTelegramHandoffs() {
        return speechTelegramHandoffs;
    }

    RetainedAudioShareStore retainedAudioShares() { return retainedAudioShares; }

    public synchronized AudioCaptureCoordinator audioCaptureCoordinator() {
        return audioCaptureCoordinator;
    }
}

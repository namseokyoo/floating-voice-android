package com.sidequestlab.floatingvoice.core;

/** A single explicit output selected for one captured payload. */
public enum OutputRoute {
    TELEGRAM_VOICE(ContentKind.AUDIO, true),
    TELEGRAM_TEXT(ContentKind.TEXT, true),
    LOCAL_AUDIO_ARCHIVE(ContentKind.AUDIO, false),
    SYSTEM_AUDIO_SHARE(ContentKind.AUDIO, false),
    SYSTEM_TEXT_SHARE(ContentKind.TEXT, false);

    public enum ContentKind { AUDIO, TEXT }

    private final ContentKind contentKind;
    private final boolean telegramDestinationRequired;

    OutputRoute(ContentKind contentKind, boolean telegramDestinationRequired) {
        this.contentKind = contentKind;
        this.telegramDestinationRequired = telegramDestinationRequired;
    }

    public ContentKind contentKind() { return contentKind; }
    public boolean requiresTelegramDestination() { return telegramDestinationRequired; }
}

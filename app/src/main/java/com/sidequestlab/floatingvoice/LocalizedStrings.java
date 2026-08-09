package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import java.util.Locale;

/** Resolves app-localized resources for components whose base context is not an AppCompatActivity. */
final class LocalizedStrings {
    private LocalizedStrings() { }

    static Context context(Context base) {
        LocaleListCompat appLocales = AppCompatDelegate.getApplicationLocales();
        if (appLocales.isEmpty()) return base;

        Locale[] locales = new Locale[appLocales.size()];
        for (int i = 0; i < appLocales.size(); i++) {
            Locale locale = appLocales.get(i);
            locales[i] = locale == null ? Locale.getDefault() : locale;
        }
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(locales));
        return base.createConfigurationContext(configuration);
    }

    static String get(Context base, int stringId, Object... arguments) {
        Context localized = context(base);
        return arguments.length == 0
                ? localized.getString(stringId)
                : localized.getString(stringId, arguments);
    }

    static String effectiveLanguageCode(Context base) {
        Locale locale = context(base).getResources().getConfiguration().getLocales().get(0);
        return locale.toLanguageTag();
    }
}

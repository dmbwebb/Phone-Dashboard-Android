package com.audacious_software.phone_dashboard.survey;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * Thin wrapper around AndroidX per-app locales so the whole app (including plain
 * {@code Activity} screens like the survey) can switch between English and
 * Spanish from an in-app toggle. AppCompat stores/restores the choice (via the
 * AppLocalesMetadataHolderService + autoStoreLocales manifest metadata) and, on
 * API 33+, delegates to the framework LocaleManager.
 */
public final class AppLocale {
    public static final String ENGLISH = "en";
    public static final String SPANISH = "es";

    private AppLocale() {
    }

    /** Persist and apply the given base language tag ("en" or "es"). */
    public static void setLanguage(String tag) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag));
    }

    /** The base language currently in effect ("en"/"es"), falling back to the system language. */
    public static String effectiveTag(Context context) {
        LocaleListCompat applied = AppCompatDelegate.getApplicationLocales();

        if (!applied.isEmpty() && applied.get(0) != null) {
            return AppLocale.SPANISH.equals(applied.get(0).getLanguage()) ? AppLocale.SPANISH : AppLocale.ENGLISH;
        }

        String system = Resources.getSystem().getConfiguration().getLocales().get(0).getLanguage();
        return AppLocale.SPANISH.equals(system) ? AppLocale.SPANISH : AppLocale.ENGLISH;
    }

    /** Wrap a base context with the active app locale (for plain Activities on API < 33). */
    public static Context wrap(Context base) {
        LocaleListCompat applied = AppCompatDelegate.getApplicationLocales();

        if (applied.isEmpty() || applied.get(0) == null) {
            return base;
        }

        Locale locale = applied.get(0);
        Locale.setDefault(locale);

        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        return base.createConfigurationContext(config);
    }
}
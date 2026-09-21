package com.audacious_software.phone_dashboard.survey;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.audacious_software.phone_dashboard.AppApplication;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Persisted server policy for child check-ins. Missing fields preserve the last-known policy. */
public final class SurveyPolicy {
    private static final String SERVER_ROLE = "com.audacious_software.phone_dashboard.survey.SERVER_ROLE";
    private static final String ENABLED = "com.audacious_software.phone_dashboard.survey.ENABLED";
    private static final String UNTIL = "com.audacious_software.phone_dashboard.survey.UNTIL";
    private static final String HAS_UNTIL = "com.audacious_software.phone_dashboard.survey.HAS_UNTIL";

    private SurveyPolicy() { }

    public static void apply(Context context, JSONObject config) throws JSONException {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
        if (config.has("role") && !config.isNull("role")) {
            String role = config.getString("role");
            if (AppApplication.ROLE_CHILD.equals(role) || AppApplication.ROLE_PARENT.equals(role)) {
                editor.putString(SERVER_ROLE, role);
            }
        }
        if (config.has("survey_enabled") && !config.isNull("survey_enabled")) {
            editor.putBoolean(ENABLED, config.getBoolean("survey_enabled"));
        }
        if (config.has("survey_until")) {
            if (config.isNull("survey_until")) {
                editor.putBoolean(HAS_UNTIL, false).remove(UNTIL);
            } else {
                editor.putLong(UNTIL, parseInstant(config.getString("survey_until")));
                editor.putBoolean(HAS_UNTIL, true);
            }
        }
        editor.apply();
    }

    public static String effectiveRole(Context context, String localRole) {
        String server = PreferenceManager.getDefaultSharedPreferences(context).getString(SERVER_ROLE, null);
        return server != null ? server : localRole;
    }

    public static boolean allows(Context context, long nowMillis) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean(ENABLED, true)) {
            return false;
        }
        return !prefs.getBoolean(HAS_UNTIL, false) || nowMillis < prefs.getLong(UNTIL, 0);
    }

    static boolean allowsValues(boolean enabled, Long untilMillis, long nowMillis) {
        return enabled && (untilMillis == null || nowMillis < untilMillis);
    }

    static long parseInstant(String value) throws JSONException {
        Pattern iso = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,6}))?(Z|[+-]\\d{2}:\\d{2})$");
        Matcher match = iso.matcher(value);
        if (!match.matches()) throw new JSONException("Invalid survey_until instant: " + value);

        String fraction = match.group(2) == null ? "000" : (match.group(2) + "000").substring(0, 3);
        String normalized = match.group(1) + "." + fraction + match.group(3);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date parsed = format.parse(normalized, position);
        if (parsed == null || position.getIndex() != normalized.length()) {
            throw new JSONException("Invalid survey_until instant: " + value);
        }
        return parsed.getTime();
    }
}

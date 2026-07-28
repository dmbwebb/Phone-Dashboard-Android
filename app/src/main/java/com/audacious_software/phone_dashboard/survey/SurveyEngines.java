package com.audacious_software.phone_dashboard.survey;

import android.content.Context;

/**
 * Factory for the active {@link SurveyEngine}. Today it always returns the
 * scripted engine; this is the single place to swap in a server-driven or
 * LLM-backed engine later.
 */
public final class SurveyEngines {
    private SurveyEngines() {
    }

    @SuppressWarnings("unused")
    public static SurveyEngine forContext(Context context) {
        return new ScriptedSurveyEngine();
    }
}
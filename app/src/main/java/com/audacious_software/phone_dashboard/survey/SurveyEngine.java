package com.audacious_software.phone_dashboard.survey;

import android.content.Context;

/**
 * The seam that lets the nightly survey evolve (more questions, server-driven
 * content, or an LLM-backed exchange) without touching the alarm, notification,
 * or Passive Data Kit storage plumbing.
 *
 * v1 ships {@link ScriptedSurveyEngine}. Emitted data points carry the engine
 * name so downstream analysis can tell scripted and future variants apart.
 */
public interface SurveyEngine {
    /** Stored on every data point as the {@code engine} field. */
    String engineName();

    /**
     * The survey to present for one prompt (questions shown one at a time).
     * {@code promptShownAt} identifies the prompt, so anything varied per prompt
     * (such as choice order) stays the same if the screen is rebuilt.
     */
    Survey currentSurvey(Context context, long promptShownAt);
}

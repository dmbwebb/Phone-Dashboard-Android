package com.audacious_software.phone_dashboard.survey;

import android.content.Context;
import android.content.res.Resources;

import com.audacious_software.phone_dashboard.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Scheduled survey: a fixed four-question experience-sampling questionnaire —
 * phone activity, mood, sleep, and in-person social time. Copy lives in string
 * resources so translation stays in the normal Android pipeline; the survey id
 * is versioned so a wording change is distinguishable in the data.
 *
 * v2 replaced v1's multi-select emotion list ({@code current_affect}) with a
 * one-tap five-face mood scale ({@code mood_valence}, 1 = very bad … 5 = very
 * good), shown best-first or worst-first at random per prompt.
 */
public class ScriptedSurveyEngine implements SurveyEngine {
    public static final String SURVEY_ID = "daily_ema_v2";

    // Stable machine keys used in the data (do not translate / rename lightly).
    public static final String Q_PHONE_ACTIVITY = "phone_activity";
    public static final String Q_MOOD_VALENCE = "mood_valence";
    public static final String Q_SLEEP_HOURS = "sleep_hours";
    public static final String Q_SOCIAL_TIME = "social_time_yesterday";

    // Recorded with each mood answer: worst-first or best-first on screen.
    public static final String ORDER_ASCENDING = "ascending";
    public static final String ORDER_DESCENDING = "descending";

    @Override
    public String engineName() {
        return "scripted";
    }

    /**
     * Whether this prompt lists the mood faces best-first. A mixed hash of the
     * prompt time, so the order is a fair coin across prompts but stays fixed
     * for one prompt.
     */
    static boolean showsMoodDescending(long promptShownAt) {
        long z = promptShownAt + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z & 1L) == 1L;
    }

    /** The mood question for one prompt; {@code canonical} runs worst to best. */
    static SurveyQuestion moodQuestion(String text, String[] canonical, boolean descending) {
        String[] labels = new String[canonical.length];
        int[] values = new int[canonical.length];
        for (int i = 0; i < canonical.length; i++) {
            int source = descending ? canonical.length - 1 - i : i;
            labels[i] = canonical[source];
            values[i] = source + 1;
        }
        return new SurveyQuestion(
                Q_MOOD_VALENCE,
                text,
                SurveyQuestion.Type.SINGLE,
                labels,
                -1,
                -1,
                values,
                descending ? ORDER_DESCENDING : ORDER_ASCENDING
        );
    }

    @Override
    public Survey currentSurvey(Context context, long promptShownAt) {
        Resources res = context.getResources();

        List<SurveyQuestion> questions = new ArrayList<>();

        String[] activity = res.getStringArray(R.array.survey_opts_phone_activity);
        questions.add(new SurveyQuestion(
                Q_PHONE_ACTIVITY,
                context.getString(R.string.survey_q_phone_activity),
                SurveyQuestion.Type.SINGLE,
                activity,
                -1,
                activity.length - 1  // last option ("Other") takes a written-in answer
        ));

        questions.add(moodQuestion(
                context.getString(R.string.survey_q_mood_valence),
                res.getStringArray(R.array.survey_opts_mood_valence),
                showsMoodDescending(promptShownAt)
        ));

        questions.add(new SurveyQuestion(
                Q_SLEEP_HOURS,
                context.getString(R.string.survey_q_sleep_hours),
                SurveyQuestion.Type.SINGLE,
                res.getStringArray(R.array.survey_opts_sleep_hours),
                -1,
                -1
        ));

        questions.add(new SurveyQuestion(
                Q_SOCIAL_TIME,
                context.getString(R.string.survey_q_social_time),
                SurveyQuestion.Type.SINGLE,
                res.getStringArray(R.array.survey_opts_social_time),
                -1,
                -1
        ));

        return new Survey(SURVEY_ID, questions);
    }
}

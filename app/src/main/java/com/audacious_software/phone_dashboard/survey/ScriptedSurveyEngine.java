package com.audacious_software.phone_dashboard.survey;

import android.content.Context;
import android.content.res.Resources;

import com.audacious_software.phone_dashboard.R;

import java.util.ArrayList;
import java.util.List;

/**
 * v1 nightly survey: a fixed four-question experience-sampling questionnaire —
 * phone activity, current affect (multi-select), sleep, and in-person social
 * time. Copy lives in string resources so translation stays in the normal
 * Android pipeline; the survey id is versioned so a wording change is
 * distinguishable in the data.
 */
public class ScriptedSurveyEngine implements SurveyEngine {
    public static final String SURVEY_ID = "daily_ema_v1";

    // Stable machine keys used in the data (do not translate / rename lightly).
    public static final String Q_PHONE_ACTIVITY = "phone_activity";
    public static final String Q_CURRENT_AFFECT = "current_affect";
    public static final String Q_SLEEP_HOURS = "sleep_hours";
    public static final String Q_SOCIAL_TIME = "social_time_yesterday";

    @Override
    public String engineName() {
        return "scripted";
    }

    @Override
    public Survey currentSurvey(Context context) {
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

        String[] affect = res.getStringArray(R.array.survey_opts_current_affect);
        questions.add(new SurveyQuestion(
                Q_CURRENT_AFFECT,
                context.getString(R.string.survey_q_current_affect),
                SurveyQuestion.Type.MULTI,
                affect,
                affect.length - 1,  // last option ("None of these") is exclusive
                -1
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

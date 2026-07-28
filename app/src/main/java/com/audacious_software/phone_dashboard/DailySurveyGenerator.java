package com.audacious_software.phone_dashboard;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;

import com.audacious_software.passive_data_kit.PassiveDataKit;
import com.audacious_software.passive_data_kit.generators.Generator;
import com.audacious_software.passive_data_kit.generators.Generators;
import com.audacious_software.phone_dashboard.survey.SurveyQuestion;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Passive Data Kit generator for the nightly in-app survey.
 *
 * Emits ONE {@code daily-survey-response} data point per answered question, so
 * item-level (and partial-completion) analysis is possible. Multi-select answers
 * are stored as JSON arrays in {@code response_values} / {@code response_labels}
 * (single-choice answers are one-element arrays). When the participant picks an
 * "other" choice, the label stays the option's own text and what they typed goes
 * in {@code other_text}. PDK batches and transmits the points to the Django
 * server like every other stream.
 *
 * Because {@link SurveyActivity} lets participants go back and change an earlier
 * answer, a question can produce more than one point in the rare case where the
 * first answer was already flushed (the activity was backgrounded mid-survey).
 * Downstream, take the latest {@code responded_at} per question.
 */
public class DailySurveyGenerator extends Generator {
    private static final String GENERATOR_IDENTIFIER = "daily-survey-response";

    private static final String DATABASE_PATH = "daily-survey-response.sqlite";
    private static final int DATABASE_VERSION = 3;

    private static final String TABLE_RESPONSES = "responses";

    public static final String HISTORY_FETCHED = "fetched";
    public static final String HISTORY_TRANSMITTED = "transmitted";
    public static final String HISTORY_OBSERVED = "observed";
    public static final String SURVEY_ID = "survey_id";
    public static final String QUESTION_KEY = "question_key";
    public static final String QUESTION_TEXT = "question_text";
    public static final String QUESTION_TYPE = "question_type";
    public static final String RESPONSE_VALUES = "response_values";
    public static final String RESPONSE_LABELS = "response_labels";
    public static final String OTHER_TEXT = "other_text";
    public static final String DISMISSED = "dismissed";
    public static final String PROMPT_SHOWN_AT = "prompt_shown_at";
    public static final String RESPONDED_AT = "responded_at";
    public static final String ENGINE = "engine";

    private SQLiteDatabase mDatabase = null;

    private static DailySurveyGenerator sInstance = null;

    public static synchronized DailySurveyGenerator getInstance(Context context) {
        if (DailySurveyGenerator.sInstance == null) {
            DailySurveyGenerator.sInstance = new DailySurveyGenerator(context.getApplicationContext());
        }
        return DailySurveyGenerator.sInstance;
    }

    private DailySurveyGenerator(Context context) {
        super(context);
        this.mContext = context;

        File path = PassiveDataKit.getGeneratorsStorage(this.mContext);
        path = new File(path, DailySurveyGenerator.DATABASE_PATH);
        this.mDatabase = SQLiteDatabase.openOrCreateDatabase(path, null);

        int version = this.getDatabaseVersion(this.mDatabase);
        switch (version) {
            case 0:
                this.mDatabase.execSQL(this.mContext.getString(R.string.generator_daily_survey_create_responses_table));
                break;
            case 1:
            case 2:
                // Pre-release schema changes (single-question -> multi-question survey;
                // then the other_text column). No shipped data exists, so drop and
                // recreate with the current schema.
                this.mDatabase.execSQL("DROP TABLE IF EXISTS " + DailySurveyGenerator.TABLE_RESPONSES);
                this.mDatabase.execSQL(this.mContext.getString(R.string.generator_daily_survey_create_responses_table));
                break;
        }

        if (version != DailySurveyGenerator.DATABASE_VERSION) {
            this.setDatabaseVersion(this.mDatabase, DailySurveyGenerator.DATABASE_VERSION);
        }
    }

    @SuppressWarnings("unused")
    public static void start(final Context context) {
        DailySurveyGenerator.getInstance(context).startGenerator();
    }

    private void startGenerator() {
        Generators.getInstance(this.mContext).registerCustomViewClass(DailySurveyGenerator.GENERATOR_IDENTIFIER, DailySurveyGenerator.class);
    }

    @SuppressWarnings({"SameReturnValue", "unused"})
    public static boolean isEnabled(Context context) {
        return true;
    }

    @SuppressWarnings({"SameReturnValue", "unused"})
    public static boolean isRunning(Context context) {
        return true;
    }

    public static long latestPointGenerated(Context context) {
        long timestamp = 0;

        DailySurveyGenerator me = DailySurveyGenerator.getInstance(context);
        Cursor c = me.mDatabase.query(DailySurveyGenerator.TABLE_RESPONSES, null, null, null, null, null, DailySurveyGenerator.HISTORY_OBSERVED + " DESC");
        if (c.moveToNext()) {
            timestamp = c.getLong(c.getColumnIndex(DailySurveyGenerator.HISTORY_OBSERVED));
        }
        c.close();

        return timestamp;
    }

    /**
     * Record one answered question and emit it as a data point.
     *
     * @param surveyId      the versioned survey id (e.g. "daily_ema_v1")
     * @param question      the question that was answered
     * @param values        selected 1-based choice values (1..N); one entry for
     *                      SINGLE, one-or-more for MULTI, empty if nothing chosen
     * @param labels        the human-readable labels matching {@code values}
     * @param otherText     what the participant typed into the write-in box when an
     *                      "other" choice was selected; empty otherwise
     * @param promptShownAt when the survey notification/screen was surfaced (ms epoch)
     * @param respondedAt   when this question was answered (ms epoch)
     * @param engineName    the {@code SurveyEngine} that produced the survey
     */
    public void saveResponse(String surveyId, SurveyQuestion question, int[] values, String[] labels,
                             String otherText, long promptShownAt, long respondedAt, String engineName) {
        String valuesJson = intArrayToJson(values);
        String labelsJson = stringArrayToJson(labels);
        String other = otherText == null ? "" : otherText;
        String type = question.type == SurveyQuestion.Type.MULTI ? "multi" : "single";

        long observed = System.currentTimeMillis();

        ContentValues value = new ContentValues();
        value.put(DailySurveyGenerator.HISTORY_OBSERVED, observed);
        value.put(DailySurveyGenerator.SURVEY_ID, surveyId);
        value.put(DailySurveyGenerator.QUESTION_KEY, question.questionKey);
        value.put(DailySurveyGenerator.QUESTION_TEXT, question.questionText);
        value.put(DailySurveyGenerator.QUESTION_TYPE, type);
        value.put(DailySurveyGenerator.RESPONSE_VALUES, valuesJson);
        value.put(DailySurveyGenerator.RESPONSE_LABELS, labelsJson);
        value.put(DailySurveyGenerator.OTHER_TEXT, other);
        value.put(DailySurveyGenerator.DISMISSED, 0);
        value.put(DailySurveyGenerator.PROMPT_SHOWN_AT, promptShownAt);
        value.put(DailySurveyGenerator.RESPONDED_AT, respondedAt);
        value.put(DailySurveyGenerator.ENGINE, engineName);

        this.mDatabase.insert(DailySurveyGenerator.TABLE_RESPONSES, null, value);

        Bundle update = new Bundle();
        update.putLong(DailySurveyGenerator.HISTORY_OBSERVED, observed);
        update.putString(DailySurveyGenerator.SURVEY_ID, surveyId);
        update.putString(DailySurveyGenerator.QUESTION_KEY, question.questionKey);
        update.putString(DailySurveyGenerator.QUESTION_TEXT, question.questionText);
        update.putString(DailySurveyGenerator.QUESTION_TYPE, type);
        update.putString(DailySurveyGenerator.RESPONSE_VALUES, valuesJson);
        update.putString(DailySurveyGenerator.RESPONSE_LABELS, labelsJson);
        update.putString(DailySurveyGenerator.OTHER_TEXT, other);
        update.putBoolean(DailySurveyGenerator.DISMISSED, false);
        update.putLong(DailySurveyGenerator.PROMPT_SHOWN_AT, promptShownAt);
        update.putLong(DailySurveyGenerator.RESPONDED_AT, respondedAt);
        update.putString(DailySurveyGenerator.ENGINE, engineName);

        Generators.getInstance(this.mContext).notifyGeneratorUpdated(DailySurveyGenerator.GENERATOR_IDENTIFIER, update);
    }

    /** Record that the survey was surfaced but skipped/dismissed without answers. */
    public void saveDismissed(String surveyId, long promptShownAt, String engineName) {
        long observed = System.currentTimeMillis();

        ContentValues value = new ContentValues();
        value.put(DailySurveyGenerator.HISTORY_OBSERVED, observed);
        value.put(DailySurveyGenerator.SURVEY_ID, surveyId);
        value.put(DailySurveyGenerator.QUESTION_KEY, "__survey__");
        value.put(DailySurveyGenerator.QUESTION_TEXT, "");
        value.put(DailySurveyGenerator.QUESTION_TYPE, "");
        value.put(DailySurveyGenerator.RESPONSE_VALUES, "[]");
        value.put(DailySurveyGenerator.RESPONSE_LABELS, "[]");
        value.put(DailySurveyGenerator.OTHER_TEXT, "");
        value.put(DailySurveyGenerator.DISMISSED, 1);
        value.put(DailySurveyGenerator.PROMPT_SHOWN_AT, promptShownAt);
        value.put(DailySurveyGenerator.RESPONDED_AT, observed);
        value.put(DailySurveyGenerator.ENGINE, engineName);

        this.mDatabase.insert(DailySurveyGenerator.TABLE_RESPONSES, null, value);

        Bundle update = new Bundle();
        update.putLong(DailySurveyGenerator.HISTORY_OBSERVED, observed);
        update.putString(DailySurveyGenerator.SURVEY_ID, surveyId);
        update.putString(DailySurveyGenerator.QUESTION_KEY, "__survey__");
        update.putBoolean(DailySurveyGenerator.DISMISSED, true);
        update.putLong(DailySurveyGenerator.PROMPT_SHOWN_AT, promptShownAt);
        update.putLong(DailySurveyGenerator.RESPONDED_AT, observed);
        update.putString(DailySurveyGenerator.ENGINE, engineName);

        Generators.getInstance(this.mContext).notifyGeneratorUpdated(DailySurveyGenerator.GENERATOR_IDENTIFIER, update);
    }

    private static String intArrayToJson(int[] values) {
        JSONArray array = new JSONArray();
        if (values != null) {
            for (int v : values) {
                array.put(v);
            }
        }
        return array.toString();
    }

    private static String stringArrayToJson(String[] labels) {
        JSONArray array = new JSONArray();
        if (labels != null) {
            for (String label : labels) {
                array.put(label);
            }
        }
        return array.toString();
    }

    @Override
    public List<Bundle> fetchPayloads() {
        ArrayList<Bundle> payloads = new ArrayList<>();

        Cursor c = this.mDatabase.query(DailySurveyGenerator.TABLE_RESPONSES, null, null, null, null, null, DailySurveyGenerator.HISTORY_OBSERVED + " DESC");
        while (c.moveToNext()) {
            payloads.add(this.getBundle(c));
        }
        c.close();

        return payloads;
    }

    private Bundle getBundle(Cursor c) {
        Bundle bundle = new Bundle();
        bundle.putLong(DailySurveyGenerator.HISTORY_FETCHED, c.getLong(c.getColumnIndex(DailySurveyGenerator.HISTORY_FETCHED)));
        bundle.putLong(DailySurveyGenerator.HISTORY_TRANSMITTED, c.getLong(c.getColumnIndex(DailySurveyGenerator.HISTORY_TRANSMITTED)));
        bundle.putLong(DailySurveyGenerator.HISTORY_OBSERVED, c.getLong(c.getColumnIndex(DailySurveyGenerator.HISTORY_OBSERVED)));
        bundle.putString(DailySurveyGenerator.SURVEY_ID, c.getString(c.getColumnIndex(DailySurveyGenerator.SURVEY_ID)));
        bundle.putString(DailySurveyGenerator.QUESTION_KEY, c.getString(c.getColumnIndex(DailySurveyGenerator.QUESTION_KEY)));
        bundle.putString(DailySurveyGenerator.QUESTION_TEXT, c.getString(c.getColumnIndex(DailySurveyGenerator.QUESTION_TEXT)));
        bundle.putString(DailySurveyGenerator.QUESTION_TYPE, c.getString(c.getColumnIndex(DailySurveyGenerator.QUESTION_TYPE)));
        bundle.putString(DailySurveyGenerator.RESPONSE_VALUES, c.getString(c.getColumnIndex(DailySurveyGenerator.RESPONSE_VALUES)));
        bundle.putString(DailySurveyGenerator.RESPONSE_LABELS, c.getString(c.getColumnIndex(DailySurveyGenerator.RESPONSE_LABELS)));
        bundle.putString(DailySurveyGenerator.OTHER_TEXT, c.getString(c.getColumnIndex(DailySurveyGenerator.OTHER_TEXT)));
        bundle.putBoolean(DailySurveyGenerator.DISMISSED, c.getInt(c.getColumnIndex(DailySurveyGenerator.DISMISSED)) != 0);
        bundle.putLong(DailySurveyGenerator.PROMPT_SHOWN_AT, c.getLong(c.getColumnIndex(DailySurveyGenerator.PROMPT_SHOWN_AT)));
        bundle.putLong(DailySurveyGenerator.RESPONDED_AT, c.getLong(c.getColumnIndex(DailySurveyGenerator.RESPONDED_AT)));
        bundle.putString(DailySurveyGenerator.ENGINE, c.getString(c.getColumnIndex(DailySurveyGenerator.ENGINE)));
        return bundle;
    }

    @Override
    protected void flushCachedData() {
        // Indefinite retention — survey responses are precious.
    }

    @Override
    public void setCachedDataRetentionPeriod(long period) {
        // Indefinite retention.
    }

    @Override
    public String getIdentifier() {
        return DailySurveyGenerator.GENERATOR_IDENTIFIER;
    }
}

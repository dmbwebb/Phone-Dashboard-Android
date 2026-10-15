package com.audacious_software.phone_dashboard.survey;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class ScriptedSurveyEngineTest {
    private static final String[] CANONICAL = {"very bad", "bad", "so-so", "good", "very good"};

    /** First prompt time at or after {@code from} that shows the given order. */
    public static long promptShowing(boolean descending, long from) {
        long t = from;
        while (ScriptedSurveyEngine.showsMoodDescending(t) != descending) {
            t++;
        }
        return t;
    }

    @Test
    public void moodValuesKeepOneCodingInBothOrders() {
        for (boolean descending : new boolean[]{false, true}) {
            SurveyQuestion q = ScriptedSurveyEngine.moodQuestion("q", CANONICAL, descending);

            assertEquals(descending ? "descending" : "ascending", q.choiceOrder);
            assertEquals(descending ? "very good" : "very bad", q.labelForIndex(0));
            for (int i = 0; i < CANONICAL.length; i++) {
                int value = q.valueForIndex(i);
                // A label always carries the same value, whatever its position.
                assertEquals(CANONICAL[value - 1], q.labelForIndex(i));
                assertEquals(i, q.indexForValue(value));
            }
        }
    }

    @Test
    public void moodOrderIsStablePerPromptAndBalancedAcrossPrompts() {
        long start = 1792080000000L;  // 18:00 Bogotá prompts land near this
        int descending = 0;
        int n = 20000;
        for (int i = 0; i < n; i++) {
            // Prompts a day apart plus jitter, as real prompts arrive.
            long t = start + i * 86_400_000L + (i * 37L) % 1000;
            boolean d = ScriptedSurveyEngine.showsMoodDescending(t);
            assertEquals(d, ScriptedSurveyEngine.showsMoodDescending(t));
            if (d) descending++;
        }
        double share = descending / (double) n;
        assertTrue("descending share " + share, share > 0.47 && share < 0.53);
    }

    @Test
    public void surveyIsV2WithMoodSecondAndOtherQuestionsUnchanged() {
        Context context = ApplicationProvider.getApplicationContext();
        long t = promptShowing(true, 1792080000000L);
        Survey survey = new ScriptedSurveyEngine().currentSurvey(context, t);

        assertEquals("daily_ema_v2", survey.surveyId);
        assertEquals(4, survey.size());
        assertEquals("phone_activity", survey.get(0).questionKey);
        assertEquals("mood_valence", survey.get(1).questionKey);
        assertEquals("sleep_hours", survey.get(2).questionKey);
        assertEquals("social_time_yesterday", survey.get(3).questionKey);

        SurveyQuestion mood = survey.get(1);
        assertEquals(SurveyQuestion.Type.SINGLE, mood.type);
        assertEquals(5, mood.choiceLabels.length);
        assertEquals(5, mood.valueForIndex(0));
        assertTrue(mood.labelForIndex(0).startsWith("😄"));

        // Unvaried questions keep position + 1 and send no order.
        assertNull(survey.get(0).choiceOrder);
        assertEquals(1, survey.get(2).valueForIndex(0));
        assertEquals(3, survey.get(2).indexForValue(4));
    }
}

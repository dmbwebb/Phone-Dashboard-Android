package com.audacious_software.phone_dashboard;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.preference.PreferenceManager;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.test.core.app.ApplicationProvider;

import com.audacious_software.phone_dashboard.survey.ScriptedSurveyEngineTest;
import com.audacious_software.phone_dashboard.survey.SurveyScheduler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class SurveyActivityPolicyTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
                .putString("com.audacious_software.phone_dashboard.IDENTIFIER", "MRD-TEST")
                .putString("com.audacious_software.phone_dashboard.ROLE", AppApplication.ROLE_PARENT)
                .commit();
    }

    @Test
    public void suppressedParentStaleActivityFinishesWithoutLifecycleCrash() {
        ActivityController<SurveyActivity> controller = Robolectric.buildActivity(SurveyActivity.class);
        SurveyActivity activity = controller.create().start().resume().get();

        assertTrue(activity.isFinishing());
        controller.pause().stop().destroy();
    }

    @Test
    public void childMoodAnswerIsRestoredByValueWhenFacesAreShownBestFirst() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString("com.audacious_software.phone_dashboard.ROLE", AppApplication.ROLE_CHILD)
                .commit();
        long promptShownAt = ScriptedSurveyEngineTest.promptShowing(true, 1792080000000L);
        Intent intent = new Intent(context, SurveyActivity.class)
                .putExtra(SurveyScheduler.EXTRA_PROMPT_SHOWN_AT, promptShownAt);

        ActivityController<SurveyActivity> controller = Robolectric.buildActivity(SurveyActivity.class, intent);
        SurveyActivity activity = controller.create().start().resume().get();
        assertFalse(activity.isFinishing());

        LinearLayout choices = activity.findViewById(R.id.survey_choices);
        choices.getChildAt(0).performClick();  // phone activity: first option advances

        // Best-first: very good, good, so-so, bad, very bad.
        assertEquals(5, choices.getChildCount());
        assertTrue(((Button) choices.getChildAt(0)).getText().toString().startsWith("😄"));
        assertTrue(((Button) choices.getChildAt(3)).getText().toString().startsWith("🙁"));
        choices.getChildAt(3).performClick();  // "bad" (value 2) advances to sleep

        activity.findViewById(R.id.survey_back).performClick();

        // The restored choice carries the selected tint; v1's position = value - 1
        // would have marked position 1 instead.
        for (int i = 0; i < choices.getChildCount(); i++) {
            ColorStateList tint = choices.getChildAt(i).getBackgroundTintList();
            boolean selected = tint != null && tint.getDefaultColor() == 0xFFA7F3D0;
            assertEquals("choice " + i, i == 3, selected);
        }

        controller.pause();
    }
}

package com.audacious_software.phone_dashboard;

import android.content.Context;
import android.preference.PreferenceManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

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
}

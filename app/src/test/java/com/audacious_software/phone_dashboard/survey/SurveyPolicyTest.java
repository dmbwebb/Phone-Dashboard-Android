package com.audacious_software.phone_dashboard.survey;

import android.content.Context;
import android.preference.PreferenceManager;

import androidx.test.core.app.ApplicationProvider;

import com.audacious_software.phone_dashboard.AppApplication;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class SurveyPolicyTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
    }

    @Test
    public void serverFieldsPersistAndOverrideLocalRole() throws Exception {
        SurveyPolicy.apply(context, new JSONObject("{\"role\":\"parent\",\"survey_enabled\":false," +
                "\"survey_until\":\"2026-09-21T18:00:00-05:00\"}"));

        assertEquals(AppApplication.ROLE_PARENT,
                SurveyPolicy.effectiveRole(context, AppApplication.ROLE_CHILD));
        assertFalse(SurveyPolicy.allows(context, 0));
    }

    @Test
    public void missingLegacyFieldsPreserveLastKnownValues() throws Exception {
        SurveyPolicy.apply(context, new JSONObject("{\"role\":\"parent\",\"survey_enabled\":false}"));
        SurveyPolicy.apply(context, new JSONObject("{\"identifier\":\"MRD-0001\"}"));

        assertEquals(AppApplication.ROLE_PARENT,
                SurveyPolicy.effectiveRole(context, AppApplication.ROLE_CHILD));
        assertFalse(SurveyPolicy.allows(context, 0));
    }

    @Test
    public void explicitNullClearsPriorCutoff() throws Exception {
        SurveyPolicy.apply(context, new JSONObject("{\"survey_until\":\"2026-09-21T18:00:00Z\"}"));
        assertFalse(SurveyPolicy.allows(context, SurveyPolicy.parseInstant("2026-09-21T18:00:00Z")));

        SurveyPolicy.apply(context, new JSONObject("{\"survey_until\":null}"));
        assertTrue(SurveyPolicy.allows(context, Long.MAX_VALUE));
    }

    @Test
    public void cutoffIsExclusiveAndNullHasNoCutoff() {
        assertTrue(SurveyPolicy.allowsValues(true, 100L, 99L));
        assertFalse(SurveyPolicy.allowsValues(true, 100L, 100L));
        assertFalse(SurveyPolicy.allowsValues(false, null, 0L));
        assertTrue(SurveyPolicy.allowsValues(true, null, Long.MAX_VALUE));
    }

    @Test
    public void parsesTimezoneAwareInstantsOnApi24CompatibleParser() throws Exception {
        assertEquals(0L, SurveyPolicy.parseInstant("1970-01-01T00:00:00Z"));
        assertEquals(0L, SurveyPolicy.parseInstant("1969-12-31T19:00:00-05:00"));
        assertEquals(123L, SurveyPolicy.parseInstant("1970-01-01T00:00:00.123456Z"));
    }
}

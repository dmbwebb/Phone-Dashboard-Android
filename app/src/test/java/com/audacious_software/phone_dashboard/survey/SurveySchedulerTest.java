package com.audacious_software.phone_dashboard.survey;

import com.audacious_software.phone_dashboard.AppApplication;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SurveySchedulerTest {
    private static final TimeZone BOGOTA = TimeZone.getTimeZone("America/Bogota");

    private static long millisFor(int year, int month, int day, int hour, int minute) {
        Calendar cal = Calendar.getInstance(BOGOTA);
        cal.set(year, month, day, hour, minute, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    @Test
    public void nextTrigger_tuesdayChoosesWednesdayAt1800() {
        long now = millisFor(2026, Calendar.SEPTEMBER, 15, 20, 0);

        assertEquals(millisFor(2026, Calendar.SEPTEMBER, 16, 18, 0),
                SurveyScheduler.nextTriggerMillis(now));
    }

    @Test
    public void nextTrigger_wednesdayMorningChoosesSameDay() {
        long now = millisFor(2026, Calendar.SEPTEMBER, 16, 9, 0);

        assertEquals(millisFor(2026, Calendar.SEPTEMBER, 16, 18, 0),
                SurveyScheduler.nextTriggerMillis(now));
    }

    @Test
    public void nextTrigger_atWednesday1800ChoosesSunday() {
        long now = millisFor(2026, Calendar.SEPTEMBER, 16, 18, 0);

        assertEquals(millisFor(2026, Calendar.SEPTEMBER, 20, 18, 0),
                SurveyScheduler.nextTriggerMillis(now));
    }

    @Test
    public void nextTrigger_saturdayChoosesSundayAt1800() {
        long now = millisFor(2026, Calendar.SEPTEMBER, 19, 23, 30);

        assertEquals(millisFor(2026, Calendar.SEPTEMBER, 20, 18, 0),
                SurveyScheduler.nextTriggerMillis(now));
    }

    @Test
    public void nextTrigger_afterSunday1800ChoosesWednesday() {
        long now = millisFor(2026, Calendar.SEPTEMBER, 20, 18, 1);

        assertEquals(millisFor(2026, Calendar.SEPTEMBER, 23, 18, 0),
                SurveyScheduler.nextTriggerMillis(now));
    }

    @Test
    public void nextTrigger_crossesYearBoundary() {
        long now = millisFor(2026, Calendar.DECEMBER, 30, 18, 0);

        assertEquals(millisFor(2027, Calendar.JANUARY, 3, 18, 0),
                SurveyScheduler.nextTriggerMillis(now));
    }

    @Test
    public void nextTrigger_ignoresDeviceDefaultTimezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
            long now = millisFor(2026, Calendar.SEPTEMBER, 16, 9, 0);

            assertEquals(millisFor(2026, Calendar.SEPTEMBER, 16, 18, 0),
                    SurveyScheduler.nextTriggerMillis(now));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    public void promptDay_usesBogotaCalendar() {
        assertTrue(SurveyScheduler.isPromptDay(
                millisFor(2026, Calendar.SEPTEMBER, 16, 18, 0)));
        assertFalse(SurveyScheduler.isPromptDay(
                millisFor(2026, Calendar.SEPTEMBER, 17, 18, 0)));
    }

    @Test
    public void roleGate_includesChildrenAndLegacyInstallsButNotParents() {
        assertTrue(SurveyScheduler.shouldPromptForRole(AppApplication.ROLE_CHILD));
        assertTrue(SurveyScheduler.shouldPromptForRole(null));
        assertFalse(SurveyScheduler.shouldPromptForRole(AppApplication.ROLE_PARENT));
    }
}

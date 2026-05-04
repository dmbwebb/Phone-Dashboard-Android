package com.audacious_software.phone_dashboard;

import com.audacious_software.passive_data_kit.generators.device.DailyUsageAggregateGenerator;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM tests for DailyUsageAggregateGenerator static helpers.
 * Full-integration tests run on the Pixel emulator via androidTest.
 */
public class DailyUsageAggregateGeneratorTest {
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final TimeZone BOGOTA = TimeZone.getTimeZone("America/Bogota");
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static long invokeComputeDayBucket(long epochMs, TimeZone tz) throws Exception {
        Method m = DailyUsageAggregateGenerator.class.getDeclaredMethod("computeDayBucket", long.class, TimeZone.class);
        m.setAccessible(true);
        return (Long) m.invoke(null, epochMs, tz);
    }

    private static long invokeComputeNextFireTime(long now, TimeZone tz) throws Exception {
        Method m = DailyUsageAggregateGenerator.class.getDeclaredMethod("computeNextFireTime", long.class, TimeZone.class);
        m.setAccessible(true);
        return (Long) m.invoke(null, now, tz);
    }

    private static long millisFor(TimeZone timeZone, int year, int month, int day, int hour, int minute) {
        Calendar cal = Calendar.getInstance(timeZone);
        cal.set(year, month, day, hour, minute, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    @Test
    public void dayBucket_landsOnLocalMidnight_bogota() throws Exception {
        long afternoon = millisFor(BOGOTA, 2026, Calendar.APRIL, 14, 14, 30);

        long bucket = invokeComputeDayBucket(afternoon, BOGOTA);

        assertEquals(millisFor(BOGOTA, 2026, Calendar.APRIL, 14, 0, 0), bucket);
    }

    @Test
    public void dayBucket_midnight_isItsOwnBucket() throws Exception {
        long midnight = millisFor(BOGOTA, 2026, Calendar.APRIL, 14, 0, 0);

        long bucket = invokeComputeDayBucket(midnight, BOGOTA);

        assertEquals(midnight, bucket);
    }

    @Test
    public void dayBucket_bucketLengthIs24HoursInNonDstZone() throws Exception {
        long dayOne = millisFor(BOGOTA, 2026, Calendar.APRIL, 14, 12, 0);
        long dayTwo = millisFor(BOGOTA, 2026, Calendar.APRIL, 15, 12, 0);

        long bucketOne = invokeComputeDayBucket(dayOne, BOGOTA);
        long bucketTwo = invokeComputeDayBucket(dayTwo, BOGOTA);

        // Colombia has no DST, so exactly 24h apart.
        assertEquals(DAY_MS, bucketTwo - bucketOne);
    }

    @Test
    public void dayBucket_respectsTimezone_notUtc() throws Exception {
        long ts = millisFor(BOGOTA, 2026, Calendar.APRIL, 14, 23, 0);

        long bogotaBucket = invokeComputeDayBucket(ts, BOGOTA);
        long utcBucket = invokeComputeDayBucket(ts, UTC);

        // Different timezones should yield different local midnights for this timestamp.
        assertTrue("bogota bucket and utc bucket must differ at late-night boundary",
                bogotaBucket != utcBucket);
    }

    @Test
    public void nextFireTime_fromMorning_picksTodayAt1500_butAlwaysFuture() throws Exception {
        long now = millisFor(BOGOTA, 2026, Calendar.APRIL, 14, 2, 0);

        long fire = invokeComputeNextFireTime(now, BOGOTA);

        assertEquals(millisFor(BOGOTA, 2026, Calendar.APRIL, 14, 15, 0), fire);
        assertTrue("fire must be strictly in the future", fire > now);
    }

    @Test
    public void nextFireTime_fromAfternoon_picksTomorrowAt1500() throws Exception {
        long now = millisFor(BOGOTA, 2026, Calendar.APRIL, 14, 15, 0);

        long fire = invokeComputeNextFireTime(now, BOGOTA);

        assertEquals(millisFor(BOGOTA, 2026, Calendar.APRIL, 15, 15, 0), fire);
    }

    @Test
    public void nextFireTime_atExactlyAlarmHour_picksTomorrow() throws Exception {
        long now = millisFor(BOGOTA, 2026, Calendar.APRIL, 14, 15, 0);

        long fire = invokeComputeNextFireTime(now, BOGOTA);

        assertEquals(now + DAY_MS, fire);
        assertTrue(fire > now);
    }
}

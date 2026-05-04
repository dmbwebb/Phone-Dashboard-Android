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
    private static long invokeComputeDayBucket(long epochMs, TimeZone tz) throws Exception {
        Method m = DailyUsageAggregateGenerator.class.getDeclaredMethod("computeDayBucket", long.class, TimeZone.class);
        m.setAccessible(true);
        return (Long) m.invoke(null, epochMs, tz);
    }

    @Test
    public void dayBucket_landsOnLocalMidnight_bogota() throws Exception {
        TimeZone bogota = TimeZone.getTimeZone("America/Bogota");

        // 2026-04-14 14:30:00 Bogota = epoch ms
        Calendar cal = Calendar.getInstance(bogota);
        cal.set(2026, Calendar.APRIL, 14, 14, 30, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long afternoon = cal.getTimeInMillis();

        long bucket = invokeComputeDayBucket(afternoon, bogota);

        // Expected bucket: 2026-04-14 00:00:00 Bogota
        Calendar expected = Calendar.getInstance(bogota);
        expected.set(2026, Calendar.APRIL, 14, 0, 0, 0);
        expected.set(Calendar.MILLISECOND, 0);
        assertEquals(expected.getTimeInMillis(), bucket);
    }

    @Test
    public void dayBucket_midnight_isItsOwnBucket() throws Exception {
        TimeZone bogota = TimeZone.getTimeZone("America/Bogota");

        Calendar cal = Calendar.getInstance(bogota);
        cal.set(2026, Calendar.APRIL, 14, 0, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long midnight = cal.getTimeInMillis();

        long bucket = invokeComputeDayBucket(midnight, bogota);

        assertEquals(midnight, bucket);
    }

    @Test
    public void dayBucket_bucketLengthIs24HoursInNonDstZone() throws Exception {
        TimeZone bogota = TimeZone.getTimeZone("America/Bogota");

        Calendar cal = Calendar.getInstance(bogota);
        cal.set(2026, Calendar.APRIL, 14, 12, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long dayOne = cal.getTimeInMillis();

        cal.set(2026, Calendar.APRIL, 15, 12, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long dayTwo = cal.getTimeInMillis();

        long bucketOne = invokeComputeDayBucket(dayOne, bogota);
        long bucketTwo = invokeComputeDayBucket(dayTwo, bogota);

        // Colombia has no DST, so exactly 24h apart.
        assertEquals(24L * 60L * 60L * 1000L, bucketTwo - bucketOne);
    }

    @Test
    public void dayBucket_respectsTimezone_notUtc() throws Exception {
        TimeZone bogota = TimeZone.getTimeZone("America/Bogota");
        TimeZone utc = TimeZone.getTimeZone("UTC");

        // Pick a timestamp that's early morning in Bogota but late-night the previous day in UTC:
        // Bogota is UTC-5 (no DST). 2026-04-14 01:00 Bogota = 2026-04-14 06:00 UTC.
        // Wait, need the opposite — let's use 2026-04-14 23:00 Bogota = 2026-04-15 04:00 UTC
        Calendar cal = Calendar.getInstance(bogota);
        cal.set(2026, Calendar.APRIL, 14, 23, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long ts = cal.getTimeInMillis();

        long bogotaBucket = invokeComputeDayBucket(ts, bogota);
        long utcBucket = invokeComputeDayBucket(ts, utc);

        // Different timezones should yield different local midnights for this timestamp.
        assertTrue("bogota bucket and utc bucket must differ at late-night boundary",
                bogotaBucket != utcBucket);
    }

    private static long invokeComputeNextFireTime(long now, TimeZone tz) throws Exception {
        Method m = DailyUsageAggregateGenerator.class.getDeclaredMethod("computeNextFireTime", long.class, TimeZone.class);
        m.setAccessible(true);
        return (Long) m.invoke(null, now, tz);
    }

    @Test
    public void nextFireTime_fromMorning_picksTodayAt1500_butAlwaysFuture() throws Exception {
        // 02:00 Bogota — next 15:00 is later today.
        TimeZone bogota = TimeZone.getTimeZone("America/Bogota");
        Calendar cal = Calendar.getInstance(bogota);
        cal.set(2026, Calendar.APRIL, 14, 2, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long now = cal.getTimeInMillis();

        long fire = invokeComputeNextFireTime(now, bogota);

        Calendar expected = Calendar.getInstance(bogota);
        expected.set(2026, Calendar.APRIL, 14, 15, 0, 0);
        expected.set(Calendar.MILLISECOND, 0);
        assertEquals(expected.getTimeInMillis(), fire);
        assertTrue("fire must be strictly in the future", fire > now);
    }

    @Test
    public void nextFireTime_fromAfternoon_picksTomorrowAt1500() throws Exception {
        TimeZone bogota = TimeZone.getTimeZone("America/Bogota");
        Calendar cal = Calendar.getInstance(bogota);
        cal.set(2026, Calendar.APRIL, 14, 15, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long now = cal.getTimeInMillis();

        long fire = invokeComputeNextFireTime(now, bogota);

        Calendar expected = Calendar.getInstance(bogota);
        expected.set(2026, Calendar.APRIL, 15, 15, 0, 0);
        expected.set(Calendar.MILLISECOND, 0);
        assertEquals(expected.getTimeInMillis(), fire);
    }

    @Test
    public void nextFireTime_atExactlyAlarmHour_picksTomorrow() throws Exception {
        // At 15:00:00.000 sharp — strict future means tomorrow's 15:00.
        TimeZone bogota = TimeZone.getTimeZone("America/Bogota");
        Calendar cal = Calendar.getInstance(bogota);
        cal.set(2026, Calendar.APRIL, 14, 15, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long now = cal.getTimeInMillis();

        long fire = invokeComputeNextFireTime(now, bogota);

        assertEquals(now + 24L * 60L * 60L * 1000L, fire);
        assertTrue(fire > now);
    }
}

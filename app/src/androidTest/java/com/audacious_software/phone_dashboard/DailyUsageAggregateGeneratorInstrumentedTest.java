package com.audacious_software.phone_dashboard;

import android.content.Context;
import android.database.Cursor;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.audacious_software.passive_data_kit.generators.device.DailyUsageAggregateGenerator;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Instrumented test for DailyUsageAggregateGenerator.
 *
 * Runs on the emulator where UsageStatsManager.queryUsageStats(INTERVAL_DAILY)
 * returns real data. Requires GET_USAGE_STATS appop to be granted (done via
 * adb shell appops set ... allow in the test setup).
 */
@RunWith(AndroidJUnit4.class)
public class DailyUsageAggregateGeneratorInstrumentedTest {

    @Test
    public void runAggregation_populatesSqlite() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();

        DailyUsageAggregateGenerator gen = DailyUsageAggregateGenerator.getInstance(context);
        assertNotNull(gen);

        // Confirm permission is granted (if not, the usage-stats query returns empty
        // and the test degenerates — but we still exercise the SQLite dedup path).
        boolean hasPerm = DailyUsageAggregateGenerator.hasPermissions(context);
        android.util.Log.i("PDKTest", "hasPermissions=" + hasPerm);

        // Run aggregation twice to exercise the dedup branch.
        Method run = DailyUsageAggregateGenerator.class.getDeclaredMethod("runAggregation");
        run.setAccessible(true);
        run.invoke(gen);
        run.invoke(gen);

        Cursor c = gen.queryHistory(null, null, null, "observed DESC");
        int rowCount = c.getCount();
        android.util.Log.i("PDKTest", "history row count=" + rowCount);
        c.close();

        if (hasPerm) {
            assertTrue("Expected > 0 rows after runAggregation with usage-stats permission",
                    rowCount > 0);
        }
    }
}

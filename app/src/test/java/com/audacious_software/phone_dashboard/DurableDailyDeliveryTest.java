package com.audacious_software.phone_dashboard;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;

import com.audacious_software.passive_data_kit.PassiveDataKit;
import com.audacious_software.passive_data_kit.generators.Generators;
import com.audacious_software.passive_data_kit.generators.device.DailyUsageAggregateGenerator;

import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual collector and SQLite outbox with controlled Android usage input. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = android.app.Application.class)
public class DurableDailyDeliveryTest {
    private Context context;
    private UsageStats usage;
    private UsageStatsManager manager;
    private DailyUsageAggregateGenerator aggregate;
    private Generators generators;
    private final List<Generators.GeneratorUpdatedListener> registeredListeners = new ArrayList<>();

    @Before public void setUp() throws Exception {
        manager = mock(UsageStatsManager.class);
        usage = mock(UsageStats.class);
        when(usage.getPackageName()).thenReturn("test.usage.package");
        when(usage.getTotalTimeInForeground()).thenReturn(8000L);
        when(usage.getFirstTimeStamp()).thenReturn(System.currentTimeMillis() - 10000);
        when(usage.getLastTimeStamp()).thenReturn(System.currentTimeMillis());
        when(usage.getLastTimeUsed()).thenReturn(System.currentTimeMillis());
        when(manager.queryUsageStats(anyInt(), anyLong(), anyLong()))
                .thenReturn(Collections.singletonList(usage));
        context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public Object getSystemService(String name) {
                return Context.USAGE_STATS_SERVICE.equals(name) ? manager : super.getSystemService(name);
            }
        };
        generators = Generators.getInstance(context);
        // GeneratorsHolder survives Robolectric test methods; its cached preferences
        // must follow this method's fresh Application and database environment.
        Field preferences = Generators.class.getDeclaredField("mSharedPreferences");
        preferences.setAccessible(true);
        preferences.set(generators, null);
        android.app.AppOpsManager appOps = (android.app.AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        org.robolectric.Shadows.shadowOf(appOps).setMode(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.getPackageName(), android.app.AppOpsManager.MODE_ALLOWED);
        org.robolectric.Shadows.shadowOf((android.os.UserManager) context.getSystemService(Context.USER_SERVICE))
                .setUserUnlocked(true);
        aggregate = new DailyUsageAggregateGenerator(context);
        aggregate.setLookbackDays(1);
    }

    private void addListener(Generators.GeneratorUpdatedListener listener) {
        registeredListeners.add(listener);
        generators.addNewGeneratorUpdatedListener(listener);
    }

    @After public void removeListeners() {
        for (Generators.GeneratorUpdatedListener listener : registeredListeners) {
            generators.removeGeneratorUpdatedListener(listener);
        }
    }

    private int pending() {
        try (Cursor cursor = aggregate.queryHistory(null, "pending_delivery = 1", null, null)) {
            return cursor.getCount();
        }
    }

    private static class Sink implements Generators.DurableGeneratorUpdatedListener {
        boolean accept = true;
        final List<Bundle> accepted = new ArrayList<>();
        @Override public void onGeneratorUpdated(String id, long timestamp, Bundle data) {
            fail("Daily summaries must use the durable interface exclusively");
        }
        @Override public boolean enqueueGeneratorUpdates(String id, List<Bundle> updates) {
            if (accept) accepted.addAll(updates);
            return accept;
        }
    }

    @Test public void coldCollectionThenUnchangedRerunDeliversOnce() {
        aggregate.runAggregation();
        assertEquals(1, pending());
        Sink sink = new Sink();
        addListener(sink);
        aggregate.runAggregation();
        assertEquals(1, sink.accepted.size());
        assertEquals(0, pending());
        aggregate.runAggregation();
        assertEquals(1, sink.accepted.size());
    }

    @Test public void rejectionRetainsRowAcrossReopeningDatabase() {
        Sink sink = new Sink();
        sink.accept = false;
        addListener(sink);
        aggregate.runAggregation();
        assertEquals(1, pending());
        aggregate = new DailyUsageAggregateGenerator(context);
        sink.accept = true;
        aggregate.replayPending();
        assertEquals(1, sink.accepted.size());
        assertEquals(0, pending());
    }

    @Test public void OrdinaryListenerCannotAcknowledgeDailyRows() {
        addListener((id, timestamp, bundle) -> {});
        aggregate.runAggregation();
        assertEquals(1, pending());
    }

    @Test public void disabledCollectorStopsNewQueriesButDeliversAlreadyPendingRows() throws Exception {
        aggregate.runAggregation();
        assertEquals(1, pending());
        aggregate.updateConfig(new org.json.JSONObject().put("enabled", false));
        when(usage.getTotalTimeInForeground()).thenReturn(9000L);
        Sink sink = new Sink();
        addListener(sink);
        aggregate.runAggregation();
        verify(manager, times(1)).queryUsageStats(anyInt(), anyLong(), anyLong());
        assertEquals(1, sink.accepted.size());
        assertEquals(8000, sink.accepted.get(0).getLong("total_ms"));
        assertEquals(0, pending());
    }

    @Test public void newerRevisionDuringAcknowledgementIsNotCleared() throws Exception {
        aggregate.runAggregation();
        Field databaseField = DailyUsageAggregateGenerator.class.getDeclaredField("mDatabase");
        databaseField.setAccessible(true);
        SQLiteDatabase database = (SQLiteDatabase) databaseField.get(aggregate);
        Sink sink = new Sink() {
            @Override public boolean enqueueGeneratorUpdates(String id, List<Bundle> updates) {
                if (accepted.isEmpty()) {
                    database.execSQL("UPDATE history SET total_ms = 9000, observed = observed + 1, pending_delivery = 1");
                    return super.enqueueGeneratorUpdates(id, updates);
                }
                return false;
            }
        };
        addListener(sink);
        aggregate.replayPending();
        assertEquals(1, pending());
        assertEquals(8000, sink.accepted.get(0).getLong("total_ms"));
        generators.removeGeneratorUpdatedListener(sink);
        Sink next = new Sink();
        addListener(next);
        aggregate.replayPending();
        assertEquals(9000, next.accepted.get(0).getLong("total_ms"));
        assertEquals(0, pending());
    }

    @Test public void versionOneMigrationDoesNotReplayHistoricalRows() throws Exception {
        Field databaseField = DailyUsageAggregateGenerator.class.getDeclaredField("mDatabase");
        databaseField.setAccessible(true);
        SQLiteDatabase database = (SQLiteDatabase) databaseField.get(aggregate);
        database.execSQL("DROP TABLE history");
        database.execSQL(context.getString(com.audacious_software.passive_data_kit.R.string.pdk_generator_daily_usage_aggregate_create_history_table));
        database.execSQL("UPDATE metadata SET value = '1' WHERE key = 'version'");
        database.execSQL("INSERT INTO history (observed, day_bucket, package, total_ms, first_timestamp, last_timestamp, last_time_used) VALUES (1, 1, 'legacy.app', 6000, 1, 2, 2)");
        aggregate = new DailyUsageAggregateGenerator(context);
        Sink sink = new Sink();
        addListener(sink);
        aggregate.replayPending();
        assertEquals(0, sink.accepted.size());
        aggregate.runAggregation();
        assertEquals(1, sink.accepted.size());
        assertEquals("test.usage.package", sink.accepted.get(0).getString("package"));
    }
}

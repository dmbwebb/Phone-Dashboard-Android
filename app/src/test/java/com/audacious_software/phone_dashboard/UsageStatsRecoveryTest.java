package com.audacious_software.phone_dashboard;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.audacious_software.passive_data_kit.generators.device.UsageStatsGenerator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = android.app.Application.class)
public class UsageStatsRecoveryTest {
    private UsageStatsGenerator collector;
    private UsageStatsManager manager;
    private Context context;
    private SharedPreferences health;
    private Runnable collection;
    private ScheduledThreadPoolExecutor executor;

    @Before public void setUp() throws Exception {
        manager = mock(UsageStatsManager.class);
        context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public Object getSystemService(String name) {
                return USAGE_STATS_SERVICE.equals(name) ? manager : super.getSystemService(name);
            }
        };
        permission(AppOpsManager.MODE_ALLOWED);
        health = PreferenceManager.getDefaultSharedPreferences(context);
        collector = new UsageStatsGenerator(context);
        Method start = UsageStatsGenerator.class.getDeclaredMethod("startGenerator");
        start.setAccessible(true);
        start.invoke(collector);
        Field task = UsageStatsGenerator.class.getDeclaredField("mCollectionTask");
        task.setAccessible(true);
        collection = (Runnable) task.get(collector);
        Field service = UsageStatsGenerator.class.getDeclaredField("mService");
        service.setAccessible(true);
        executor = (ScheduledThreadPoolExecutor) service.get(collector);
    }

    private void permission(int mode) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        Shadows.shadowOf(appOps).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.getPackageName(), mode);
    }

    @After public void tearDown() { if (executor != null) executor.shutdownNow(); }

    @Test public void nullBeforeUnlockDoesNotKillFutureCollection() {
        when(manager.queryEvents(anyLong(), anyLong())).thenReturn(null).thenReturn(mock(UsageEvents.class));
        collection.run();
        assertEquals("query_unavailable", health.getString(UsageStatsGenerator.LAST_QUERY_RESULT, ""));
        assertEquals(0, health.getLong(UsageStatsGenerator.LAST_QUERY_SUCCESS, 0));
        assertFalse(executor.getQueue().isEmpty());
        collection.run();
        assertEquals("success", health.getString(UsageStatsGenerator.LAST_QUERY_RESULT, ""));
        assertTrue(health.getLong(UsageStatsGenerator.LAST_QUERY_SUCCESS, 0) > 0);
    }

    @Test public void queryExceptionDoesNotKillFutureCollection() {
        when(manager.queryEvents(anyLong(), anyLong())).thenThrow(new SecurityException("temporarily unavailable"))
                .thenReturn(mock(UsageEvents.class));
        collection.run();
        assertEquals("error:SecurityException", health.getString(UsageStatsGenerator.LAST_QUERY_RESULT, ""));
        assertFalse(executor.getQueue().isEmpty());
        collection.run();
        assertEquals("success", health.getString(UsageStatsGenerator.LAST_QUERY_RESULT, ""));
    }

    @Test public void permissionRegrantRestartsQueriesWithoutRestartingProcess() {
        permission(AppOpsManager.MODE_IGNORED);
        collection.run();
        verifyNoInteractions(manager);
        assertEquals("permission_missing", health.getString(UsageStatsGenerator.LAST_QUERY_RESULT, ""));
        permission(AppOpsManager.MODE_ALLOWED);
        when(manager.queryEvents(anyLong(), anyLong())).thenReturn(mock(UsageEvents.class));
        collection.run();
        verify(manager).queryEvents(anyLong(), anyLong());
        assertEquals("success", health.getString(UsageStatsGenerator.LAST_QUERY_RESULT, ""));
    }
}

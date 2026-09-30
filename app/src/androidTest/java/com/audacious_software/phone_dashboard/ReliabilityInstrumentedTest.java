package com.audacious_software.phone_dashboard;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.audacious_software.passive_data_kit.generators.Generators;
import com.audacious_software.passive_data_kit.PassiveDataKit;
import com.audacious_software.passive_data_kit.generators.device.DailyUsageAggregateGenerator;
import com.audacious_software.passive_data_kit.transmitters.HttpTransmitter;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.click;
import static org.hamcrest.Matchers.containsString;

/** Real OS UsageStats, SQLite, filesystem queue and HTTP; no mock data-source services. */
@RunWith(AndroidJUnit4.class)
public class ReliabilityInstrumentedTest {
    static final String BASE = "http://127.0.0.1:8765";
    static final String CONFIG = "{\"identifier\":\"E2E-SYNTHETIC\",\"transmitters\":[{\"type\":\"pdk-http-transmitter\",\"upload-uri\":\""
            + BASE + "/upload\",\"wifi-only\":false,\"charging-only\":false,\"compression\":false}],"
            + "\"generators\":[{\"identifier\":\"pdk-daily-usage-aggregate\",\"enabled\":true,\"lookback-days\":7}]}";
    private Context context() { return ApplicationProvider.getApplicationContext(); }
    private interface Check { boolean get() throws Exception; }
    private void await(String description, long timeout, Check check) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < end) {
            if (check.get()) return;
            SystemClock.sleep(100);
        }
        fail(description);
    }
    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(fd.getFileDescriptor());
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            return bytes.toString("UTF-8");
        }
    }
    private JSONObject fixture(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        try (java.io.InputStream input = connection.getInputStream();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            return new JSONObject(bytes.toString("UTF-8"));
        } finally { connection.disconnect(); }
    }
    private HttpTransmitter transmitter() {
        HttpTransmitter transmitter = new HttpTransmitter();
        HashMap<String, String> options = new HashMap<>();
        options.put(HttpTransmitter.UPLOAD_URI, BASE + "/upload");
        options.put(HttpTransmitter.USER_ID, "E2E-SYNTHETIC");
        options.put(HttpTransmitter.WIFI_ONLY, "false");
        options.put(HttpTransmitter.CHARGING_ONLY, "false");
        transmitter.initialize(context(), options);
        return transmitter;
    }
    private void grantUsage(boolean allow) throws Exception {
        shell("appops set " + context().getPackageName() + " GET_USAGE_STATS " + (allow ? "allow" : "deny"));
        await("Usage access did not change", 5000,
                () -> DailyUsageAggregateGenerator.hasPermissions(context()) == allow);
    }
    private void generateActualUsage() throws Exception {
        grantUsage(true);
        shell("am start -a android.settings.SETTINGS");
        SystemClock.sleep(2500);
        shell("input keyevent KEYCODE_HOME");
        SystemClock.sleep(1500);
    }
    private long settingsTotal(DailyUsageAggregateGenerator generator) {
        try (Cursor cursor = generator.queryHistory(new String[]{"total_ms"},
                "package = ?", new String[]{"com.android.settings"}, "day_bucket DESC")) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }
    private int pending(DailyUsageAggregateGenerator generator) {
        try (Cursor cursor = generator.queryHistory(null, "pending_delivery = 1", null, null)) {
            return cursor.getCount();
        }
    }
    private boolean received(String key, String value) throws Exception {
        JSONArray records = fixture("/state").getJSONArray("records");
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            if (value.equals(record.optString(key))) {
                JSONObject metadata = record.getJSONObject("passive-data-metadata");
                assertEquals("E2E-SYNTHETIC", metadata.getString("source"));
                if ("package".equals(key)) assertEquals("pdk-daily-usage-aggregate", metadata.getString("generator-id"));
                assertTrue(metadata.getDouble("timestamp") > 0);
                return true;
            }
        }
        return false;
    }
    @Test public void realUsageCollectedBeforeUploaderIsEventuallyDelivered() throws Exception {
        fixture("/reset");
        generateActualUsage();
        DailyUsageAggregateGenerator generator = DailyUsageAggregateGenerator.getInstance(context());
        generator.runAggregation();
        assertTrue("Actual Settings use must persist in SQLite", settingsTotal(generator) > 0);
        assertTrue("No transmitter must leave durable pending rows", pending(generator) > 0);
        HttpTransmitter transmitter = transmitter();
        generator.replayPending();
        assertEquals("Rows acknowledged only after durable queue creation", 0, pending(generator));
        transmitter.transmit(true);
        await("Actual Settings aggregate never reached HTTP sink", 15000,
                () -> received("package", "com.android.settings"));
        transmitter.deinitialize(context());
    }
    @Test public void permissionRevokedAndRestoredResumesActualCollection() throws Exception {
        generateActualUsage();
        DailyUsageAggregateGenerator generator = DailyUsageAggregateGenerator.getInstance(context());
        grantUsage(false);
        generator.runAggregation();
        assertEquals("Revoked usage permission must not fabricate summaries", 0, settingsTotal(generator));
        grantUsage(true);
        generator.runAggregation();
        assertTrue("Restoring permission must resume real collection", settingsTotal(generator) > 0);
    }
    @Test public void http503RetainsQueueAndBackoffDoesNotBlockNewRecords() throws Exception {
        failedNetworkRetainsQueueAndRecovers(503);
    }
    @Test public void disconnectedSocketRetainsQueueAndRecovers() throws Exception {
        failedNetworkRetainsQueueAndRecovers(444);
    }
    private void failedNetworkRetainsQueueAndRecovers(int status) throws Exception {
        fixture("/reset"); fixture("/mode?status=" + status);
        HttpTransmitter transmitter = transmitter();
        Bundle first = new Bundle(); first.putString("marker", "before-failure");
        assertTrue(transmitter.enqueueGeneratorUpdates("e2e-synthetic", Collections.singletonList(first)));
        transmitter.transmit(true);
        await("Fixture never received failing request", 15000, () -> fixture("/state").getInt("attempts") >= 1);
        SystemClock.sleep(1000);
        assertTrue("Failed network request must retain payload", transmitter.pendingTransmissions() > 0);
        long start = SystemClock.elapsedRealtime();
        Bundle second = new Bundle(); second.putString("marker", "during-backoff");
        assertTrue(transmitter.enqueueGeneratorUpdates("e2e-synthetic", Collections.singletonList(second)));
        assertTrue("Network failure must not block durable disk writes", SystemClock.elapsedRealtime() - start < 3000);
        int attempts = fixture("/state").getInt("attempts");
        for (int i = 0; i < 20; i++) transmitter.transmit(true);
        SystemClock.sleep(2000);
        assertEquals("Manual sync must respect backoff", attempts, fixture("/state").getInt("attempts"));
        fixture("/mode?status=201");
        await("Retained observations never recovered after backoff", 45000, () -> {
            transmitter.transmit(true);
            return received("marker", "before-failure") && received("marker", "during-backoff");
        });
        transmitter.deinitialize(context());
    }
    @Test public void preparePendingRealUsageForProcessDeath() throws Exception {
        fixture("/reset"); generateActualUsage();
        DailyUsageAggregateGenerator generator = DailyUsageAggregateGenerator.getInstance(context());
        generator.runAggregation();
        long total = settingsTotal(generator);
        assertTrue(total > 0); assertTrue(pending(generator) > 0);
        PreferenceManager.getDefaultSharedPreferences(context()).edit().putLong("e2e.expected_total", total).commit();
    }
    @Test public void recoverPendingRealUsageAfterProcessDeath() throws Exception {
        long expected = PreferenceManager.getDefaultSharedPreferences(context()).getLong("e2e.expected_total", -1);
        assertTrue("Run prepare stage first without clearing app", expected > 0);
        DailyUsageAggregateGenerator generator = DailyUsageAggregateGenerator.getInstance(context());
        assertEquals(expected, settingsTotal(generator));
        assertTrue("Pending state must survive process death", pending(generator) > 0);
        HttpTransmitter transmitter = transmitter(); generator.replayPending(); transmitter.transmit(true);
        await("Cold-process replay never reached sink", 15000, () -> received("package", "com.android.settings"));
        JSONArray records = fixture("/state").getJSONArray("records");
        boolean exact = false;
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            exact |= "com.android.settings".equals(record.optString("package")) && record.optLong("total_ms") == expected;
        }
        assertTrue("Recovered original value without recollection", exact);
        transmitter.deinitialize(context());
    }
    @Test public void repeatedCachedStartupKeepsOneTransmitter() throws Exception {
        assertEquals("E2E-SYNTHETIC", ((AppApplication) context()).getIdentifier());
        Schedule schedule = Schedule.getInstance(context());
        for (int i = 0; i < 30; i++) schedule.resumeMonitoring();
        await("Monitoring failed to start from cached config", 15000,
                () -> Generators.getInstance(context()).activeTransmitters().size() == 1);
        SystemClock.sleep(3000);
        assertEquals("Repeated startup must not duplicate uploaders", 1,
                Generators.getInstance(context()).activeTransmitters().size());
    }
    @Test public void upgradingLegacyDatabaseDoesNotReplayHistoricRows() throws Exception {
        fixture("/reset");
        File path = new File(PassiveDataKit.getGeneratorsStorage(context()), "pdk-daily-usage-aggregate.sqlite");
        try (SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(path, null)) {
            old.execSQL(context().getString(com.audacious_software.passive_data_kit.R.string.pdk_generator_create_version_table));
            old.execSQL("INSERT INTO metadata VALUES ('version', '1', 1)");
            old.execSQL(context().getString(com.audacious_software.passive_data_kit.R.string.pdk_generator_daily_usage_aggregate_create_history_table));
            old.execSQL("INSERT INTO history (observed,day_bucket,package,total_ms) VALUES (1,1,'e2e.historical',1234)");
        }
        DailyUsageAggregateGenerator generator = DailyUsageAggregateGenerator.getInstance(context());
        assertEquals("Old rows must not become pending on upgrade", 0, pending(generator));
        HttpTransmitter transmitter = transmitter(); generator.replayPending(); transmitter.transmit(true);
        SystemClock.sleep(1000);
        assertFalse("Pilot user declined historic replay", received("package", "e2e.historical"));
        generateActualUsage(); generator.runAggregation(); transmitter.transmit(true);
        await("New observations must still work after schema upgrade", 15000,
                () -> received("package", "com.android.settings"));
        transmitter.deinitialize(context());
    }
    @Test public void malformedConfigurationPreservesCachedMonitoring() throws Exception {
        fixture("/reset");
        Schedule schedule = Schedule.getInstance(context());
        schedule.resumeMonitoring();
        await("Cached monitoring never started", 15000,
                () -> Generators.getInstance(context()).activeTransmitters().size() == 1);
        String cached = PreferenceManager.getDefaultSharedPreferences(context()).getString(Schedule.SAVED_CONFIGURATION, "");
        fixture("/mode?config=malformed");
        assertFalse("Malformed config must be rejected", schedule.refreshMonitoringConfiguration());
        assertEquals("Malformed response must preserve last valid config", cached,
                PreferenceManager.getDefaultSharedPreferences(context()).getString(Schedule.SAVED_CONFIGURATION, ""));
        assertEquals(1, Generators.getInstance(context()).activeTransmitters().size());
        HttpTransmitter transmitter = (HttpTransmitter) Generators.getInstance(context()).activeTransmitters().get(0);
        Bundle record = new Bundle(); record.putString("marker", "cached-config-still-works");
        assertTrue(transmitter.enqueueGeneratorUpdates("e2e-synthetic", Collections.singletonList(record)));
        transmitter.transmit(true);
        await("Malformed config disabled working data delivery", 15000,
                () -> received("marker", "cached-config-still-works"));
        fixture("/mode?config=valid");
    }
    @Test public void spanishHealthScreenOpensRealPermissionSettings() throws Exception {
        grantUsage(false);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context().getSystemService(android.app.LocaleManager.class)
                        .setApplicationLocales(android.os.LocaleList.forLanguageTags("es"));
            } else {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("es"));
            }
        });
        try (ActivityScenario<MonitoringHealthActivity> screen = ActivityScenario.launch(MonitoringHealthActivity.class)) {
            onView(withText(containsString("Desactivado: no se puede recoger el uso"))).check(matches(isDisplayed()));
            screen.onActivity(activity -> {
                android.view.ViewGroup holder = activity.findViewById(android.R.id.content);
                android.view.ViewGroup root = (android.view.ViewGroup) holder.getChildAt(0);
                android.view.View toolbar = root.getChildAt(0);
                android.view.View scroll = root.getChildAt(1);
                androidx.core.view.WindowInsetsCompat windowInsets = androidx.core.view.ViewCompat.getRootWindowInsets(root);
                assertNotNull("Real window insets must be available", windowInsets);
                androidx.core.graphics.Insets bars = windowInsets.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.systemBars()
                                | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
                assertTrue("Toolbar must clear the status bar", toolbar.getTop() >= bars.top);
                assertEquals("Scrolling content must start below toolbar", toolbar.getBottom(), scroll.getTop());
                assertTrue("Content must clear bottom navigation", scroll.getBottom() <= root.getHeight() - bars.bottom);
                assertTrue("Toolbar must have visible height", toolbar.getHeight() > 0);
            });
            // Espresso waits for layout, but API24 may not yet have submitted
            // the first rendered frame to the screenshot compositor.
            SystemClock.sleep(1000);
            Bitmap topScreenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull("Health-screen top screenshot", topScreenshot);
            try (FileOutputStream output = new FileOutputStream(new File(context().getExternalCacheDir(), "e2e-health-es-top.png"))) {
                assertTrue(topScreenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
            topScreenshot.recycle();
            onView(withText("Revisar acceso a datos de uso")).perform(scrollTo()).check(matches(isDisplayed()));
            Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull("Health-screen screenshot", screenshot);
            try (FileOutputStream output = new FileOutputStream(new File(context().getExternalCacheDir(), "e2e-health-es.png"))) {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
            screenshot.recycle();
            onView(withText("Revisar acceso a datos de uso")).perform(click());
            await("Repair button did not open Android settings", 5000, () -> {
                android.view.accessibility.AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                        .getUiAutomation().getRootInActiveWindow();
                return root != null && "com.android.settings".contentEquals(root.getPackageName());
            });
        }
    }
    @Test public void realReadTimeoutPreservesQueueWithoutBlockingDisk() throws Exception {
        fixture("/reset"); fixture("/mode?status=0");
        HttpTransmitter transmitter = transmitter();
        Bundle before = new Bundle(); before.putString("marker", "timeout-first");
        assertTrue(transmitter.enqueueGeneratorUpdates("e2e-synthetic", Collections.singletonList(before)));
        AtomicBoolean finished = new AtomicBoolean();
        long began = SystemClock.elapsedRealtime();
        transmitter.transmitWithCompletion(true, () -> finished.set(true));
        await("Blackhole fixture was never contacted", 10000, () -> fixture("/state").getInt("attempts") > 0);
        Bundle during = new Bundle(); during.putString("marker", "timeout-during");
        long diskBegan = SystemClock.elapsedRealtime();
        assertTrue(transmitter.enqueueGeneratorUpdates("e2e-synthetic", Collections.singletonList(during)));
        assertTrue("Blocked response must not block disk persistence", SystemClock.elapsedRealtime() - diskBegan < 3000);
        await("Upload call did not obey bounded timeout", 55000, finished::get);
        long elapsed = SystemClock.elapsedRealtime() - began;
        assertTrue("Test must exercise a real socket timeout", elapsed > 10000);
        assertTrue("Timeout must be bounded", elapsed < 55000);
        assertTrue("Timed-out payloads must survive", transmitter.pendingTransmissions() >= 2);
        fixture("/mode?status=201");
        await("Timeout recovery failed", 50000, () -> {
            transmitter.transmit(true);
            return received("marker", "timeout-first") && received("marker", "timeout-during");
        });
        transmitter.deinitialize(context());
    }
    @Test public void simultaneousUploaderDrainsSendOneCopyAndClearQueue() throws Exception {
        fixture("/reset"); fixture("/mode?delay=1");
        HttpTransmitter first = transmitter();
        HttpTransmitter second = transmitter();
        Bundle update = new Bundle(); update.putString("marker", "simultaneous-drain");
        assertTrue(first.enqueueGeneratorUpdates("e2e-synthetic", Collections.singletonList(update)));
        CountDownLatch release = new CountDownLatch(1);
        Thread a = new Thread(() -> { try { release.await(); first.transmit(true); } catch (InterruptedException e) { throw new AssertionError(e); } });
        Thread b = new Thread(() -> { try { release.await(); second.transmit(true); } catch (InterruptedException e) { throw new AssertionError(e); } });
        a.start(); b.start(); release.countDown(); a.join(); b.join();
        await("Concurrent drains did not finish", 15000, () -> first.pendingTransmissions() == 0);
        SystemClock.sleep(1500);
        JSONArray records = fixture("/state").getJSONArray("records");
        int copies = 0;
        for (int i = 0; i < records.length(); i++) {
            if ("simultaneous-drain".equals(records.getJSONObject(i).optString("marker"))) copies++;
        }
        assertEquals("Shared queue must not upload one file twice concurrently", 1, copies);
        assertEquals(0, second.pendingTransmissions());
        first.deinitialize(context()); second.deinitialize(context());
    }
}

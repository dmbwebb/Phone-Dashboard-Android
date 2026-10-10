package com.audacious_software.phone_dashboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.audacious_software.passive_data_kit.Toolbox;
import com.audacious_software.passive_data_kit.generators.Generators;
import com.audacious_software.passive_data_kit.transmitters.HttpTransmitter;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import static org.junit.Assert.*;

/** Run seed on v108, then recovery on v109 without clearing data between APK installs. */
@RunWith(AndroidJUnit4.class)
public class CompressionRecoveryInstrumentedTest {
    static final String ID = "E2E-COMPRESSION-RECOVERY";
    static final String MARKER = "queued-before-v109-upgrade";
    private Context context() { return ApplicationProvider.getApplicationContext(); }
    private SharedPreferences prefs() { return PreferenceManager.getDefaultSharedPreferences(context()); }
    private interface Check { boolean get() throws Exception; }
    private void await(String description, Check check) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check.get()) return;
            SystemClock.sleep(100);
        }
        fail(description);
    }
    static String config(boolean compressed) {
        return "{\"transmitters\":[{\"type\":\"pdk-http-transmitter\","
                + "\"upload-uri\":\"https://127.0.0.1:8766/upload\",\"compression\":" + compressed
                + ",\"strict-ssl-verification\":false,\"wifi-only\":false,\"charging-only\":false}],"
                + "\"generators\":[{\"identifier\":\"pdk-daily-usage-aggregate\",\"enabled\":false}]}";
    }
    private JSONObject fixture(String path) throws Exception {
        HttpURLConnection request = (HttpURLConnection) new URL("http://127.0.0.1:8765" + path).openConnection();
        request.setConnectTimeout(5000); request.setReadTimeout(5000);
        try (InputStream input = request.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return new JSONObject(output.toString("UTF-8"));
        } finally { request.disconnect(); }
    }
    private boolean received(String marker, String compression) throws Exception {
        JSONArray requests = fixture("/state").getJSONArray("requests");
        for (int i = 0; i < requests.length(); i++) {
            JSONObject request = requests.getJSONObject(i);
            if (!compression.equals(request.getString("compression"))) continue;
            JSONArray rows = request.getJSONArray("records");
            for (int j = 0; j < rows.length(); j++) {
                JSONObject row = rows.getJSONObject(j);
                if (marker.equals(row.optString("marker"))) {
                    assertEquals(ID, row.getJSONObject("passive-data-metadata").getString("source"));
                    return true;
                }
            }
        }
        return false;
    }
    private HttpTransmitter activeTransmitter() throws Exception {
        await("Expected exactly one active uploader", () -> Generators.getInstance(context()).activeTransmitters().size() == 1);
        return (HttpTransmitter) Generators.getInstance(context()).activeTransmitters().get(0);
    }
    private void enqueue(HttpTransmitter transmitter, String marker) {
        Bundle row = new Bundle(); row.putString("marker", marker);
        assertTrue(transmitter.enqueueGeneratorUpdates("e2e-compression", Collections.singletonList(row)));
    }

    @Test public void seedV108CompressedQueueAndReproduceCrash() throws Exception {
        assertEquals(108, context().getPackageManager().getPackageInfo(context().getPackageName(), 0).versionCode);
        assertTrue(prefs().edit()
                .putString("com.audacious_software.phone_dashboard.IDENTIFIER", ID)
                .putString("com.audacious_software.phone_dashboard.ROLE", "child")
                .putString(Schedule.SAVED_CONFIGURATION, config(true))
                .putString("monitoring_configuration_identity", ID)
                .putLong("monitoring_configuration_checked", System.currentTimeMillis()).commit());
        HttpTransmitter transmitter = new HttpTransmitter();
        HashMap<String, String> options = new HashMap<>();
        options.put(HttpTransmitter.USER_ID, ID);
        options.put(HttpTransmitter.UPLOAD_URI, "https://127.0.0.1:8766/upload");
        options.put(HttpTransmitter.COMPRESS_PAYLOADS, "true");
        options.put(HttpTransmitter.STRICT_SSL_VERIFICATION, "false");
        options.put(HttpTransmitter.WIFI_ONLY, "false");
        options.put(HttpTransmitter.CHARGING_ONLY, "false");
        transmitter.initialize(context(), options);
        enqueue(transmitter, MARKER);
        transmitter.transmit(true);
        SystemClock.sleep(10000);
        fail("Expected the original minified v108 native-library crash");
    }

    @Test public void upgradeRecoversCachedCompressionAndFetchesRevertedConfiguration() throws Exception {
        assertEquals(109, context().getPackageManager().getPackageInfo(context().getPackageName(), 0).versionCode);
        assertEquals(ID, ((AppApplication) context()).getIdentifier());
        assertTrue(new JSONObject(prefs().getString(Schedule.SAVED_CONFIGURATION, "{}"))
                .getJSONArray("transmitters").getJSONObject(0).getBoolean("compression"));
        Schedule schedule = Schedule.getInstance(context());
        // onCreate already resumed cached monitoring; a second resume must be idempotent.
        schedule.resumeMonitoring();
        HttpTransmitter compressed = activeTransmitter();
        compressed.transmit(true);
        await("The queued pre-upgrade record was not delivered as gzip", () -> received(MARKER, "gzip"));
        await("Successful compressed send must clear durable queue", () -> compressed.pendingTransmissions() == 0);

        fixture("/mode?compression=false");
        assertTrue("Latest configuration must download successfully", schedule.refreshMonitoringConfiguration());
        assertFalse(new JSONObject(prefs().getString(Schedule.SAVED_CONFIGURATION, "{}"))
                .getJSONArray("transmitters").getJSONObject(0).getBoolean("compression"));
        HttpTransmitter plain = activeTransmitter();
        assertNotSame("New configuration replaces the uploader", compressed, plain);
        enqueue(plain, "after-config-revert");
        plain.transmit(true);
        await("Upload after revert must be uncompressed", () -> received("after-config-revert", "none"));
        await("Successful normal send must clear durable queue", () -> plain.pendingTransmissions() == 0);
    }

    @Test public void retainedNativeBindingsWorkInMinifiedRelease() {
        assertEquals("aGVsbG8=", Toolbox.encodeBase64("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)).trim());
        assertEquals(24, Toolbox.randomNonce().length);
        assertEquals("m3HSJL1i83hdltRq0+o9czGb+8KJDKra4t/3JRlnPKcjI8PZm6XBHXx6zG4UuMXaDEZjR1wuXDre9G9zvN7AQw==",
                Toolbox.hash("hello").replaceAll("\\s", ""));
    }

    @Test public void prepareQueuedCompressedPayloadForNormalColdStart() throws Exception {
        fixture("/mode?compression=true");
        Schedule schedule = Schedule.getInstance(context());
        assertTrue(schedule.refreshMonitoringConfiguration());
        HttpTransmitter transmitter = activeTransmitter();
        ((android.app.job.JobScheduler) context().getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancelAll();
        transmitter.deinitialize(context());
        // Stage durably without racing the old uploader's initial drain or a
        // persisted retry. The next normal app process uses its real uploader.
        HttpTransmitter dormant = new HttpTransmitter() {
            @Override public synchronized boolean transmit(boolean force) { return false; }
        };
        HashMap<String, String> options = new HashMap<>();
        options.put(HttpTransmitter.USER_ID, ID);
        options.put(HttpTransmitter.UPLOAD_URI, "https://127.0.0.1:8766/upload");
        options.put(HttpTransmitter.COMPRESS_PAYLOADS, "true");
        options.put(HttpTransmitter.STRICT_SSL_VERIFICATION, "false");
        options.put(HttpTransmitter.WIFI_ONLY, "false");
        options.put(HttpTransmitter.CHARGING_ONLY, "false");
        dormant.initialize(context(), options);
        enqueue(dormant, "normal-application-cold-start");
        dormant.deinitialize(context());
        assertTrue(new JSONObject(prefs().getString(Schedule.SAVED_CONFIGURATION, "{}"))
                .getJSONArray("transmitters").getJSONObject(0).getBoolean("compression"));
    }
}

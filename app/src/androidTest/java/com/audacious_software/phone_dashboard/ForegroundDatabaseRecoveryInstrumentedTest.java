package com.audacious_software.phone_dashboard;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.PreferenceManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.audacious_software.passive_data_kit.PassiveDataKit;
import com.audacious_software.passive_data_kit.generators.Generators;
import com.audacious_software.passive_data_kit.generators.device.DailyUsageAggregateGenerator;
import com.audacious_software.passive_data_kit.generators.device.ForegroundApplication;
import com.audacious_software.passive_data_kit.generators.device.UsageStatsGenerator;
import com.audacious_software.passive_data_kit.transmitters.HttpTransmitter;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;

import static org.junit.Assert.*;

/** Staged by the driver: v109 seed, retained-data v111 update, then normal cold start. */
@RunWith(AndroidJUnit4.class)
public class ForegroundDatabaseRecoveryInstrumentedTest {
    public static final String MARKER = "queued-before-v111-database-recovery";
    private Context context() { return ApplicationProvider.getApplicationContext(); }
    private File database() {
        return new File(PassiveDataKit.getGeneratorsStorage(context()), "pdk-foreground-application.sqlite");
    }
    private File usageDatabase() {
        return new File(PassiveDataKit.getGeneratorsStorage(context()), "pdk-usage-stats.sqlite");
    }

    private enum ExtraDatabase {
        EVENT("pdk-app-event.sqlite", 1, 0, "history", com.audacious_software.passive_data_kit.R.string.pdk_generator_app_events_create_history_table,
                "event_name", "retained-fixture-event"),
        BATTERY("pdk-device-battery.sqlite", 1, -1, "history", com.audacious_software.passive_data_kit.R.string.pdk_generator_device_battery_create_history_table,
                "technology", "retained-fixture-battery"),
        SCREEN("pdk-screen-state.sqlite", 2, 1, "history", com.audacious_software.passive_data_kit.R.string.pdk_generator_screen_state_create_history_table,
                "state", "on"),
        DAILY("pdk-daily-usage-aggregate.sqlite", 2, 1, "history", com.audacious_software.passive_data_kit.R.string.pdk_generator_daily_usage_aggregate_create_history_table,
                "package", "retained.synthetic.app"),
        SYSTEM("pdk-system-status.sqlite", 4, 2, "history", com.audacious_software.passive_data_kit.R.string.pdk_generator_diagnostics_system_status_create_history_table,
                "storage_path", "/retained-fixture"),
        USER("pdk-user.sqlite", 2, 1, "history", com.audacious_software.passive_data_kit.R.string.pdk_generator_users_create_history_table,
                "mode", "foreground"),
        NOTIFICATION("pdk-notification-events.sqlite", 1, -1, "history", com.audacious_software.passive_data_kit.R.string.pdk_generator_notification_events_create_history_table,
                "package", "retained.synthetic.app"),
        BUDGET("daily-app-budget.sqlite", 1, -1, "history", R.string.generator_daily_app_budget_create_history_table,
                "budget", "{\"retained.synthetic.app\":60000}"),
        SNOOZE("app-snooze.sqlite", 2, 1, "history", R.string.generator_app_snooze_create_history_table,
                "app_package", "retained.synthetic.app"),
        DELAY("snooze-delay.sqlite", 1, 0, "history", R.string.generator_snooze_delay_create_history_table,
                "snooze_delay", "30000"),
        SURVEY("daily-survey-response.sqlite", 3, 2, "responses", R.string.generator_daily_survey_create_responses_table,
                "survey_id", "retained-fixture-survey");

        final String file, table, column, value;
        final int version, marker, create;
        ExtraDatabase(String file, int version, int marker, String table, int create, String column, String value) {
            this.file = file; this.version = version; this.marker = marker; this.table = table;
            this.create = create; this.column = column; this.value = value;
        }
    }

    private File database(ExtraDatabase fixture) {
        return new File(PassiveDataKit.getGeneratorsStorage(context()), fixture.file);
    }

    private void seedAdditionalInterruptedSchemas(long observed) {
        for (ExtraDatabase fixture : ExtraDatabase.values()) {
            assertFalse("Fixture requires a fresh database: " + fixture.file, database(fixture).exists());
            try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(database(fixture), null)) {
                db.execSQL("CREATE TABLE metadata(key TEXT, value TEXT, last_updated INTEGER)");
                setInterruptedMarker(db, fixture);
                db.execSQL(context().getString(fixture.create));
                db.execSQL("INSERT INTO " + fixture.table + "(_id, observed, fetched, transmitted, " + fixture.column
                        + ") VALUES (424242, ?, 7, 0, ?)", new Object[]{observed, fixture.value});
                if (fixture == ExtraDatabase.DAILY) {
                    db.execSQL("ALTER TABLE history ADD pending_delivery INTEGER NOT NULL DEFAULT 0");
                    db.execSQL("UPDATE history SET day_bucket=?, total_ms=60000, pending_delivery=1", new Object[]{observed});
                } else if (fixture == ExtraDatabase.SYSTEM) {
                    db.execSQL(context().getString(com.audacious_software.passive_data_kit.R.string.pdk_generator_diagnostics_system_status_history_table_add_system_runtime));
                    db.execSQL(context().getString(com.audacious_software.passive_data_kit.R.string.pdk_generator_diagnostics_system_status_history_table_add_gps_enabled));
                    db.execSQL("UPDATE history SET system_runtime=1234, gps_enabled=1");
                } else if (fixture == ExtraDatabase.SNOOZE) {
                    db.execSQL(context().getString(R.string.generator_app_snooze_add_original_budget));
                    db.execSQL("UPDATE history SET duration=60000, original_budget=12.5");
                } else if (fixture == ExtraDatabase.SURVEY) {
                    db.execSQL("UPDATE responses SET other_text='retained fixture answer', response_values='[2]', dismissed=0");
                } else if (fixture == ExtraDatabase.BUDGET || fixture == ExtraDatabase.DELAY) {
                    db.execSQL("UPDATE history SET effective_on=?", new Object[]{observed});
                }
            }
        }
    }

    private void setInterruptedMarker(SQLiteDatabase db, ExtraDatabase fixture) {
        db.execSQL("DELETE FROM metadata WHERE key='version'");
        if (fixture.marker >= 0) db.execSQL("INSERT INTO metadata VALUES ('version', ?, 1)", new Object[]{fixture.marker});
    }

    private void assertAdditionalSchemasRetained(long observed) {
        // Observe normal asynchronous startup; do not start collectors out of order from the test.
        long deadline = SystemClock.elapsedRealtime() + 60000;
        while (Generators.getInstance(context()).activeGenerators().size() < 12
                && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100);
        }
        assertEquals("All twelve enabled collectors must finish normal startup", 12,
                Generators.getInstance(context()).activeGenerators().size());
        assertFalse("The local fixture disables aggregate collection", DailyUsageAggregateGenerator.isEnabled(context()));
        // Aggregation is disabled in this isolated fixture; its database constructor is still checked.
        DailyUsageAggregateGenerator.getInstance(context());
        for (ExtraDatabase fixture : ExtraDatabase.values()) {
            try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(database(fixture), null);
                 Cursor version = db.rawQuery("SELECT value FROM metadata WHERE key='version'", null);
                 Cursor row = db.rawQuery("SELECT * FROM " + fixture.table + " WHERE _id=424242", null)) {
                assertTrue(fixture.file, version.moveToFirst());
                assertEquals(fixture.file, fixture.version, version.getInt(0));
                assertFalse(fixture.file, version.moveToNext());
                assertEquals(fixture.file, 1, row.getCount()); assertTrue(row.moveToFirst());
                assertEquals(fixture.file, observed, row.getLong(row.getColumnIndexOrThrow("observed")));
                assertEquals(fixture.file, fixture.value, row.getString(row.getColumnIndexOrThrow(fixture.column)));
                assertEquals(fixture.file, 7, row.getInt(row.getColumnIndexOrThrow("fetched")));
                assertEquals(fixture.file, 0, row.getInt(row.getColumnIndexOrThrow("transmitted")));
                if (fixture == ExtraDatabase.DAILY) {
                    // Startup can already have durably queued this pending row. The
                    // host requires its delivery, then checks the cleared flag below.
                    int pending = row.getInt(row.getColumnIndexOrThrow("pending_delivery"));
                    assertTrue(pending == 0 || pending == 1);
                    assertEquals(60000, row.getLong(row.getColumnIndexOrThrow("total_ms")));
                } else if (fixture == ExtraDatabase.SYSTEM) {
                    assertEquals(1234, row.getLong(row.getColumnIndexOrThrow("system_runtime")));
                    assertEquals(1, row.getInt(row.getColumnIndexOrThrow("gps_enabled")));
                    assertTrue(row.isNull(row.getColumnIndexOrThrow("network_enabled")));
                    assertTrue(row.isNull(row.getColumnIndexOrThrow("pending_transmissions")));
                } else if (fixture == ExtraDatabase.USER) {
                    assertTrue(row.isNull(row.getColumnIndexOrThrow("identifier")));
                } else if (fixture == ExtraDatabase.SNOOZE) {
                    assertEquals(12.5, row.getDouble(row.getColumnIndexOrThrow("original_budget")), 0);
                    assertTrue(row.isNull(row.getColumnIndexOrThrow("remaining_budget")));
                } else if (fixture == ExtraDatabase.SURVEY) {
                    assertEquals("retained fixture answer", row.getString(row.getColumnIndexOrThrow("other_text")));
                    assertEquals("[2]", row.getString(row.getColumnIndexOrThrow("response_values")));
                }
            }
        }
    }

    @Test public void seedInterruptedV109DatabaseAndConfirmCrash() throws Exception {
        assertEquals(109, context().getPackageManager().getPackageInfo(context().getPackageName(), 0).versionCode);
        assertFalse("Fixture requires a fresh synthetic installation", database().exists());
        assertFalse("Fixture requires a fresh usage-event database", usageDatabase().exists());
        long observed = System.currentTimeMillis() - 60000;
        assertTrue(PreferenceManager.getDefaultSharedPreferences(context()).edit()
                .putLong("database_recovery_fixture_observed", observed).commit());
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(database(), null)) {
            db.execSQL("CREATE TABLE metadata(key TEXT, value TEXT, last_updated INTEGER)");
            db.execSQL("CREATE TABLE history(_id INTEGER PRIMARY KEY AUTOINCREMENT, fetched INTEGER, transmitted INTEGER, observed INTEGER, application TEXT)");
            db.execSQL("INSERT INTO history(observed, application, transmitted) VALUES (?, 'retained.synthetic.app', 0)",
                    new Object[]{observed});
            db.execSQL("CREATE TABLE substitutes(package TEXT PRIMARY KEY, substitute TEXT)");
            db.execSQL("INSERT INTO substitutes VALUES ('retained.synthetic.app', 'retained substitute')");
        }
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(usageDatabase(), null)) {
            db.execSQL("CREATE TABLE metadata(key TEXT, value TEXT, last_updated INTEGER)");
            db.execSQL("CREATE TABLE history(_id INTEGER PRIMARY KEY AUTOINCREMENT, fetched INTEGER, transmitted INTEGER, observed INTEGER, event_type TEXT, package TEXT, extras TEXT)");
            db.execSQL("INSERT INTO history(observed, event_type, package, transmitted) VALUES (?, 'activity-resumed', 'retained.synthetic.app', 0)",
                    new Object[]{observed});
        }
        seedAdditionalInterruptedSchemas(observed);
        try {
            ForegroundApplication.getInstance(context());
            fail("v109 should reproduce the reported partial-schema failure");
        } catch (SQLiteException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("table history already exists"));
        }
        try {
            UsageStatsGenerator.getInstance(context());
            fail("v109 should reproduce the interrupted raw-event database failure");
        } catch (SQLiteException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("table history already exists"));
        }
        Bundle evidence = new Bundle();
        evidence.putLong("database_fixture_observed", observed);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, evidence);
    }

    @Test public void deliveredAggregateClearsPendingFlag() {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        do {
            try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(database(ExtraDatabase.DAILY), null);
                 Cursor row = db.rawQuery("SELECT pending_delivery, total_ms, observed FROM history WHERE _id=424242", null)) {
                assertTrue("Delivered row must remain in history", row.moveToFirst());
                assertEquals(60000, row.getLong(1));
                assertEquals(PreferenceManager.getDefaultSharedPreferences(context())
                        .getLong("database_recovery_fixture_observed", -1), row.getLong(2));
                if (row.getInt(0) == 0) return;
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Delivered retained aggregate still marked pending");
    }

    @Test public void upgradedDatabaseKeepsHistoryAndCanReopen() throws Exception {
        assertEquals(111, context().getPackageManager().getPackageInfo(context().getPackageName(), 0).versionCode);
        assertTrue("Upgrade must retain the pre-existing database", database().exists());
        ForegroundApplication first = ForegroundApplication.getInstance(context());
        assertSame(first, ForegroundApplication.getInstance(context()));
        try (Cursor rows = first.queryHistory(null, "application = ?", new String[]{"retained.synthetic.app"}, null)) {
            assertEquals(1, rows.getCount());
            assertTrue(rows.moveToFirst());
            assertEquals(PreferenceManager.getDefaultSharedPreferences(context())
                            .getLong("database_recovery_fixture_observed", -1),
                    rows.getLong(rows.getColumnIndexOrThrow("observed")));
            assertEquals("retained.synthetic.app", rows.getString(rows.getColumnIndexOrThrow("application")));
            assertEquals(0, rows.getInt(rows.getColumnIndexOrThrow("transmitted")));
            for (String column : new String[]{"duration", "screen_active", "display_state", "is_home", "category"}) {
                assertTrue("Missing migrated column " + column, rows.getColumnIndex(column) >= 0);
            }
        }
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(database(), null);
             Cursor rows = db.rawQuery("SELECT value FROM metadata WHERE key='version'", null)) {
            assertTrue(rows.moveToFirst());
            assertEquals("7", rows.getString(0));
            try (Cursor substitute = db.rawQuery("SELECT substitute FROM substitutes WHERE package='retained.synthetic.app'", null)) {
                assertTrue(substitute.moveToFirst()); assertEquals("retained substitute", substitute.getString(0));
            }
        }
        UsageStatsGenerator usage = UsageStatsGenerator.getInstance(context());
        try (Cursor rows = usage.queryHistory(null, "package = ?", new String[]{"retained.synthetic.app"}, null)) {
            assertEquals(1, rows.getCount());
            assertTrue(rows.moveToFirst());
            assertEquals(PreferenceManager.getDefaultSharedPreferences(context())
                            .getLong("database_recovery_fixture_observed", -1),
                    rows.getLong(rows.getColumnIndexOrThrow("observed")));
            assertEquals("activity-resumed", rows.getString(rows.getColumnIndexOrThrow("event_type")));
            assertEquals(0, rows.getInt(rows.getColumnIndexOrThrow("transmitted")));
        }
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(usageDatabase(), null);
             Cursor rows = db.rawQuery("SELECT value FROM metadata WHERE key='version'", null)) {
            assertTrue(rows.moveToFirst());
            assertEquals("1", rows.getString(0));
        }
        assertAdditionalSchemasRetained(PreferenceManager.getDefaultSharedPreferences(context())
                .getLong("database_recovery_fixture_observed", -1));
    }

    @Test public void stageRetainedUploadBeforeUpgrade() throws Exception {
        // Identity and queue are staged after the deliberate v109 crash. The
        // next upgraded Application must keep this database and deliver the record.
        // Restore the interrupted markers if v109's crash logging initialized a database.
        // All synthetic rows remain in place; neither history nor the original queue is cleared.
        for (ExtraDatabase fixture : ExtraDatabase.values()) {
            try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(database(fixture), null)) {
                setInterruptedMarker(db, fixture);
            }
        }
        assertTrue(PreferenceManager.getDefaultSharedPreferences(context()).edit()
                .putString("com.audacious_software.phone_dashboard.IDENTIFIER", CompressionRecoveryInstrumentedTest.ID)
                .putString("com.audacious_software.phone_dashboard.ROLE", "child")
                .putString(Schedule.SAVED_CONFIGURATION, CompressionRecoveryInstrumentedTest.config(false))
                .putString("monitoring_configuration_identity", CompressionRecoveryInstrumentedTest.ID)
                .putLong("monitoring_configuration_checked", System.currentTimeMillis()).commit());
        HttpTransmitter transmitter = new HttpTransmitter();
        HashMap<String, String> options = new HashMap<>();
        options.put(HttpTransmitter.USER_ID, CompressionRecoveryInstrumentedTest.ID);
        options.put(HttpTransmitter.UPLOAD_URI, "https://127.0.0.1:8766/upload");
        options.put(HttpTransmitter.STRICT_SSL_VERIFICATION, "false");
        options.put(HttpTransmitter.WIFI_ONLY, "false");
        options.put(HttpTransmitter.CHARGING_ONLY, "false");
        transmitter.initialize(context(), options);
        Bundle record = new Bundle();
        record.putString("marker", MARKER);
        assertTrue(transmitter.enqueueGeneratorUpdates("e2e-database-recovery", Collections.singletonList(record)));
        transmitter.deinitialize(context());
    }
}

package com.audacious_software.phone_dashboard;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import com.audacious_software.passive_data_kit.PassiveDataKit;
import com.audacious_software.passive_data_kit.R;
import com.audacious_software.passive_data_kit.generators.Generator;
import com.audacious_software.passive_data_kit.generators.device.Battery;
import com.audacious_software.passive_data_kit.generators.device.DailyUsageAggregateGenerator;
import com.audacious_software.passive_data_kit.generators.device.NotificationEvents;
import com.audacious_software.passive_data_kit.generators.device.ScreenState;
import com.audacious_software.passive_data_kit.generators.device.User;
import com.audacious_software.passive_data_kit.generators.diagnostics.AppEvent;
import com.audacious_software.passive_data_kit.generators.diagnostics.SystemStatus;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Calls the actual initializer of every remaining enabled PDK database without starting timers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = android.app.Application.class)
public class PdkDatabaseRecoveryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Context context = RuntimeEnvironment.getApplication();

    private enum Schema {
        EVENT(AppEvent.class, 1, R.string.pdk_generator_app_events_create_history_table,
                "event_name", "'retained-event'"),
        BATTERY(Battery.class, 1, R.string.pdk_generator_device_battery_create_history_table,
                "technology", "'retained-battery'"),
        SCREEN(ScreenState.class, 2, R.string.pdk_generator_screen_state_create_history_table,
                "state", "'on'"),
        NOTIFICATION(NotificationEvents.class, 1, R.string.pdk_generator_notification_events_create_history_table,
                "package", "'retained.app'"),
        USER(User.class, 2, R.string.pdk_generator_users_create_history_table,
                "mode", "'foreground'", R.string.pdk_generator_users_history_table_add_identifier),
        SYSTEM(SystemStatus.class, 4, R.string.pdk_generator_diagnostics_system_status_create_history_table,
                "storage_path", "'/retained'",
                R.string.pdk_generator_diagnostics_system_status_history_table_add_system_runtime,
                R.string.pdk_generator_diagnostics_system_status_history_table_add_gps_enabled,
                R.string.pdk_generator_diagnostics_system_status_history_table_add_network_enabled,
                R.string.pdk_generator_diagnostics_system_status_history_table_add_pending_transmissions),
        DAILY(DailyUsageAggregateGenerator.class, 2, R.string.pdk_generator_daily_usage_aggregate_create_history_table,
                "package", "'retained.app'");

        final Class<? extends Generator> type;
        final int version, create;
        final String column, value;
        final int[] alters;
        Schema(Class<? extends Generator> type, int version, int create, String column, String value, int... alters) {
            this.type = type; this.version = version; this.create = create;
            this.column = column; this.value = value; this.alters = alters;
        }
        int boundaries() { return this == DAILY ? 1 : alters.length; }
    }

    private SQLiteDatabase open(File file) { return SQLiteDatabase.openOrCreateDatabase(file, null); }
    private void metadata(SQLiteDatabase db, int marker) {
        if (marker >= -1) {
            db.execSQL("CREATE TABLE metadata(key TEXT, value TEXT, last_updated INTEGER)");
            if (marker >= 0) db.execSQL("INSERT INTO metadata VALUES ('version', ?, 1)", new Object[]{marker});
        }
    }
    private File seed(Schema schema, int marker, int boundary) throws Exception {
        File file = new File(temporary.newFolder(), "fixture.sqlite");
        try (SQLiteDatabase db = open(file)) {
            metadata(db, marker);
            if (boundary >= 0) {
                db.execSQL(context.getString(schema.create));
                db.execSQL("INSERT INTO history(_id, fetched, transmitted, observed, " + schema.column
                        + ") VALUES (42, 7, 8, 123456789, " + schema.value + ")");
                for (int step = 0; step < boundary; step++) {
                    if (schema == Schema.DAILY) {
                        db.execSQL("ALTER TABLE history ADD pending_delivery INTEGER NOT NULL DEFAULT 0");
                        db.execSQL("UPDATE history SET pending_delivery=1");
                    } else {
                        db.execSQL(context.getString(schema.alters[step]));
                    }
                }
                if (schema == Schema.DAILY) db.execSQL("UPDATE history SET day_bucket=123400000, total_ms=60000");
            }
        }
        return file;
    }
    private Generator generator(Schema schema, SQLiteDatabase db) throws Exception {
        // No constructor side effects/retention pruning: the real migration method and helper run.
        Generator generator = mock(schema.type, CALLS_REAL_METHODS);
        Field contextField = Generator.class.getDeclaredField("mContext");
        contextField.setAccessible(true); contextField.set(generator, context);
        Field database = schema.type.getDeclaredField("mDatabase");
        database.setAccessible(true); database.set(generator, db);
        return generator;
    }
    private void invoke(Generator generator, Class<?> type, String name) throws Exception {
        Method method = type.getDeclaredMethod(name); method.setAccessible(true);
        try { method.invoke(generator); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }
    private void migrate(Schema schema, File file) throws Exception {
        SQLiteDatabase db = open(file);
        try { invoke(generator(schema, db), schema.type, "initializeDatabase"); }
        catch (RuntimeException | Error failure) {
            assertFalse("Failed migration must close its handle", db.isOpen());
            throw failure;
        } finally { if (db.isOpen()) db.close(); }
    }
    private int version(SQLiteDatabase db) {
        try (Cursor rows = db.rawQuery("SELECT value FROM metadata WHERE key='version'", null)) {
            assertTrue(rows.moveToFirst()); int value = rows.getInt(0); assertFalse(rows.moveToNext()); return value;
        }
    }
    private List<String> snapshot(SQLiteDatabase db, String table) {
        List<String> result = new ArrayList<>();
        try (Cursor rows = db.rawQuery("SELECT * FROM " + table + " ORDER BY _id", null)) {
            for (String column : rows.getColumnNames()) result.add(column);
            while (rows.moveToNext()) for (int i = 0; i < rows.getColumnCount(); i++) {
                result.add(rows.isNull(i) ? null : rows.getString(i));
            }
        }
        return result;
    }
    private boolean table(SQLiteDatabase db, String name) {
        try (Cursor rows = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", new String[]{name})) {
            return rows.moveToFirst();
        }
    }
    private boolean column(SQLiteDatabase db, String name) {
        try (Cursor rows = db.rawQuery("PRAGMA table_info(history)", null)) {
            while (rows.moveToNext()) if (name.equals(rows.getString(rows.getColumnIndexOrThrow("name")))) return true;
            return false;
        }
    }

    @Test public void sevenCurrentHistoriesSurviveMissingEmptyAndStaleMetadata() throws Exception {
        for (Schema schema : Schema.values()) for (int marker : new int[]{-2, -1, 0}) {
            File file = seed(schema, marker, schema.boundaries());
            List<String> expected;
            try (SQLiteDatabase db = open(file)) { expected = snapshot(db, "history"); }
            migrate(schema, file); migrate(schema, file);
            try (SQLiteDatabase db = open(file)) {
                assertEquals(schema.name(), schema.version, version(db));
                assertEquals(schema.name(), expected, snapshot(db, "history"));
            }
        }
    }

    @Test public void sevenFreshSchemasAndCurrentFutureMarkersRemainStable() throws Exception {
        for (Schema schema : Schema.values()) {
            File fresh = seed(schema, -2, -1);
            migrate(schema, fresh);
            try (SQLiteDatabase db = open(fresh)) {
                assertEquals(schema.version, version(db)); assertTrue(table(db, "history"));
            }
            for (int marker : new int[]{schema.version, schema.version + 1}) {
                File file = seed(schema, marker, schema.boundaries());
                List<String> expected;
                try (SQLiteDatabase db = open(file)) { expected = snapshot(db, "history"); }
                migrate(schema, file); migrate(schema, file);
                try (SQLiteDatabase db = open(file);
                     Cursor rows = db.rawQuery("SELECT last_updated FROM metadata WHERE key='version'", null)) {
                    assertEquals(marker, version(db)); assertTrue(rows.moveToFirst()); assertEquals(1, rows.getLong(0));
                    assertEquals(expected, snapshot(db, "history"));
                }
            }
        }
    }

    @Test public void userAndSystemResumeEveryAlterBoundaryIncludingBetweenLocationColumns() throws Exception {
        for (Schema schema : new Schema[]{Schema.USER, Schema.SYSTEM}) {
            for (int marker = -2; marker < schema.version; marker++) {
                for (int boundary = 0; boundary <= schema.boundaries(); boundary++) {
                    File file = seed(schema, marker, boundary);
                    // Give pre-existing optional columns values so a rebuild cannot masquerade as recovery.
                    String[] optional = schema == Schema.USER ? new String[]{"identifier"}
                            : new String[]{"system_runtime", "gps_enabled", "network_enabled", "pending_transmissions"};
                    try (SQLiteDatabase db = open(file)) {
                        for (int i = 0; i < boundary; i++) db.execSQL("UPDATE history SET " + optional[i] + "=99");
                    }
                    migrate(schema, file);
                    try (SQLiteDatabase db = open(file); Cursor row = db.rawQuery("SELECT * FROM history", null)) {
                        assertEquals(schema.version, version(db)); assertEquals(1, row.getCount()); assertTrue(row.moveToFirst());
                        assertEquals(42, row.getInt(row.getColumnIndexOrThrow("_id")));
                        assertEquals(8, row.getInt(row.getColumnIndexOrThrow("transmitted")));
                        assertEquals(123456789, row.getLong(row.getColumnIndexOrThrow("observed")));
                        for (int i = 0; i < optional.length; i++) {
                            int index = row.getColumnIndexOrThrow(optional[i]);
                            if (i < boundary) assertEquals(99, row.getInt(index)); else assertTrue(row.isNull(index));
                        }
                    }
                }
            }
        }
    }

    @Test public void dailyDeliveryFlagsAndUniqueKeySurviveBothBoundaries() throws Exception {
        for (int marker : new int[]{-2, -1, 0, 1}) for (int boundary : new int[]{0, 1}) {
            File file = seed(Schema.DAILY, marker, boundary);
            migrate(Schema.DAILY, file); migrate(Schema.DAILY, file);
            try (SQLiteDatabase db = open(file);
                 Cursor row = db.rawQuery("SELECT pending_delivery, total_ms, transmitted FROM history", null)) {
                assertTrue(row.moveToFirst()); assertEquals(boundary, row.getInt(0));
                assertEquals(60000, row.getLong(1)); assertEquals(8, row.getInt(2));
                db.execSQL("INSERT INTO history(day_bucket, package, total_ms) VALUES (123400000, 'retained.app', 70000)");
                try (Cursor count = db.rawQuery("SELECT count(*), total_ms FROM history", null)) {
                    assertTrue(count.moveToFirst()); assertEquals(1, count.getInt(0)); assertEquals(70000, count.getLong(1));
                }
            }
        }
    }

    @Test public void currentScreenAtLegacyMarkerRetainsRowsWithoutArchive() throws Exception {
        File file = seed(Schema.SCREEN, 1, 0);
        List<String> expected;
        try (SQLiteDatabase db = open(file)) { expected = snapshot(db, "history"); }
        migrate(Schema.SCREEN, file);
        try (SQLiteDatabase db = open(file)) {
            assertEquals(expected, snapshot(db, "history")); assertFalse(table(db, "history_legacy_v1"));
        }
    }

    @Test public void incompatibleScreenIsArchivedWithoutCollisionOrRepeatedReset() throws Exception {
        File file = seed(Schema.SCREEN, 1, -1);
        try (SQLiteDatabase db = open(file)) {
            db.execSQL("CREATE TABLE history(_id INTEGER PRIMARY KEY, legacy_value BLOB)");
            db.execSQL("INSERT INTO history VALUES (42, X'010203')");
            db.execSQL("CREATE TABLE history_legacy_v1(_id INTEGER, old_evidence TEXT)");
            db.execSQL("INSERT INTO history_legacy_v1 VALUES (5, 'older')");
        }
        migrate(Schema.SCREEN, file); migrate(Schema.SCREEN, file);
        try (SQLiteDatabase db = open(file);
             Cursor row = db.rawQuery("SELECT _id, hex(legacy_value) FROM history_legacy_v1_1", null);
             Cursor previous = db.rawQuery("SELECT old_evidence FROM history_legacy_v1", null)) {
            assertTrue(row.moveToFirst()); assertEquals(42, row.getInt(0)); assertEquals("010203", row.getString(1));
            assertTrue(previous.moveToFirst()); assertEquals("older", previous.getString(0));
            assertTrue(column(db, "state")); assertFalse(table(db, "history_legacy_v1_2"));
        }
    }

    @Test public void refusedInsertAndUpdateRollBackAllAddedColumnsAndAllowRetry() throws Exception {
        for (int marker : new int[]{-1, 1}) {
            File file = seed(Schema.SYSTEM, marker, 1);
            List<String> expected;
            try (SQLiteDatabase db = open(file)) {
                expected = snapshot(db, "history");
                db.execSQL("CREATE TRIGGER refuse_version BEFORE " + (marker == -1 ? "INSERT" : "UPDATE")
                        + " ON metadata BEGIN SELECT RAISE(IGNORE); END");
            }
            assertThrows(SQLiteException.class, () -> migrate(Schema.SYSTEM, file));
            try (SQLiteDatabase db = open(file)) {
                assertEquals(expected, snapshot(db, "history")); assertFalse(column(db, "gps_enabled"));
                db.execSQL("DROP TRIGGER refuse_version");
            }
            migrate(Schema.SYSTEM, file);
            try (SQLiteDatabase db = open(file)) { assertEquals(4, version(db)); }
        }
    }

    @Test public void refusedScreenMarkerRollsBackArchiveForBothInsertAndUpdate() throws Exception {
        for (int marker : new int[]{-1, 1}) {
            File file = seed(Schema.SCREEN, marker, -1);
            try (SQLiteDatabase db = open(file)) {
                db.execSQL("CREATE TABLE history(_id INTEGER PRIMARY KEY, legacy_value TEXT)");
                db.execSQL("INSERT INTO history VALUES (42, 'untouched')");
                db.execSQL("CREATE TRIGGER refuse_version BEFORE " + (marker == -1 ? "INSERT" : "UPDATE")
                        + " ON metadata BEGIN SELECT RAISE(IGNORE); END");
            }
            assertThrows(SQLiteException.class, () -> migrate(Schema.SCREEN, file));
            try (SQLiteDatabase db = open(file)) {
                assertTrue(column(db, "legacy_value")); assertFalse(column(db, "state"));
                assertFalse(table(db, "history_legacy_v" + Math.max(0, marker)));
                db.execSQL("DROP TRIGGER refuse_version");
            }
            migrate(Schema.SCREEN, file);
            try (SQLiteDatabase db = open(file)) { assertEquals(2, version(db)); }
        }
    }

    @Test public void appEventFailedMarkerClosesDatabaseAndClearsWorkingFlag() throws Exception {
        File directory = temporary.newFolder();
        File file = new File(directory, "pdk-app-event.sqlite");
        try (SQLiteDatabase db = open(file)) {
            metadata(db, -1);
            db.execSQL("CREATE TRIGGER refuse_version BEFORE INSERT ON metadata BEGIN SELECT RAISE(IGNORE); END");
        }
        Generator event = generator(Schema.EVENT, null);
        try (MockedStatic<PassiveDataKit> pdk = mockStatic(PassiveDataKit.class)) {
            pdk.when(() -> PassiveDataKit.getGeneratorsStorage(any(Context.class))).thenReturn(directory);
            assertThrows(SQLiteException.class, () -> invoke(event, AppEvent.class, "openDatabase"));
        }
        Field working = AppEvent.class.getDeclaredField("mWorking"); working.setAccessible(true);
        assertFalse(working.getBoolean(event));
        Field database = AppEvent.class.getDeclaredField("mDatabase"); database.setAccessible(true);
        assertFalse(((SQLiteDatabase) database.get(event)).isOpen());
        try (SQLiteDatabase db = open(file)) { assertFalse(table(db, "history")); }
    }

    @Test public void failedSchemaCreationAlsoRollsBackNewMetadataTable() throws Exception {
        File file = seed(Schema.BATTERY, -2, -1);
        try (SQLiteDatabase db = open(file)) {
            db.execSQL("CREATE VIEW history AS SELECT 1 AS retained_evidence");
        }
        assertThrows(SQLiteException.class, () -> migrate(Schema.BATTERY, file));
        try (SQLiteDatabase db = open(file); Cursor view = db.rawQuery("SELECT retained_evidence FROM history", null)) {
            assertFalse(table(db, "metadata")); assertTrue(view.moveToFirst()); assertEquals(1, view.getInt(0));
            db.execSQL("DROP VIEW history");
        }
        migrate(Schema.BATTERY, file);
        try (SQLiteDatabase db = open(file)) { assertEquals(1, version(db)); }
    }
}

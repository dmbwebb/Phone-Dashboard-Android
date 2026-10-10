package com.audacious_software.phone_dashboard;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.Bundle;

import com.audacious_software.passive_data_kit.PassiveDataKit;
import com.audacious_software.passive_data_kit.generators.Generator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = android.app.Application.class)
public class AppGeneratorDatabaseRecoveryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Context context = RuntimeEnvironment.getApplication();

    private enum Schema {
        BUDGET(DailyBudgetGenerator.class, "daily-app-budget.sqlite", "history", 1,
                R.string.generator_daily_app_budget_create_history_table,
                "INSERT INTO history VALUES (42, 7, 8, 123456789, 123400000, '{\"retained.app\":60000}')"),
        SNOOZE(AppSnoozeGenerator.class, "app-snooze.sqlite", "history", 2,
                R.string.generator_app_snooze_create_history_table,
                "INSERT INTO history VALUES (42, 7, 8, 123456789, 60000, 'retained.app', 20.5, 10.25)"),
        DELAY(SnoozeDelayGenerator.class, "snooze-delay.sqlite", "history", 1,
                R.string.generator_snooze_delay_create_history_table,
                "INSERT INTO history VALUES (42, 7, 8, 123456789, 123400000, 30000)"),
        SURVEY(DailySurveyGenerator.class, "daily-survey-response.sqlite", "responses", 3,
                R.string.generator_daily_survey_create_responses_table,
                "INSERT INTO responses VALUES (42, 7, 8, 123456789, 'retained-survey', 'q1', 'Question', 'single', '[2]', '[\"answer\"]', 'retained text', 0, 123400000, 123456700, 'native')");

        final Class<? extends Generator> type;
        final String file;
        final String table;
        final int version;
        final int createResource;
        final String insert;

        Schema(Class<? extends Generator> type, String file, String table, int version,
               int createResource, String insert) {
            this.type = type;
            this.file = file;
            this.table = table;
            this.version = version;
            this.createResource = createResource;
            this.insert = insert;
        }
    }

    // -2 means metadata itself is absent; -1 means metadata exists without a version.
    private File seed(Schema schema, int marker, boolean withCurrentRow) throws Exception {
        File directory = temporary.newFolder();
        try (SQLiteDatabase db = database(directory, schema)) {
            if (marker >= -1) {
                db.execSQL("CREATE TABLE metadata(key TEXT, value TEXT, last_updated INTEGER)");
                if (marker >= 0) {
                    db.execSQL("INSERT INTO metadata VALUES ('version', ?, 1)", new Object[]{marker});
                }
            }
            if (withCurrentRow) {
                createCurrent(db, schema);
                db.execSQL(schema.insert);
            }
        }
        return directory;
    }

    private SQLiteDatabase database(File directory, Schema schema) {
        return SQLiteDatabase.openOrCreateDatabase(new File(directory, schema.file), null);
    }

    private void createCurrent(SQLiteDatabase db, Schema schema) {
        db.execSQL(context.getString(schema.createResource));
        if (schema == Schema.SNOOZE) {
            db.execSQL(context.getString(R.string.generator_app_snooze_add_original_budget));
            db.execSQL(context.getString(R.string.generator_app_snooze_add_remaining_budget));
        }
    }

    private void open(File directory, Schema schema, Consumer<Generator> check) throws Exception {
        try (MockedStatic<PassiveDataKit> pdk = mockStatic(PassiveDataKit.class)) {
            pdk.when(() -> PassiveDataKit.getGeneratorsStorage(any(Context.class))).thenReturn(directory);
            Constructor<? extends Generator> constructor = schema.type.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            Generator generator;
            try {
                generator = constructor.newInstance(context);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new AssertionError(cause);
            }
            Field field = schema.type.getDeclaredField("mDatabase");
            field.setAccessible(true);
            try {
                check.accept(generator);
            } finally {
                ((SQLiteDatabase) field.get(generator)).close();
            }
        }
    }

    private void open(File directory, Schema schema) throws Exception {
        open(directory, schema, generator -> { });
    }

    private int version(SQLiteDatabase db) {
        try (Cursor rows = db.rawQuery("SELECT value FROM metadata WHERE key='version'", null)) {
            assertTrue(rows.moveToFirst());
            int result = rows.getInt(0);
            assertFalse(rows.moveToNext());
            return result;
        }
    }

    private List<String> snapshot(SQLiteDatabase db, String table) {
        List<String> result = new ArrayList<>();
        try (Cursor rows = db.rawQuery("SELECT * FROM " + table + " ORDER BY _id", null)) {
            for (String column : rows.getColumnNames()) result.add(column);
            while (rows.moveToNext()) {
                for (int i = 0; i < rows.getColumnCount(); i++) {
                    result.add(rows.isNull(i) ? null : rows.getString(i));
                }
            }
        }
        return result;
    }

    private int archiveCount(SQLiteDatabase db) {
        try (Cursor rows = db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name LIKE 'responses_legacy_%'", null)) {
            assertTrue(rows.moveToFirst());
            return rows.getInt(0);
        }
    }

    private boolean hasColumn(SQLiteDatabase db, String table, String column) {
        try (Cursor rows = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (rows.moveToNext()) {
                if (column.equals(rows.getString(rows.getColumnIndexOrThrow("name")))) return true;
            }
        }
        return false;
    }

    @Test public void allFourRecoverExistingRowsWithMissingEmptyOrZeroMetadata() throws Exception {
        for (Schema schema : Schema.values()) {
            for (int marker : new int[]{-2, -1, 0}) {
                File directory = seed(schema, marker, true);
                List<String> expected;
                try (SQLiteDatabase db = database(directory, schema)) {
                    expected = snapshot(db, schema.table);
                }
                for (int attempt = 0; attempt < 2; attempt++) {
                    open(directory, schema, generator -> {
                        List<Bundle> payloads = generator.fetchPayloads();
                        assertEquals(1, payloads.size());
                        assertEquals(123456789, payloads.get(0).getLong("observed"));
                    });
                    try (SQLiteDatabase db = database(directory, schema)) {
                        assertEquals(schema.version, version(db));
                        assertEquals(expected, snapshot(db, schema.table));
                        if (schema == Schema.SURVEY) assertEquals(0, archiveCount(db));
                    }
                }
            }
        }
    }

    @Test public void allFourCreateFreshSchemasAndLeaveCurrentAndFutureSchemasUntouched() throws Exception {
        for (Schema schema : Schema.values()) {
            File fresh = seed(schema, -2, false);
            open(fresh, schema, generator -> assertTrue(generator.fetchPayloads().isEmpty()));
            try (SQLiteDatabase db = database(fresh, schema)) {
                assertEquals(schema.version, version(db));
            }
            for (int marker : new int[]{schema.version, schema.version + 1}) {
                File directory = seed(schema, marker, true);
                List<String> expected;
                try (SQLiteDatabase db = database(directory, schema)) {
                    expected = snapshot(db, schema.table);
                }
                open(directory, schema);
                open(directory, schema);
                try (SQLiteDatabase db = database(directory, schema);
                     Cursor metadata = db.rawQuery("SELECT last_updated FROM metadata WHERE key='version'", null)) {
                    assertEquals(marker, version(db));
                    assertTrue(metadata.moveToFirst());
                    assertEquals("Current/future metadata must not be rewritten", 1, metadata.getLong(0));
                    assertEquals(expected, snapshot(db, schema.table));
                }
            }
        }
    }

    @Test public void snoozeResumesEveryAlterBoundaryWithoutLosingExistingValues() throws Exception {
        for (int marker : new int[]{-2, -1, 0, 1}) {
            for (int columns = 0; columns <= 2; columns++) {
                File directory = seed(Schema.SNOOZE, marker, false);
                try (SQLiteDatabase db = database(directory, Schema.SNOOZE)) {
                    db.execSQL(context.getString(R.string.generator_app_snooze_create_history_table));
                    db.execSQL("INSERT INTO history VALUES (42, 7, 8, 123456789, 60000, 'retained.app')");
                    if (columns >= 1) {
                        db.execSQL(context.getString(R.string.generator_app_snooze_add_original_budget));
                        db.execSQL("UPDATE history SET original_budget=20.5");
                    }
                    if (columns >= 2) {
                        db.execSQL(context.getString(R.string.generator_app_snooze_add_remaining_budget));
                        db.execSQL("UPDATE history SET remaining_budget=10.25");
                    }
                }
                open(directory, Schema.SNOOZE);
                open(directory, Schema.SNOOZE);
                try (SQLiteDatabase db = database(directory, Schema.SNOOZE);
                     Cursor row = db.rawQuery("SELECT _id, observed, duration, app_package, original_budget, remaining_budget FROM history", null)) {
                    assertEquals(2, version(db));
                    assertEquals(1, row.getCount());
                    assertTrue(row.moveToFirst());
                    assertEquals(42, row.getInt(0));
                    assertEquals(123456789, row.getLong(1));
                    assertEquals(60000, row.getLong(2));
                    assertEquals("retained.app", row.getString(3));
                    if (columns >= 1) assertEquals(20.5, row.getDouble(4), 0); else assertTrue(row.isNull(4));
                    if (columns >= 2) assertEquals(10.25, row.getDouble(5), 0); else assertTrue(row.isNull(5));
                }
            }
        }
    }

    @Test public void surveyWithCurrentColumnsAndStaleLegacyMarkerStaysLiveAndFetchable() throws Exception {
        for (int marker : new int[]{1, 2}) {
            File directory = seed(Schema.SURVEY, marker, true);
            List<String> expected;
            try (SQLiteDatabase db = database(directory, Schema.SURVEY)) {
                expected = snapshot(db, "responses");
            }
            open(directory, Schema.SURVEY, generator -> {
                List<Bundle> payloads = generator.fetchPayloads();
                assertEquals(1, payloads.size());
                assertEquals("retained text", payloads.get(0).getString("other_text"));
                assertEquals("[2]", payloads.get(0).getString("response_values"));
            });
            try (SQLiteDatabase db = database(directory, Schema.SURVEY)) {
                assertEquals(3, version(db));
                assertEquals(0, archiveCount(db));
                assertEquals(expected, snapshot(db, "responses"));
            }
        }
    }

    private File seedIncompatibleSurvey(int marker) throws Exception {
        File directory = seed(Schema.SURVEY, marker, false);
        try (SQLiteDatabase db = database(directory, Schema.SURVEY)) {
            if (marker == 2) {
                // The documented v2-to-v3 change added other_text.
                db.execSQL(context.getString(R.string.generator_daily_survey_create_responses_table)
                        .replace(", other_text TEXT", ""));
                db.execSQL("INSERT INTO responses(_id, observed, question_key, response_values) VALUES (42, 123456789, 'q1', '[2]')");
            } else {
                // An intentionally incompatible fixture, not a claim about historical v1 SQL.
                db.execSQL("CREATE TABLE responses(_id INTEGER PRIMARY KEY, observed INTEGER, legacy_answer TEXT)");
                db.execSQL("INSERT INTO responses VALUES (42, 123456789, 'uninterpreted legacy answer')");
            }
        }
        return directory;
    }

    @Test public void incompatibleSurveySchemasAreArchivedExactlyAndNotReplayed() throws Exception {
        for (int marker : new int[]{0, 1, 2}) {
            File directory = seedIncompatibleSurvey(marker);
            List<String> expected;
            try (SQLiteDatabase db = database(directory, Schema.SURVEY)) {
                expected = snapshot(db, "responses");
            }
            open(directory, Schema.SURVEY, generator -> assertTrue(generator.fetchPayloads().isEmpty()));
            open(directory, Schema.SURVEY);
            try (SQLiteDatabase db = database(directory, Schema.SURVEY)) {
                assertEquals(3, version(db));
                assertEquals(1, archiveCount(db));
                assertEquals(expected, snapshot(db, "responses_legacy_v" + marker));
                assertTrue(hasColumn(db, "responses", "other_text"));
            }
        }
    }

    @Test public void incompatibleSurveyArchiveNeverOverwritesAnEarlierArchive() throws Exception {
        File directory = seedIncompatibleSurvey(1);
        try (SQLiteDatabase db = database(directory, Schema.SURVEY)) {
            db.execSQL("CREATE TABLE responses_legacy_v1(_id INTEGER, evidence TEXT)");
            db.execSQL("INSERT INTO responses_legacy_v1 VALUES (7, 'older archive')");
        }
        open(directory, Schema.SURVEY);
        open(directory, Schema.SURVEY);
        try (SQLiteDatabase db = database(directory, Schema.SURVEY);
             Cursor older = db.rawQuery("SELECT evidence FROM responses_legacy_v1", null);
             Cursor retained = db.rawQuery("SELECT legacy_answer FROM responses_legacy_v1_1", null)) {
            assertEquals(2, archiveCount(db));
            assertTrue(older.moveToFirst());
            assertEquals("older archive", older.getString(0));
            assertTrue(retained.moveToFirst());
            assertEquals("uninterpreted legacy answer", retained.getString(0));
        }
    }

    @Test public void refusedSurveyVersionUpdateRollsBackArchiveAndNewSchemaThenRetries() throws Exception {
        File directory = seedIncompatibleSurvey(2);
        List<String> expected;
        try (SQLiteDatabase db = database(directory, Schema.SURVEY)) {
            expected = snapshot(db, "responses");
            db.execSQL("CREATE TRIGGER refuse_version BEFORE UPDATE ON metadata BEGIN SELECT RAISE(IGNORE); END");
        }
        assertThrows(SQLiteException.class, () -> open(directory, Schema.SURVEY));
        try (SQLiteDatabase db = database(directory, Schema.SURVEY)) {
            assertEquals(2, version(db));
            assertEquals(0, archiveCount(db));
            assertEquals(expected, snapshot(db, "responses"));
            assertFalse(hasColumn(db, "responses", "other_text"));
            db.execSQL("DROP TRIGGER refuse_version");
        }
        open(directory, Schema.SURVEY);
        try (SQLiteDatabase db = database(directory, Schema.SURVEY)) {
            assertEquals(3, version(db));
            assertEquals(expected, snapshot(db, "responses_legacy_v2"));
        }
    }

    @Test public void refusedSnoozeVersionInsertRollsBackAddedColumnsThenRetries() throws Exception {
        File directory = seed(Schema.SNOOZE, -1, false);
        try (SQLiteDatabase db = database(directory, Schema.SNOOZE)) {
            db.execSQL(context.getString(R.string.generator_app_snooze_create_history_table));
            db.execSQL("INSERT INTO history VALUES (42, 7, 8, 123456789, 60000, 'retained.app')");
            db.execSQL("CREATE TRIGGER refuse_version BEFORE INSERT ON metadata BEGIN SELECT RAISE(IGNORE); END");
        }
        assertThrows(SQLiteException.class, () -> open(directory, Schema.SNOOZE));
        try (SQLiteDatabase db = database(directory, Schema.SNOOZE);
             Cursor row = db.rawQuery("SELECT app_package FROM history", null)) {
            assertFalse(hasColumn(db, "history", "original_budget"));
            assertFalse(hasColumn(db, "history", "remaining_budget"));
            assertTrue(row.moveToFirst());
            assertEquals("retained.app", row.getString(0));
            db.execSQL("DROP TRIGGER refuse_version");
        }
        open(directory, Schema.SNOOZE, generator -> assertEquals(1, generator.fetchPayloads().size()));
        try (SQLiteDatabase db = database(directory, Schema.SNOOZE)) {
            assertEquals(2, version(db));
        }
    }
}

package com.audacious_software.phone_dashboard;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import com.audacious_software.passive_data_kit.PassiveDataKit;
import com.audacious_software.passive_data_kit.generators.device.ForegroundApplication;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = android.app.Application.class)
public class ForegroundDatabaseRecoveryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Context context = RuntimeEnvironment.getApplication();
    private static final String DATABASE = "pdk-foreground-application.sqlite";
    private static final String[] MIGRATIONS = {
            "CREATE TABLE history(_id INTEGER PRIMARY KEY AUTOINCREMENT, fetched INTEGER, transmitted INTEGER, observed INTEGER, application TEXT)",
            "ALTER TABLE history ADD duration REAL",
            "ALTER TABLE history ADD screen_active INTEGER",
            "ALTER TABLE history ADD display_state TEXT",
            "ALTER TABLE history ADD is_home INTEGER",
            "CREATE TABLE substitutes(package TEXT PRIMARY KEY, substitute TEXT)",
            "ALTER TABLE history ADD category TEXT"
    };

    private File seed(int completedStatements, int recordedVersion) throws Exception {
        File directory = temporary.newFolder();
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(directory, DATABASE), null)) {
            db.execSQL("CREATE TABLE metadata(key TEXT, value TEXT, last_updated INTEGER)");
            if (recordedVersion >= 0) {
                db.execSQL("INSERT INTO metadata VALUES ('version', ?, 1)", new Object[]{recordedVersion});
            }
            for (int i = 0; i < completedStatements; i++) db.execSQL(MIGRATIONS[i]);
            if (completedStatements > 0) {
                db.execSQL("INSERT INTO history(observed, application, transmitted) VALUES (123456789, 'existing.app', 0)");
            }
            if (completedStatements >= 6) {
                db.execSQL("INSERT INTO substitutes VALUES ('existing.app', 'replacement.app')");
            }
        }
        return directory;
    }

    private void openGenerator(File directory) throws Exception {
        try (MockedStatic<PassiveDataKit> pdk = mockStatic(PassiveDataKit.class)) {
            pdk.when(() -> PassiveDataKit.getGeneratorsStorage(any(Context.class))).thenReturn(directory);
            ForegroundApplication generator = new ForegroundApplication(context);
            Field database = ForegroundApplication.class.getDeclaredField("mDatabase");
            database.setAccessible(true);
            ((SQLiteDatabase) database.get(generator)).close();
        }
    }

    private Set<String> columns(SQLiteDatabase db) {
        Set<String> result = new HashSet<>();
        try (Cursor rows = db.rawQuery("PRAGMA table_info(history)", null)) {
            while (rows.moveToNext()) result.add(rows.getString(rows.getColumnIndexOrThrow("name")));
        }
        return result;
    }

    private void assertRecovered(File directory, boolean hadHistory, boolean hadSubstitute) {
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(directory, DATABASE), null)) {
            try (Cursor version = db.rawQuery("SELECT value FROM metadata WHERE key='version'", null)) {
                assertTrue(version.moveToFirst());
                assertEquals("7", version.getString(0));
                assertFalse(version.moveToNext());
            }
            assertEquals(10, columns(db).size());
            try (Cursor rows = db.rawQuery("SELECT observed, application, transmitted FROM history", null)) {
                assertEquals(hadHistory ? 1 : 0, rows.getCount());
                if (hadHistory) {
                    assertTrue(rows.moveToFirst());
                    assertEquals(123456789, rows.getLong(0));
                    assertEquals("existing.app", rows.getString(1));
                    assertEquals(0, rows.getInt(2));
                }
            }
            try (Cursor rows = db.rawQuery("SELECT substitute FROM substitutes", null)) {
                assertEquals(hadSubstitute ? 1 : 0, rows.getCount());
                if (hadSubstitute) {
                    assertTrue(rows.moveToFirst());
                    assertEquals("replacement.app", rows.getString(0));
                }
            }
        }
    }

    @Test public void interruptedInitialCreationRetainsHistoryAndRecoversOnNextOpen() throws Exception {
        // Version 0 with history already committed reproduces the production stack.
        File directory = seed(1, -1);
        openGenerator(directory);
        assertRecovered(directory, true, false);
        openGenerator(directory);
        assertRecovered(directory, true, false);
    }

    @Test public void everyInterruptedMigrationBoundaryResumesWithoutDroppingRows() throws Exception {
        for (int recorded = 0; recorded < 7; recorded++) {
            for (int committed = Math.max(1, recorded); committed <= 7; committed++) {
                File directory = seed(committed, recorded);
                openGenerator(directory);
                assertRecovered(directory, true, committed >= 6);
            }
        }
    }

    @Test public void freshInstallAndCompletedVersionSevenRemainIdempotent() throws Exception {
        File fresh = seed(0, -1);
        openGenerator(fresh);
        assertRecovered(fresh, false, false);
        File existing = seed(7, 7);
        openGenerator(existing);
        openGenerator(existing);
        assertRecovered(existing, true, true);
    }

    @Test public void failureToRecordVersionRollsBackAllSchemaChangesAndCanRetry() throws Exception {
        File directory = seed(0, -1);
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(directory, DATABASE), null)) {
            db.execSQL("CREATE TRIGGER refuse_version BEFORE INSERT ON metadata BEGIN SELECT RAISE(ABORT, 'injected version failure'); END");
        }
        assertThrows(SQLiteException.class, () -> openGenerator(directory));
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(directory, DATABASE), null)) {
            assertTrue("DDL must roll back with its version marker", columns(db).isEmpty());
            db.execSQL("DROP TRIGGER refuse_version");
        }
        openGenerator(directory);
        assertRecovered(directory, false, false);
    }
}

package com.audacious_software.phone_dashboard;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import com.audacious_software.passive_data_kit.PassiveDataKit;
import com.audacious_software.passive_data_kit.generators.device.UsageStatsGenerator;

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

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = android.app.Application.class)
public class UsageStatsDatabaseRecoveryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Context context = RuntimeEnvironment.getApplication();
    private static final String DATABASE = "pdk-usage-stats.sqlite";

    private File seed(boolean history, int version) throws Exception {
        File directory = temporary.newFolder();
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(directory, DATABASE), null)) {
            db.execSQL("CREATE TABLE metadata(key TEXT, value TEXT, last_updated INTEGER)");
            if (version >= 0) db.execSQL("INSERT INTO metadata VALUES ('version', ?, 1)", new Object[]{version});
            if (history) {
                db.execSQL("CREATE TABLE history(_id INTEGER PRIMARY KEY AUTOINCREMENT, fetched INTEGER, transmitted INTEGER, observed INTEGER, event_type TEXT, package TEXT, extras TEXT)");
                db.execSQL("INSERT INTO history(observed, event_type, package, extras, transmitted) VALUES (123456789, 'activity-resumed', 'retained.app', '{}', 0)");
            }
        }
        return directory;
    }

    private void openGenerator(File directory) throws Exception {
        try (MockedStatic<PassiveDataKit> pdk = mockStatic(PassiveDataKit.class)) {
            pdk.when(() -> PassiveDataKit.getGeneratorsStorage(any(Context.class))).thenReturn(directory);
            UsageStatsGenerator generator = new UsageStatsGenerator(context);
            Field database = UsageStatsGenerator.class.getDeclaredField("mDatabase");
            database.setAccessible(true);
            ((SQLiteDatabase) database.get(generator)).close();
        }
    }

    private void assertRecovered(File directory, boolean retained, int expectedVersion) {
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(directory, DATABASE), null);
             Cursor version = db.rawQuery("SELECT value FROM metadata WHERE key='version'", null);
             Cursor rows = db.rawQuery("SELECT observed, event_type, package, extras, transmitted FROM history", null)) {
            assertTrue(version.moveToFirst());
            assertEquals(Integer.toString(expectedVersion), version.getString(0));
            assertFalse(version.moveToNext());
            assertEquals(retained ? 1 : 0, rows.getCount());
            if (retained) {
                assertTrue(rows.moveToFirst());
                assertEquals(123456789, rows.getLong(0));
                assertEquals("activity-resumed", rows.getString(1));
                assertEquals("retained.app", rows.getString(2));
                assertEquals("{}", rows.getString(3));
                assertEquals(0, rows.getInt(4));
            }
        }
    }

    @Test public void interruptedCreationRetainsUsageEventsAndReopens() throws Exception {
        for (int version : new int[]{-1, 0}) {
            File directory = seed(true, version);
            openGenerator(directory);
            openGenerator(directory);
            assertRecovered(directory, true, 1);
        }
    }

    @Test public void freshAndCompletedSchemasRemainIdempotent() throws Exception {
        File fresh = seed(false, -1);
        openGenerator(fresh);
        assertRecovered(fresh, false, 1);
        File existing = seed(true, 1);
        openGenerator(existing);
        assertRecovered(existing, true, 1);
    }

    @Test public void failedVersionWriteRollsBackCreationAndCanRetry() throws Exception {
        File directory = seed(false, -1);
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(directory, DATABASE), null)) {
            db.execSQL("CREATE TRIGGER refuse_version BEFORE INSERT ON metadata BEGIN SELECT RAISE(ABORT, 'injected version failure'); END");
        }
        assertThrows(SQLiteException.class, () -> openGenerator(directory));
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(directory, DATABASE), null);
             Cursor tables = db.rawQuery("SELECT name FROM sqlite_master WHERE name='history'", null)) {
            assertFalse("History creation must roll back", tables.moveToFirst());
            db.execSQL("DROP TRIGGER refuse_version");
        }
        openGenerator(directory);
        assertRecovered(directory, false, 1);
    }

    @Test public void futureSchemaMarkerIsPreserved() throws Exception {
        File directory = seed(true, 2);
        openGenerator(directory);
        assertRecovered(directory, true, 2);
    }
}

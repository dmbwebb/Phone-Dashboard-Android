package com.audacious_software.phone_dashboard;

import android.app.Application;
import android.os.Bundle;
import android.preference.PreferenceManager;
import androidx.test.runner.AndroidJUnitRunner;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

/** Fixture identity exists only inside the instrumentation APK. */
public class ReliabilityTestRunner extends AndroidJUnitRunner {
    private boolean lifecycle;
    @Override public void onCreate(Bundle arguments) {
        lifecycle = "true".equals(arguments.getString("lifecycle"));
        super.onCreate(arguments);
    }
    @Override public void callApplicationOnCreate(Application application) {
        if (lifecycle) {
            PreferenceManager.getDefaultSharedPreferences(application).edit()
                    .putString("com.audacious_software.phone_dashboard.IDENTIFIER", "E2E-SYNTHETIC")
                    .putString("com.audacious_software.phone_dashboard.ROLE", "child")
                    .putString(Schedule.SAVED_CONFIGURATION, ReliabilityInstrumentedTest.CONFIG)
                    .commit();
        }
        super.callApplicationOnCreate(application);
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(false);
    }
}

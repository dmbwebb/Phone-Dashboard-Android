package com.audacious_software.phone_dashboard;

import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import androidx.test.runner.AndroidJUnitRunner;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

/** Runs the real application startup with synthetic identity and local configuration endpoints. */
public class CompressionRecoveryTestRunner extends AndroidJUnitRunner {
    @Override public Application newApplication(ClassLoader loader, String name, Context context)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return super.newApplication(loader, RecoveryApplication.class.getName(), context);
    }

    public static class RecoveryApplication extends AppApplication {
        private Resources fixtureResources;

        @Override public void onCreate() {
            super.onCreate();
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(false);
        }

        @Override public Resources getResources() {
            if (fixtureResources == null) {
                Resources original = super.getResources();
                fixtureResources = new Resources(original.getAssets(), original.getDisplayMetrics(),
                        original.getConfiguration()) {
                    @Override public String getString(int id) throws NotFoundException {
                        if (id == R.string.url_phone_dashboard_configuration) {
                            return "http://127.0.0.1:8765/config";
                        }
                        if (id == R.string.url_phone_study_configuration) {
                            return "http://127.0.0.1:8765/study";
                        }
                        return super.getString(id);
                    }
                };
            }
            return fixtureResources;
        }
    }
}

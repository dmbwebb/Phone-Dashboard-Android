package com.audacious_software.passive_data_kit.activities;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.widget.ListView;

import androidx.test.core.app.ApplicationProvider;

import com.audacious_software.passive_data_kit.R;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = android.app.Application.class)
public class AppUsageSelectionActivityTest {
    private static final String PACKAGE_NAME = "com.oem.malformed";

    @After
    public void tearDown() {
        TestAppUsageSelectionActivity.sPackageManager = null;
    }

    @Test
    public void malformedFirstLauncherResultDoesNotCrashActivity() {
        PackageManager packageManager = spy(ApplicationProvider.getApplicationContext().getPackageManager());

        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = PACKAGE_NAME;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.packageName = PACKAGE_NAME;

        doReturn(Collections.singletonList(packageInfo))
                .when(packageManager)
                .getInstalledPackages(PackageManager.GET_META_DATA);
        doThrow(new NullPointerException("class name is null"))
                .when(packageManager)
                .getLaunchIntentForPackage(PACKAGE_NAME);

        ResolveInfo malformed = this.resolveInfo(PACKAGE_NAME, null);
        ResolveInfo valid = this.resolveInfo(PACKAGE_NAME, "com.oem.malformed.MainActivity");

        doReturn(Arrays.asList(malformed, valid))
                .when(packageManager)
                .queryIntentActivities(
                        argThat(intent -> intent != null && intent.hasCategory(Intent.CATEGORY_INFO)),
                        anyInt());

        TestAppUsageSelectionActivity.sPackageManager = packageManager;

        ActivityController<TestAppUsageSelectionActivity> controller =
                Robolectric.buildActivity(TestAppUsageSelectionActivity.class);
        TestAppUsageSelectionActivity activity = controller.get();
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat);
        controller.create();

        ListView apps = activity.findViewById(R.id.app_selection_list);
        assertEquals(1, apps.getAdapter().getCount());
    }

    private ResolveInfo resolveInfo(String packageName, String className) {
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.packageName = packageName;
        resolveInfo.activityInfo.name = className;
        return resolveInfo;
    }

    public static class TestAppUsageSelectionActivity extends AppUsageSelectionActivity {
        private static PackageManager sPackageManager;

        @Override
        public PackageManager getPackageManager() {
            if (sPackageManager != null) {
                return sPackageManager;
            }

            return super.getPackageManager();
        }
    }
}

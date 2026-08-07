package com.audacious_software.phone_dashboard;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = android.app.Application.class)
public class EditBudgetAdapterTest {
    private static final String BROKEN_PACKAGE = "com.oem.malformed";
    private static final String VALID_PACKAGE = "com.example.valid";

    @Test
    public void malformedLauncherMetadataSkipsOnlyBrokenApp() {
        Context context = mock(Context.class);
        AppApplication application = mock(AppApplication.class);
        PackageManager packageManager = mock(PackageManager.class);
        DailyBudgetGenerator budgets = mock(DailyBudgetGenerator.class);

        ApplicationInfo broken = this.applicationInfo(BROKEN_PACKAGE);
        ApplicationInfo valid = this.applicationInfo(VALID_PACKAGE);

        when(context.getApplicationContext()).thenReturn(application);
        when(context.getPackageManager()).thenReturn(packageManager);
        when(packageManager.getInstalledApplications(PackageManager.GET_META_DATA))
                .thenReturn(Arrays.asList(broken, valid));
        when(packageManager.getLaunchIntentForPackage(BROKEN_PACKAGE))
                .thenThrow(new NullPointerException("class name is null"));
        when(packageManager.getLaunchIntentForPackage(VALID_PACKAGE)).thenReturn(mock(Intent.class));
        when(packageManager.getApplicationLabel(valid)).thenReturn("Valid app");
        when(packageManager.queryIntentActivities(any(Intent.class), anyInt()))
                .thenAnswer(invocation -> {
                    Intent query = invocation.getArgument(0);

                    if (BROKEN_PACKAGE.equals(query.getPackage())) {
                        return Collections.singletonList(this.resolveInfo(BROKEN_PACKAGE, null));
                    }

                    if (VALID_PACKAGE.equals(query.getPackage())) {
                        return Collections.singletonList(this.resolveInfo(VALID_PACKAGE, "com.example.valid.MainActivity"));
                    }

                    return Collections.emptyList();
                });
        when(application.replacementPackages()).thenReturn(Collections.emptySet());
        when(application.hidePackage(any())).thenReturn(false);
        when(application.fetchPriority(any())).thenReturn(100);
        when(budgets.budgetForDate(any(), anyBoolean())).thenReturn(null);

        try (MockedStatic<DailyBudgetGenerator> generator = mockStatic(DailyBudgetGenerator.class)) {
            generator.when(() -> DailyBudgetGenerator.getInstance(context)).thenReturn(budgets);

            EditBudgetAdapter adapter = new EditBudgetAdapter(context, true, false);

            assertEquals(1, adapter.getItemCount());
        }
    }

    private ApplicationInfo applicationInfo(String packageName) {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = packageName;
        return info;
    }

    private ResolveInfo resolveInfo(String packageName, String className) {
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.packageName = packageName;
        resolveInfo.activityInfo.name = className;
        return resolveInfo;
    }
}

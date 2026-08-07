package com.audacious_software.phone_dashboard;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.HandlerThread;

import com.audacious_software.passive_data_kit.generators.device.ForegroundApplication;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = android.app.Application.class)
public class BudgetAdapterTest {
    private static final String BROKEN_PACKAGE = "com.oem.malformed";

    private HandlerThread mOriginalHandlerThread;
    private Handler mOriginalHandler;

    @Before
    public void setUp() throws Exception {
        this.mOriginalHandlerThread = (HandlerThread) this.staticField("sHandlerThread").get(null);
        this.mOriginalHandler = (Handler) this.staticField("sHandler").get(null);

        Handler handler = mock(Handler.class);
        when(handler.post(any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return true;
        });

        this.staticField("sHandlerThread").set(null, mock(HandlerThread.class));
        this.staticField("sHandler").set(null, handler);
        BudgetAdapter.clearInstalledApps();
        this.launchIntents().clear();
    }

    @After
    public void tearDown() throws Exception {
        BudgetAdapter.clearInstalledApps();
        this.launchIntents().clear();
        this.staticField("sHandlerThread").set(null, this.mOriginalHandlerThread);
        this.staticField("sHandler").set(null, this.mOriginalHandler);
    }

    @Test
    public void malformedLauncherMetadataSkipsBrokenAppWithoutCrashingWorker() {
        Context context = mock(Context.class);
        AppApplication application = mock(AppApplication.class);
        PackageManager packageManager = mock(PackageManager.class);
        ForegroundApplication foregroundApplication = mock(ForegroundApplication.class);
        AppLogger appLogger = mock(AppLogger.class);

        ApplicationInfo broken = new ApplicationInfo();
        broken.packageName = BROKEN_PACKAGE;

        when(context.getApplicationContext()).thenReturn(application);
        when(context.getPackageManager()).thenReturn(packageManager);
        when(application.replacementPackages()).thenReturn(Collections.emptySet());
        when(application.hidePackage(any())).thenReturn(false);
        when(packageManager.getInstalledApplications(PackageManager.GET_META_DATA))
                .thenReturn(Collections.singletonList(broken));
        when(packageManager.getLaunchIntentForPackage(BROKEN_PACKAGE))
                .thenThrow(new NullPointerException("class name is null"));
        when(packageManager.queryIntentActivities(any(Intent.class), anyInt()))
                .thenAnswer(invocation -> {
                    Intent query = invocation.getArgument(0);

                    if (query.hasCategory(Intent.CATEGORY_INFO)
                            || query.hasCategory(Intent.CATEGORY_LAUNCHER)) {
                        return Collections.singletonList(this.resolveInfo(BROKEN_PACKAGE, null));
                    }

                    return Collections.emptyList();
                });
        when(foregroundApplication.earliestTimestamp()).thenReturn(0L);

        try (MockedStatic<ForegroundApplication> foreground = mockStatic(ForegroundApplication.class);
             MockedStatic<AppLogger> logging = mockStatic(AppLogger.class)) {
            foreground.when(() -> ForegroundApplication.getInstance(context))
                    .thenReturn(foregroundApplication);
            logging.when(() -> AppLogger.getInstance(context)).thenReturn(appLogger);

            BudgetAdapter adapter = new BudgetAdapter(context, System.currentTimeMillis(), 1, true);

            assertEquals(1, adapter.getItemCount());
        }
    }

    private ResolveInfo resolveInfo(String packageName, String className) {
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.packageName = packageName;
        resolveInfo.activityInfo.name = className;
        return resolveInfo;
    }

    private Field staticField(String name) throws NoSuchFieldException {
        Field field = BudgetAdapter.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Intent> launchIntents() throws Exception {
        return (Map<String, Intent>) this.staticField("sLaunchIntents").get(null);
    }
}

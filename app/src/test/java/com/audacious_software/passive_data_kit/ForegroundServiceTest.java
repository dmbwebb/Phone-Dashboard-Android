package com.audacious_software.passive_data_kit;

import android.app.Notification;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = android.app.Application.class)
public class ForegroundServiceTest {
    @Test
    public void nullSourceAndMissingLauncherStillBuildNotification() {
        Context baseContext = ApplicationProvider.getApplicationContext();
        PackageManager packageManager = mock(PackageManager.class);
        PassiveDataKit passiveDataKit = mock(PassiveDataKit.class);

        when(packageManager.queryIntentActivities(any(), anyInt())).thenReturn(Collections.emptyList());
        when(passiveDataKit.getForegroundPendingIntent()).thenReturn(null);

        Context context = new PackageManagerContext(baseContext, packageManager);

        try (MockedStatic<PassiveDataKit> passiveDataKitStatic = mockStatic(PassiveDataKit.class)) {
            passiveDataKitStatic.when(() -> PassiveDataKit.getInstance(any(Context.class)))
                    .thenReturn(passiveDataKit);

            Notification notification = ForegroundService.getForegroundNotification(context, null);

            assertNotNull(notification);
        }
    }

    private static class PackageManagerContext extends ContextWrapper {
        private final PackageManager mPackageManager;

        PackageManagerContext(Context base, PackageManager packageManager) {
            super(base);
            this.mPackageManager = packageManager;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public PackageManager getPackageManager() {
            return this.mPackageManager;
        }
    }
}

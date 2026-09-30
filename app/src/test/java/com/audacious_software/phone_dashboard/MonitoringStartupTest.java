package com.audacious_software.phone_dashboard;

import android.content.Context;
import android.os.HandlerThread;
import android.preference.PreferenceManager;

import com.audacious_software.passive_data_kit.PassiveDataKit;
import com.audacious_software.passive_data_kit.transmitters.HttpTransmitter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = MonitoringStartupTest.TestApplication.class)
public class MonitoringStartupTest {
    public static class TestApplication extends AppApplication {
        @Override public void onCreate() { }
    }

    @Test public void repeatedCachedStartupCreatesOneUploaderAndDoesNotForceAnotherDrain() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString("com.audacious_software.phone_dashboard.IDENTIFIER", "reliability-test")
                .putString(Schedule.SAVED_CONFIGURATION, MonitoringConfigurationTest.valid()).commit();
        PassiveDataKit pdk = mock(PassiveDataKit.class);
        HttpTransmitter transmitter = mock(HttpTransmitter.class);
        when(pdk.fetchTransmitters(anyString(), anyString(), any())).thenReturn(Collections.singletonList(transmitter));
        try (MockedStatic<PassiveDataKit> mocked = mockStatic(PassiveDataKit.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> PassiveDataKit.getInstance(any(Context.class))).thenReturn(pdk);
            Schedule schedule = Schedule.getInstance(context);
            try {
                Method start = Schedule.class.getDeclaredMethod("start", String.class);
                start.setAccessible(true);
                start.invoke(schedule, "reliability-test");
                start.invoke(schedule, "reliability-test");
                verify(pdk, times(1)).fetchTransmitters(anyString(), anyString(), any());
                verify(transmitter, times(1)).setMaxBundleSize(32);
                verify(transmitter, never()).deinitialize(any());
            } finally {
                Field thread = Schedule.class.getDeclaredField("mHandlerThread");
                thread.setAccessible(true);
                ((HandlerThread) thread.get(schedule)).quitSafely();
            }
        }
    }
}

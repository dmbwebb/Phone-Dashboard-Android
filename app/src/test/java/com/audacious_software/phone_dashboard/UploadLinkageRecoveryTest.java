package com.audacious_software.phone_dashboard;

import android.os.Bundle;
import android.preference.PreferenceManager;
import com.audacious_software.passive_data_kit.Toolbox;
import com.audacious_software.passive_data_kit.transmitters.HttpTransmitter;
import com.audacious_software.passive_data_kit.transmitters.Transmitter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = android.app.Application.class)
public class UploadLinkageRecoveryTest {
    @Test public void failedNativeInitializationAndPoisonedClassKeepQueueAndReportFailure() throws Exception {
        for (LinkageError failure : new LinkageError[]{new UnsatisfiedLinkError("synthetic JNI failure"),
                new NoClassDefFoundError("synthetic previously failed initializer")}) {
            HttpTransmitter transmitter = new HttpTransmitter();
            HashMap<String, String> options = new HashMap<>();
            options.put(HttpTransmitter.UPLOAD_URI, "https://example.invalid/recovery");
            options.put(HttpTransmitter.USER_ID, "synthetic");
            options.put(HttpTransmitter.COMPRESS_PAYLOADS, "true");
            transmitter.initialize(RuntimeEnvironment.getApplication(), options);
            try (MockedStatic<Toolbox> toolbox = mockStatic(Toolbox.class)) {
                toolbox.when(() -> Toolbox.encodeBase64(any(byte[].class))).thenThrow(failure);
                Bundle row = new Bundle(); row.putString("marker", "must-survive");
                assertTrue(transmitter.enqueueGeneratorUpdates("test", Collections.singletonList(row)));
                long pending = transmitter.pendingTransmissions();
                assertTrue(pending > 0);
                Method drain = HttpTransmitter.class.getDeclaredMethod("drainQueue");
                drain.setAccessible(true);
                drain.invoke(transmitter);
                assertEquals("Native linkage failure cannot acknowledge queued observations", pending,
                        transmitter.pendingTransmissions());
                assertEquals(0, transmitter.lastSuccessfulTransmission());
                String reason = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
                        .getString(Transmitter.FAILURE_REASON, "");
                assertTrue(reason, reason.contains(failure.getClass().getSimpleName()));
                Field retry = HttpTransmitter.class.getDeclaredField("mNextRetryAt");
                retry.setAccessible(true);
                assertTrue("Failure follows the normal durable retry path", retry.getLong(transmitter) > System.currentTimeMillis());
                Field scheduled = HttpTransmitter.class.getDeclaredField("mDrainScheduled");
                scheduled.setAccessible(true);
                assertFalse("Native failure must release the drain guard", scheduled.getBoolean(transmitter));
            } finally {
                transmitter.deinitialize(RuntimeEnvironment.getApplication());
            }
        }
    }
}

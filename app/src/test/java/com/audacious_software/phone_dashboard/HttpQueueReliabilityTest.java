package com.audacious_software.phone_dashboard;

import android.content.Context;
import android.os.Bundle;

import com.audacious_software.passive_data_kit.transmitters.HttpTransmitter;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = android.app.Application.class)
public class HttpQueueReliabilityTest {
    private final List<Queue> transmitters = new ArrayList<>();

    private static class Queue extends HttpTransmitter {
        File folder() { return getPendingFolder(); }
        void finish() throws IOException { closeOpenSession(); }
        File current() { return mCurrentFile; }
        void begin(String name) throws IOException {
            mCurrentFile = new File(folder(), name + ".in-progress");
            mJsonGenerator = new JsonFactory().createGenerator(mCurrentFile, JsonEncoding.UTF8);
            mJsonGenerator.writeStartArray();
            mJsonGenerator.writeStartObject();
            mJsonGenerator.writeNumberField("value", 1);
            mJsonGenerator.writeEndObject();
            mJsonGenerator.flush();
        }
    }

    private Queue queue() {
        Queue queue = new Queue();
        HashMap<String, String> options = new HashMap<>();
        options.put(HttpTransmitter.UPLOAD_URI, "https://example.invalid/data");
        options.put(HttpTransmitter.USER_ID, "reliability-test");
        queue.initialize(RuntimeEnvironment.getApplication(), options);
        transmitters.add(queue);
        return queue;
    }

    @After public void stopWorkers() {
        for (Queue queue : transmitters) queue.deinitialize(RuntimeEnvironment.getApplication());
    }

    private static Bundle observation(long total) {
        Bundle bundle = new Bundle();
        bundle.putLong("observed", 123456000L);
        bundle.putLong("total_ms", total);
        bundle.putString("package", "test.package");
        return bundle;
    }

    @Test public void acknowledgementPublishesCompletePayloadWithOriginalTimestamp() throws Exception {
        Queue queue = queue();
        assertTrue(queue.enqueueGeneratorUpdates("pdk-daily-usage-aggregate", Collections.singletonList(observation(8000))));
        File[] files = queue.folder().listFiles((dir, name) -> name.endsWith(".json"));
        assertEquals(1, files.length);
        org.json.JSONArray payload = new org.json.JSONArray(new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8));
        assertEquals(8000, payload.getJSONObject(0).getLong("total_ms"));
        assertEquals(123456.0, payload.getJSONObject(0).getJSONObject("passive-data-metadata").getDouble("timestamp"), 0);
        assertEquals(0, queue.lastSuccessfulTransmission());
    }

    @Test public void finalizationPreservesOtherWritersAndUsesNoMoveFileRace() throws Exception {
        Queue first = queue();
        Queue second = queue();
        first.begin("first");
        second.begin("second");
        first.finish();
        assertTrue(second.current().isFile());
        second.finish();
        assertTrue(new File(first.folder(), "first.json").isFile());
        assertTrue(new File(first.folder(), "second.json").isFile());
    }

    @Test public void missingOwnedFileIsReportedAsIOExceptionAndAllowsFutureObservations() throws Exception {
        Queue queue = queue();
        queue.begin("missing");
        File owned = queue.current();
        // Move rather than delete, preserving bytes while injecting the former crash condition.
        assertTrue(owned.renameTo(new File(queue.folder(), "externally-moved")));
        try {
            queue.finish();
            fail("Expected explicit finalization failure");
        } catch (IOException expected) {
            assertNull(queue.current());
        }
        queue.begin("after-failure");
        queue.finish();
        assertTrue(new File(queue.folder(), "after-failure.json").isFile());
    }

    @Test public void failedPublicationRemainsPendingUntilRetrySucceeds() throws Exception {
        Queue queue = queue();
        queue.begin("blocked");
        File destination = new File(queue.folder(), "blocked.json");
        assertTrue(destination.mkdir());
        File keep = new File(destination, "keep");
        Files.write(keep.toPath(), new byte[] { 1 });
        try {
            queue.finish();
            fail("Expected failure publishing over a directory");
        } catch (IOException expected) {
            assertTrue(queue.current().isFile());
        }
        assertTrue(destination.renameTo(new File(queue.folder(), "saved-directory")));
        queue.finish();
        assertNull(queue.current());
        assertTrue(destination.isFile());
    }

    @Test public void interruptedJsonSalvagesCompleteRecordsAndKeepsMalformedTail() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File folder = new File(context.getFilesDir(), "http-transmitter");
        folder.mkdirs();
        File interrupted = new File(folder, "interrupted.in-progress");
        String original = "[{\"value\":1},{\"value\":";
        Files.write(interrupted.toPath(), original.getBytes(StandardCharsets.UTF_8));
        Queue queue = queue();
        assertTrue(new File(folder, "interrupted.in-progress.recovered").isFile());
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".json"));
        assertEquals(1, files.length);
        org.json.JSONArray recovered = new org.json.JSONArray(new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8));
        assertEquals(1, recovered.length());
        assertEquals(1, recovered.getJSONObject(0).getInt("value"));
        assertEquals(original, new String(Files.readAllBytes(new File(folder, "interrupted.in-progress.recovered").toPath()), StandardCharsets.UTF_8));
    }

    @Test public void whollyMalformedInterruptedFileIsQuarantinedWithoutLoss() throws Exception {
        File folder = new File(RuntimeEnvironment.getApplication().getFilesDir(), "http-transmitter");
        folder.mkdirs();
        Files.write(new File(folder, "broken.in-progress").toPath(), "not-json".getBytes(StandardCharsets.UTF_8));
        Queue queue = queue();
        assertTrue(new File(folder, "broken.in-progress.error").isFile());
        assertEquals(0, queue.pendingTransmissions());
    }

    @Test public void diskWriteFailureCannotAcknowledgeDurableBatch() throws Exception {
        Queue queue = queue();
        File folder = queue.folder();
        assertTrue(folder.renameTo(new File(folder.getParentFile(), "saved-queue")));
        Files.write(folder.toPath(), new byte[] { 1 });
        assertFalse(queue.enqueueGeneratorUpdates("pdk-daily-usage-aggregate",
                Collections.singletonList(observation(8000))));
        assertEquals(0, queue.lastSuccessfulTransmission());
    }

    @Test public void restartDuringPersistedBackoffAutomaticallyRequestsDrainAtDeadline() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String endpoint = "https://example.invalid/restart-test";
        long deadline = System.currentTimeMillis() + 5_000L;
        android.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putLong("pdk-upload-retry:" + endpoint + ":reliability-test",
                        deadline).commit();
        AtomicInteger acceptedDrains = new AtomicInteger();
        Queue restarted = new Queue() {
            @Override public synchronized boolean transmit(boolean force) {
                boolean accepted = super.transmit(force);
                if (accepted) acceptedDrains.incrementAndGet();
                return accepted;
            }
        };
        Field networkHandlerField = HttpTransmitter.class.getDeclaredField("mNetworkHandler");
        networkHandlerField.setAccessible(true);
        android.os.Handler networkHandler = (android.os.Handler) networkHandlerField.get(restarted);
        org.robolectric.shadows.ShadowLooper network = org.robolectric.Shadows.shadowOf(networkHandler.getLooper());
        network.pause();
        try {
        HashMap<String, String> options = new HashMap<>();
        options.put(HttpTransmitter.UPLOAD_URI, endpoint);
        options.put(HttpTransmitter.USER_ID, "reliability-test");
        restarted.initialize(context, options);
        transmitters.add(restarted);

        assertFalse("Startup must respect the restored backoff", restarted.transmit(true));
        assertEquals(0, acceptedDrains.get());
        // Handler idleFor advances Android uptime, not java.lang.System wall time.
        // Persisted retry deadlines intentionally use wall time across process restarts.
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(Math.max(1, deadline - System.currentTimeMillis()));
        }
        network.idleFor(Duration.ofSeconds(6));
        assertEquals("The new process must resume without another job or foreground entry", 1,
                acceptedDrains.get());
        } finally {
            // Restore the looper before @After quits it. Robolectric's own reset
            // cannot unpause a HandlerThread that has already terminated.
            network.unPause();
        }
    }
}

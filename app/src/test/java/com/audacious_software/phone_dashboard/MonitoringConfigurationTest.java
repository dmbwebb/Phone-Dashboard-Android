package com.audacious_software.phone_dashboard;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, application = android.app.Application.class)
public class MonitoringConfigurationTest {
    static String valid() {
        return "{\"transmitters\":[{\"type\":\"pdk-http-transmitter\",\"upload-uri\":\"https://example.invalid/data/\"}],"
                + "\"generators\":[{\"identifier\":\"pdk-daily-usage-aggregate\",\"enabled\":true,\"lookback-days\":7}]}";
    }

    @Test public void acceptsValidConfigAndDeduplicatesSameEndpoint() throws Exception {
        JSONObject config = new JSONObject(valid());
        config.getJSONArray("transmitters").put(config.getJSONArray("transmitters").get(0));
        assertEquals(1, MonitoringConfiguration.parse(config.toString()).getJSONArray("transmitters").length());
    }

    @Test public void rejectsEnrollmentErrorAndHtmlResponses() {
        for (String text : new String[]{null, "", "<html>offline</html>", "{}",
                "{\"identifier\":\"12345678\"}", "{\"error\":\"invalid identifier\"}"}) {
            assertThrows(JSONException.class, () -> MonitoringConfiguration.parse(text));
        }
    }

    @Test public void rejectsInvalidOrInsecureEndpoint() {
        for (String endpoint : new String[]{"not a URL", "http://example.invalid/data/", "file:///data"}) {
            assertThrows(JSONException.class, () -> MonitoringConfiguration.parse(valid().replace("https://example.invalid/data/", endpoint)));
        }
    }

    @Test public void rejectsWrongBooleanAndBadCollectionIntervals() throws Exception {
        for (Object value : new Object[]{"garbage", -1, 0, 0.5}) {
            JSONObject config = new JSONObject(valid());
            config.getJSONArray("generators").getJSONObject(0).put("sample-interval", value);
            assertThrows(JSONException.class, () -> MonitoringConfiguration.parse(config.toString()));
        }
        assertThrows(JSONException.class, () -> MonitoringConfiguration.parse(valid().replace("\"enabled\":true", "\"enabled\":\"false\"")));
    }
}

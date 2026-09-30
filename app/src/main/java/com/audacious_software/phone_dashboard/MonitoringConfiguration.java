package com.audacious_software.phone_dashboard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

import okhttp3.HttpUrl;

/** Validates the complete monitoring configuration before replacing the working cache. */
final class MonitoringConfiguration {
    private MonitoringConfiguration() { }

    static JSONObject parse(String text) throws JSONException {
        if (text == null) throw new JSONException("No monitoring configuration");
        JSONObject config = new JSONObject(text);
        JSONArray definitions = config.getJSONArray("transmitters");
        JSONArray generators = config.getJSONArray("generators");
        if (definitions.length() == 0 || generators.length() == 0) {
            throw new JSONException("Empty monitoring configuration");
        }
        Set<String> endpoints = new HashSet<>();
        JSONArray unique = new JSONArray();
        for (int i = 0; i < definitions.length(); i++) {
            JSONObject definition = definitions.getJSONObject(i);
            if (!"pdk-http-transmitter".equals(definition.getString("type"))) {
                throw new JSONException("Unsupported transmitter");
            }
            HttpUrl url = HttpUrl.parse(definition.getString("upload-uri"));
            if (url == null || !("https".equals(url.scheme()) ||
                    (BuildConfig.DEBUG && ("127.0.0.1".equals(url.host()) || "10.0.2.2".equals(url.host()))))) {
                throw new JSONException("Invalid upload endpoint");
            }
            for (String option : new String[]{"compression", "wifi-only", "charging-only",
                    "use-external-storage", "strict-ssl-verification"}) {
                if (definition.has(option) && !(definition.get(option) instanceof Boolean)) {
                    throw new JSONException("Invalid transmitter option: " + option);
                }
            }
            if (definition.has("max-bundle-size") && definition.getInt("max-bundle-size") <= 0) {
                throw new JSONException("Invalid bundle size");
            }
            if (endpoints.add(url.toString())) unique.put(definition);
        }
        for (int i = 0; i < generators.length(); i++) {
            JSONObject generator = generators.getJSONObject(i);
            if (generator.getString("identifier").trim().isEmpty()) {
                throw new JSONException("Missing generator identifier");
            }
            if (generator.has("enabled") && !(generator.get("enabled") instanceof Boolean)) {
                throw new JSONException("Invalid generator enabled flag");
            }
            for (String option : new String[]{"sample-interval", "lookback-days"}) {
                if (generator.has(option)) {
                    Object value = generator.get(option);
                    if (!(value instanceof Number) || ((Number) value).doubleValue() <= 0
                            || ((Number) value).doubleValue() != ((Number) value).longValue()) {
                        throw new JSONException("Invalid generator interval: " + option);
                    }
                }
            }
            for (String option : new String[]{"included-apps", "excluded-apps"}) {
                if (generator.has(option)) {
                    JSONArray apps = generator.getJSONArray(option);
                    for (int j = 0; j < apps.length(); j++) {
                        if (!(apps.get(j) instanceof String)) throw new JSONException("Invalid package list");
                    }
                }
            }
        }
        config.put("transmitters", unique);
        return config;
    }
}

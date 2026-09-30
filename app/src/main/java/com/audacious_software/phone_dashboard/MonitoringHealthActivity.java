package com.audacious_software.phone_dashboard;

import android.app.ActivityManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.audacious_software.passive_data_kit.generators.Generators;
import com.audacious_software.passive_data_kit.generators.device.UsageStatsGenerator;
import com.audacious_software.passive_data_kit.generators.device.DailyUsageAggregateGenerator;
import com.audacious_software.passive_data_kit.transmitters.HttpTransmitter;
import com.audacious_software.passive_data_kit.transmitters.Transmitter;

import java.text.DateFormat;
import java.util.Date;

/** Participant-facing checks distinguish permission, collection and server acceptance. */
public class MonitoringHealthActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView feedback;
    private Button check;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.AppTheme_NoActionBar);
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setTitle(R.string.monitoring_title);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        status = new TextView(this);
        status.setTextSize(17);
        status.setTextIsSelectable(true);
        content.addView(status);
        feedback = new TextView(this);
        content.addView(feedback);
        check = button(content, R.string.monitoring_title, () -> {
            check.setEnabled(false);
            feedback.setText(R.string.monitoring_checking);
            Schedule.getInstance(this).checkAndSync(() -> {
                if (isFinishing() || isDestroyed()) return;
                check.setEnabled(true);
                feedback.setText(R.string.monitoring_requested);
                refreshStatus();
            });
        });
        button(content, R.string.monitoring_usage_action, () -> openSettings(
                new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        button(content, R.string.monitoring_battery_action, () -> openSettings(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        TextView guidance = new TextView(this);
        guidance.setText(R.string.monitoring_guidance);
        content.addView(guidance);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        // Keep the app bar in the layout so it cannot cover the first status rows.
        // Android 15+ enforces edge-to-edge; the root owns system-bar/cutout insets.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(ContextCompat.getColor(this, R.color.colorPrimary));
        toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.textOnPrimary));
        TypedValue actionBarSize = new TypedValue();
        getTheme().resolveAttribute(androidx.appcompat.R.attr.actionBarSize, actionBarSize, true);
        int toolbarHeight = TypedValue.complexToDimensionPixelSize(actionBarSize.data, getResources().getDisplayMetrics());
        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, toolbarHeight));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        setContentView(root);
        setSupportActionBar(toolbar);
        ViewCompat.requestApplyInsets(root);
    }

    private Button button(LinearLayout parent, int label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    private void openSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException error) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        Schedule.getInstance(this).resumeMonitoring();
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private String time(long timestamp) {
        return timestamp > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(timestamp))
                : getString(R.string.monitoring_not_yet);
    }

    private void refreshStatus() {
        boolean permission = UsageStatsGenerator.hasPermissions(this);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        boolean unrestricted = power != null && power.isIgnoringBatteryOptimizations(getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            unrestricted = unrestricted && manager != null && !manager.isBackgroundRestricted();
        }
        long queued = 0, acknowledgement = 0, attempt = 0;
        for (Transmitter transmitter : Generators.getInstance(this).activeTransmitters()) {
            queued += transmitter.pendingTransmissions();
            acknowledgement = Math.max(acknowledgement, transmitter.lastSuccessfulTransmission());
            if (transmitter instanceof HttpTransmitter) attempt = Math.max(attempt, ((HttpTransmitter) transmitter).lastTransmissionAttempt());
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String identifier = ((AppApplication) getApplication()).getIdentifier();
        status.setText(getString(R.string.monitoring_status,
                identifier == null ? getString(R.string.monitoring_not_enrolled) : identifier,
                getString(permission ? R.string.monitoring_allowed : R.string.monitoring_permission_missing),
                getString(unrestricted ? R.string.monitoring_unrestricted : R.string.monitoring_battery_check),
                getString(Schedule.getInstance(this).hasValidConfiguration() ? R.string.monitoring_ready : R.string.monitoring_config_missing),
                time(prefs.getLong(DailyUsageAggregateGenerator.LAST_QUERY_SUCCESS, 0)), queued,
                time(attempt), time(acknowledgement))
                + "\n\n" + getString(R.string.monitoring_daily_pending,
                DailyUsageAggregateGenerator.getInstance(this).pendingDeliveryCount())
                + "\n\n" + getString(R.string.monitoring_daily_result,
                dailyResult(prefs.getString(DailyUsageAggregateGenerator.LAST_QUERY_RESULT, "not_attempted")))
                + "\n\n" + getString(R.string.monitoring_last_error,
                time(prefs.getLong(Transmitter.FAILURE_TIMESTAMP, 0))));
    }

    private String dailyResult(String result) {
        switch (result) {
            case "collected": return getString(R.string.monitoring_query_collected);
            case "empty": return getString(R.string.monitoring_query_empty);
            case "permission-denied": return getString(R.string.monitoring_permission_missing);
            case "locked": return getString(R.string.monitoring_query_locked);
            case "unavailable": case "error": return getString(R.string.monitoring_query_error);
            case "disabled": return getString(R.string.monitoring_query_disabled);
            default: return getString(R.string.monitoring_not_yet);
        }
    }
}

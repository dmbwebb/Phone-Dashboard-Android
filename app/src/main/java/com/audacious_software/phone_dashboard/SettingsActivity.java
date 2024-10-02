package com.audacious_software.phone_dashboard;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.icu.text.DateFormat;
import android.icu.text.SimpleDateFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Toast;

import com.audacious_software.passive_data_kit.PassiveDataKit;
import com.audacious_software.passive_data_kit.activities.DataDisclosureActivity;
import com.audacious_software.passive_data_kit.activities.DataStreamActivity;
import com.audacious_software.passive_data_kit.generators.device.ForegroundApplication;
import com.audacious_software.passive_data_kit.generators.device.UsageEvents;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragment;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

public class SettingsActivity extends AppCompatActivity {
    public static final String TRANSMISSION_INTERVAL = "com.audacious_software.phone_dashboard.SettingsActivity.TRANSMISSION_INTERVAL";
    public static final String TRANSMISSION_INTERVAL_DEFAULT = "" + (5 * 60 * 1000);

    public static final String USER_SNOOZE_DELAY = "com.audacious_software.phone_dashboard.SettingsActivity.USER_SNOOZE_DELAY";
    public static final long USER_SNOOZE_DELAY_DURATION_DEFAULT = 0;

    private static final String TRANSMIT_DATA = "com.audacious_software.phone_dashboard.SettingsActivity.TRANSMIT_DATA";

    private static final String DATA_DISCLOSURE = "com.audacious_software.phone_dashboard.SettingsActivity.DATA_DISCLOSURE";
    private static final String DATA_STREAM = "com.audacious_software.phone_dashboard.SettingsActivity.DATA_STREAM";
    private static final String UPLOAD_DATA = "com.audacious_software.phone_dashboard.SettingsActivity.UPLOAD_DATA";

    private static final String APP_VERSION = "com.audacious_software.phone_dashboard.SettingsActivity.APP_VERSION";

    private static final String FAQ = "com.audacious_software.phone_dashboard.SettingsActivity.FAQ";

    // private static final String RECEIVES_SUBSIDY = "com.audacious_software.phone_dashboard.SettingsActivity.RECEIVES_SUBSIDY";
    // private static final String BLOCKER_TYPE = "com.audacious_software.phone_dashboard.SettingsActivity.BLOCKER_TYPE";
    // private static final String REMAINING_BUDGET = "com.audacious_software.phone_dashboard.SettingsActivity.REMAINING_BUDGET";
    private static final String SNOOZE_DELAY = "com.audacious_software.phone_dashboard.SettingsActivity.SNOOZE_DELAY";
    private static final String CHANGE_SNOOZE_DELAY = "com.audacious_software.phone_dashboard.SettingsActivity.CHANGE_SNOOZE_DELAY";
    private static final String REFRESH_STUDY_CONFIG = "com.audacious_software.phone_dashboard.SettingsActivity.REFRESH_STUDY_CONFIG";
    private static final String BLOCKING_STATUS = "com.audacious_software.phone_dashboard.SettingsActivity.BLOCKING_STATUS";
    private static final String TREATMENT_STATUS = "com.audacious_software.phone_dashboard.SettingsActivity.TREATMENT_STATUS";
    private static final String APP_CODE = "com.audacious_software.phone_dashboard.SettingsActivity.APP_CODE";
    // private static final String PERIOD_START = "com.audacious_software.phone_dashboard.SettingsActivity.PERIOD_START";
    private static final String ACKNOWLEDGEMENTS = "com.audacious_software.phone_dashboard.SettingsActivity.ACKNOWLEDGEMENTS";
    private static final String SHOW_SNOOZE_DELAY_MESSAGE = "com.audacious_software.phone_dashboard.SettingsActivity.SHOW_SNOOZE_DELAY_MESSAGE";
    private static final String TRANSMIT_USAGE = "com.audacious_software.phone_dashboard.SettingsActivity.TRANSMIT_USAGE";
    private static final String USAGE_HISTORY = "com.audacious_software.phone_dashboard.SettingsActivity.USAGE_HISTORY";
    public static final String LAST_EVENTS_HISTORY_RETRIEVED = "com.audacious_software.phone_dashboard.SettingsActivity.LAST_EVENTS_HISTORY_RETRIEVED_TEST";
    private static final String LAST_EVENTS_HISTORY_RETRIEVALS = "com.audacious_software.phone_dashboard.SettingsActivity.LAST_EVENTS_HISTORY_RETRIEVALS";


    private PhoneDashboardPreferenceFragment mSettingsFragment = null;


    public static class PhoneDashboardPreferenceFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener  {
        private Handler mHandler = new Handler();

        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            this.setPreferencesFromResource(R.xml.settings, rootKey);

            final SettingsActivity me = (SettingsActivity) this.getActivity();

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(me);
            prefs.registerOnSharedPreferenceChangeListener(this);
        }

        public void onPause()
        {
            final SettingsActivity me = (SettingsActivity) this.getActivity();

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(me);
            prefs.unregisterOnSharedPreferenceChangeListener(this);

            this.mHandler.removeCallbacksAndMessages (null);

            super.onPause();
        }

        private void showUsageHistory() {
            final SettingsActivity me = (SettingsActivity) this.getActivity();

            final AppApplication app = (AppApplication) me.getApplication();

            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);

            final Set<String> history = new HashSet<>(prefs.getStringSet(SettingsActivity.LAST_EVENTS_HISTORY_RETRIEVALS, new HashSet<>()));

            ArrayList<Date> dates = new ArrayList<>();

            DateFormat formatter = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.SHORT);

            for (String historyDate : history) {
                long timestamp = Long.parseLong(historyDate);

                Date when = new Date(timestamp);

                dates.add(when);
            }

            Collections.sort(dates, new Comparator<Date>() {
                @Override
                public int compare(Date one, Date two) {
                    return two.compareTo(one);
                }
            });

            String[] formattedDates = new String[history.size() + 1];

            long pending = PassiveDataKit.getInstance(me).pendingTransmissions();

            if (pending == 0) {
                formattedDates[0] = me.getString(R.string.title_transmission_complete);
            } else {
                formattedDates[0] = me.getString(R.string.title_transmission_in_progress, pending);
            }

            int index = 1;

            for (Date when : dates) {
                formattedDates[index] = formatter.format(when);

                index += 1;
            }

            ContextThemeWrapper wrapper = new ContextThemeWrapper(me, R.style.AppTheme);

            AlertDialog.Builder builder = new AlertDialog.Builder(wrapper);

            builder.setTitle(R.string.dialog_title_usage_history);

            builder.setItems(formattedDates, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {

                }
            });

            builder.show();
        }

        public boolean onPreferenceTreeClick(final Preference preference)
        {
            final PhoneDashboardPreferenceFragment fragment = this;

            final SettingsActivity me = (SettingsActivity) this.getActivity();

            final AppApplication app = (AppApplication) me.getApplication();

            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);

            String key = preference.getKey();

            if (SettingsActivity.UPLOAD_DATA.equals(key) || SettingsActivity.TRANSMIT_DATA.equals(key)) {
                AppLogger.getInstance(me).log("settings_transmit_data");

                Schedule.getInstance(me).transmitUsageSummary(me, true, true, true, true, true, true, true);
                Schedule.getInstance(me).transmitData();

                Toast.makeText(me, R.string.toast_uploading_data, Toast.LENGTH_LONG).show();

                return true;
            } else if (SettingsActivity.DATA_DISCLOSURE.equals(key)) {
                me.startActivity(new Intent(me, DataDisclosureActivity.class));

                AppLogger.getInstance(me).log("settings_launched_data_disclosure");

                return true;
            } else if (SettingsActivity.DATA_STREAM.equals(key)) {
                me.startActivity(new Intent(me, DataStreamActivity.class));

                AppLogger.getInstance(me).log("settings_launched_data_stream");

                return true;
            } else if (SettingsActivity.FAQ.equals(key)) {
                me.startActivity(new Intent(me, FAQActivity.class));

                AppLogger.getInstance(me).log("settings_launched_faq");

                return true;
            } else if (SettingsActivity.REFRESH_STUDY_CONFIG.equals(key)) {
                app.refreshConfiguration(true, new Runnable() {
                    @Override
                    public void run() {
                        fragment.refresh();

                        Toast.makeText(me, R.string.toast_config_update_succeeded, Toast.LENGTH_LONG).show();
                    }
                });
            } else if (SettingsActivity.TREATMENT_STATUS.equals(key)) {
                if (app.treatmentActive() == true) {
                    ContextThemeWrapper wrapper = new ContextThemeWrapper(me, R.style.AppTheme);

                    AlertDialog.Builder builder = new AlertDialog.Builder(wrapper);
                    builder.setTitle(R.string.title_opt_out);
                    builder.setMessage(R.string.message_opt_out);

                    builder.setPositiveButton(R.string.action_cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {

                        }
                    });

                    builder.setNeutralButton(R.string.action_opt_out, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            app.optOut(new Runnable() {
                                @Override
                                public void run() {
                                    fragment.refresh();
                                }
                            });
                        }
                    });

                    builder.create().show();

                    return true;
                }
            } else if (SettingsActivity.APP_CODE.equals(key)) {
                String identifier = app.getIdentifier();

                String message = me.getString(R.string.toast_app_code_copied, identifier);

                Toast.makeText(me, message, Toast.LENGTH_LONG).show();

                ClipboardManager clipboard = (ClipboardManager) me.getSystemService(CLIPBOARD_SERVICE);

                ClipData clip = ClipData.newPlainText("Phone Dashboard App Code", identifier);

                clipboard.setPrimaryClip(clip);

                return true;
            } else if (SettingsActivity.ACKNOWLEDGEMENTS.equals(key)) {
                me.startActivity(new Intent(me, SettingsAcknowledgementsActivity.class));
                return true;
            } else if (SettingsActivity.APP_VERSION.equals(key)) {
                me.startActivity(new Intent(me, DataStreamActivity.class));
                return true;
            } else if (SettingsActivity.CHANGE_SNOOZE_DELAY.equals(key)) {
                if (AppApplication.BLOCKER_TYPE_FLEXIBLE_SNOOZE.equals(app.blockerType())) {
                    final AlertDialog.Builder listDialog = new AlertDialog.Builder(me);

                    listDialog.setTitle(R.string.title_change_snooze_delay);

                    String[] labels = me.getResources().getStringArray(R.array.snooze_delay_options);
                    String[] values = me.getResources().getStringArray(R.array.snooze_delay_values);

                    listDialog.setItems(labels, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            long snoozeDelay = Long.parseLong(values[which]);

                            if (snoozeDelay < 0) {
                                SnoozeDelayGenerator.getInstance(me).addSnoozeDelay(-1);

                                Toast.makeText(me, R.string.toast_confirm_snooze_disabled, Toast.LENGTH_LONG).show();

                                fragment.refresh();
                            } else if (snoozeDelay == 0) {
                                SnoozeDelayGenerator.getInstance(me).addSnoozeDelay(0);

                                Toast.makeText(me, R.string.toast_confirm_snooze_none, Toast.LENGTH_LONG).show();

                                fragment.refresh();
                            } else {
                                SnoozeDelayGenerator.getInstance(me).addSnoozeDelay(snoozeDelay * 60 * 1000);

                                String confirm = me.getString(R.string.toast_confirm_snooze, "" + snoozeDelay);

                                Toast.makeText(me, confirm, Toast.LENGTH_LONG).show();

                                fragment.refresh();
                            }
                        }
                    });

                    if (prefs.getBoolean(SettingsActivity.SHOW_SNOOZE_DELAY_MESSAGE, true)) {
                        AlertDialog.Builder builder = new AlertDialog.Builder(me);

                        builder.setTitle(R.string.title_change_snooze_delay);
                        View content = LayoutInflater.from(me).inflate(R.layout.dialog_change_snooze_delay, null, false);

                        final CheckBox dontShow = content.findViewById(R.id.check_dont_show);

                        builder.setView(content);

                        builder.setPositiveButton(R.string.action_continue, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (dontShow.isChecked()) {
                                    SharedPreferences.Editor e = prefs.edit();
                                    e.putBoolean(SettingsActivity.SHOW_SNOOZE_DELAY_MESSAGE, false);
                                    e.apply();
                                }

                                listDialog.show();
                            }
                        });

                        builder.create().show();
                    } else {
                        listDialog.show();
                    }
                }
            } else if (SettingsActivity.TRANSMIT_USAGE.equals(key)) {
                long lastPull = prefs.getLong(SettingsActivity.LAST_EVENTS_HISTORY_RETRIEVED, 0);

                Calendar last = Calendar.getInstance();
                last.setTimeInMillis(lastPull);
                last.set(Calendar.HOUR_OF_DAY, 0);
                last.set(Calendar.MINUTE, 0);
                last.set(Calendar.SECOND, 0);
                last.set(Calendar.MILLISECOND, 0);

                Calendar now = Calendar.getInstance();
                now.setTimeInMillis(System.currentTimeMillis());
                now.set(Calendar.HOUR_OF_DAY, 0);
                now.set(Calendar.MINUTE, 0);
                now.set(Calendar.SECOND, 0);
                now.set(Calendar.MILLISECOND, 0);

                if (now.getTimeInMillis() == last.getTimeInMillis()) {
                    this.showUsageHistory();

                    return true;
                } else {
                    if (ForegroundApplication.hasPermissions(me) == false) {
                        ContextThemeWrapper wrapper = new ContextThemeWrapper(me, R.style.AppTheme);

                        AlertDialog.Builder builder = new AlertDialog.Builder(wrapper);

                        builder.setTitle(R.string.dialog_title_missing_app_usage_permission);
                        builder.setMessage(R.string.dialog_message_missing_app_usage_permission);
                        builder.setCancelable(false);

                        builder.setPositiveButton(R.string.action_continue, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                ForegroundApplication.fetchPermissions(me);

                            }
                        });

                        builder.create().show();
                    } else {
                        ContextThemeWrapper wrapper = new ContextThemeWrapper(me, R.style.AppTheme);

                        AlertDialog.Builder builder = new AlertDialog.Builder(wrapper);

                        builder.setTitle(R.string.dialog_title_fetching_usage_history);
                        builder.setMessage(R.string.dialog_message_fetching_usage_history);
                        builder.setCancelable(false);

                        final AlertDialog fetchingDialog = builder.show();

                        Runnable runnable = new Runnable() {
                            @Override
                            public void run() {
                                UsageEvents.getInstance(app).fetchFullHistory(true, 0);

                                me.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        long now = System.currentTimeMillis();

                                        SharedPreferences.Editor e = prefs.edit();
                                        e.putLong(SettingsActivity.LAST_EVENTS_HISTORY_RETRIEVED, now);

                                        final Set<String> history = new HashSet<>(prefs.getStringSet(SettingsActivity.LAST_EVENTS_HISTORY_RETRIEVALS, new HashSet<>()));

                                        history.add("" + now);

                                        e.putStringSet(SettingsActivity.LAST_EVENTS_HISTORY_RETRIEVALS, history);

                                        e.commit();

                                        ContextThemeWrapper wrapper = new ContextThemeWrapper(me, R.style.AppTheme);

                                        fetchingDialog.setCancelable(true);
                                        fetchingDialog.cancel();

                                        Schedule.getInstance(me).transmitData();

                                        AlertDialog.Builder builder = new AlertDialog.Builder(wrapper);

                                        builder.setTitle(R.string.dialog_title_usage_fetched);
                                        builder.setMessage(R.string.dialog_message_usage_fetched);

                                        builder.setPositiveButton(R.string.action_view_export_history, new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                fragment.onPreferenceTreeClick(preference);
                                            }
                                        });

                                        builder.setNegativeButton(R.string.action_close, new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {

                                            }
                                        });

                                        builder.show();
                                    }
                                });
                            }
                        };

                        Thread fetch = new Thread(runnable);
                        fetch.start();
                    }
                }

                return true;
            } else if (SettingsActivity.USAGE_HISTORY.equals(key)) {
                this.showUsageHistory();

                return true;
            }

            return super.onPreferenceTreeClick(preference);
        }

        public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {

        }

        public void onResume() {
            final PhoneDashboardPreferenceFragment me = this;

            super.onResume();

            this.refresh();
        }

        private void refresh() {
            final SettingsActivity me = (SettingsActivity) this.getActivity();

            if (me == null) {
                return;
            }

            final AppApplication app = (AppApplication) me.getApplication();

            Preference appCode = this.findPreference(SettingsActivity.APP_CODE);
            appCode.setTitle(app.getIdentifier());

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(me);

            Preference version = this.findPreference(SettingsActivity.APP_VERSION);

            try {
                version.setTitle(me.getPackageManager().getPackageInfo(me.getPackageName(), 0).versionName);
            } catch (PackageManager.NameNotFoundException ignored) {

            }

            // this.updatePendingTransmissions();

            // transmitData.setVisible(false);

            // Preference subsidy = this.findPreference(SettingsActivity.RECEIVES_SUBSIDY);
            // Preference blockerType = this.findPreference(SettingsActivity.BLOCKER_TYPE);
            Preference snoozeDelay = this.findPreference(SettingsActivity.SNOOZE_DELAY);
            Preference changeSnoozeDelay = this.findPreference(SettingsActivity.CHANGE_SNOOZE_DELAY);
            Preference treatmentStatus = this.findPreference(SettingsActivity.TREATMENT_STATUS);

            Preference blockStatus = this.findPreference(SettingsActivity.BLOCKING_STATUS);
            // Preference remainingBudget = this.findPreference(SettingsActivity.REMAINING_BUDGET);

            // remainingBudget.setTitle(this.getString(R.string.value_app_remaining_budget, app.remainingBudget(0)));

            boolean treatmentActive = app.treatmentActive();

            if (treatmentActive) {
                treatmentStatus.setTitle(R.string.label_app_treatment_status_active);
            } else {
                treatmentStatus.setTitle(R.string.label_app_treatment_status_inactive);
            }

            if (treatmentActive) {
                /* if (app.receivesSubsidy()) {
                    subsidy.setTitle(R.string.value_yes);
                } else {
                    subsidy.setTitle(R.string.value_no);
                } */

                long minuteDelay =  app.snoozeDelay() / (60 * 1000);

                String delayString = this.getString(R.string.label_snooze_delay_minutes, (int) minuteDelay);

                if (minuteDelay == 0) {
                    delayString = this.getString(R.string.label_snooze_delay_immediate);
                } else if (minuteDelay < 0) {
                    delayString = this.getString(R.string.label_snooze_delay_none);
                } else if (minuteDelay == 1) {
                    delayString = this.getString(R.string.label_snooze_delay_one_minute);
                }
                snoozeDelay.setTitle(delayString);

                String blocker = app.blockerType();

                changeSnoozeDelay.setVisible(false);

                if (AppApplication.BLOCKER_TYPE_NONE.equals(blocker) == false) {
                    blockStatus.setTitle(R.string.label_app_budget_status_active);
                    blockStatus.setVisible(true);
                    // subsidy.setVisible(true);
                    treatmentStatus.setVisible(true);
                    snoozeDelay.setVisible(true);

                    if (AppApplication.BLOCKER_TYPE_FLEXIBLE_SNOOZE.equals(blocker)) {
                        changeSnoozeDelay.setVisible(true);

                        long latestSnoozeDelay = SnoozeDelayGenerator.getInstance(me).latestSnoozeDelay();

                        if (latestSnoozeDelay < 0) {
                            changeSnoozeDelay.setSummary(R.string.label_app_change_snooze_delay_summary_disabled);
                        } else if (latestSnoozeDelay > 0) {
                            latestSnoozeDelay = latestSnoozeDelay / (60 * 1000);

                            if (latestSnoozeDelay == 1) {
                                changeSnoozeDelay.setSummary(R.string.label_app_change_snooze_delay_summary_one_minute);
                            } else {
                                changeSnoozeDelay.setSummary(me.getString(R.string.label_app_change_snooze_delay_summary, latestSnoozeDelay));
                            }
                        } else {
                            changeSnoozeDelay.setSummary(R.string.label_app_change_snooze_delay_summary_none);
                        }
                    }
                } else {
                    blockStatus.setTitle(R.string.label_app_budget_status_inactive);
                    blockStatus.setVisible(false);
                    // subsidy.setVisible(false);
                    treatmentStatus.setVisible(false);
                    snoozeDelay.setVisible(false);
                }

                if (AppApplication.BLOCKER_TYPE_NONE.equals(blocker)) {
                    // blockerType.setTitle(R.string.blocker_type_none);
                    blockStatus.setVisible(false);
                    // blockerType.setVisible(false);
                    // remainingBudget.setVisible(false);
                } else if (AppApplication.BLOCKER_TYPE_FREE_SNOOZE.equals(blocker)) {
                    // blockerType.setTitle(R.string.blocker_type_free_snooze);
                    blockStatus.setVisible(true);
                    // blockerType.setVisible(true);
                    // remainingBudget.setVisible(false);
                } else if (AppApplication.BLOCKER_TYPE_NO_SNOOZE.equals(blocker)) {
                    // blockerType.setTitle(R.string.blocker_type_no_snooze);
                    blockStatus.setVisible(true);
                    // blockerType.setVisible(true);
                    // remainingBudget.setVisible(false);
                    snoozeDelay.setVisible(false);
                } else if (AppApplication.BLOCKER_TYPE_COSTLY_SNOOZE.equals(blocker)) {
                    // blockerType.setTitle(R.string.blocker_type_costly_snooze);
                    blockStatus.setVisible(true);
                    // blockerType.setVisible(true);
                    // remainingBudget.setVisible(true);
                }
            } else {
                // subsidy.setVisible(false);
                // blockerType.setVisible(false);
                snoozeDelay.setVisible(false);
                blockStatus.setVisible(false);
                treatmentStatus.setVisible(false);
                // remainingBudget.setVisible(false);
            }

            // subsidy.setVisible(false);
            // blockerType.setVisible(false);

            /* Preference periodStart = this.findPreference(SettingsActivity.PERIOD_START);

            long whenStarted = app.periodStart();

            if (whenStarted > 0) {
                DateFormat format = android.text.format.DateFormat.getLongDateFormat(me);
                Date when = new Date(whenStarted);

                periodStart.setTitle(format.format(when));
            } else {
                periodStart.setTitle(R.string.period_start_unknown);
            } */

            this.onSharedPreferenceChanged(prefs, null);

            prefs.registerOnSharedPreferenceChangeListener(this);
        }

        private void updatePendingTransmissions() {
//            final PhoneDashboardPreferenceFragment me = this;
//
//            long pending = PassiveDataKit.getInstance(me.getActivity()).pendingTransmissions();
//
//            Preference transmitData = me.findPreference(SettingsActivity.TRANSMIT_DATA);
//
//            if (pending == 0) {
//                transmitData.setTitle(R.string.title_transmission_complete);
//            } else {
//                transmitData.setTitle(R.string.title_transmission_in_progress);
//            }
//
//            this.mHandler.postDelayed(new Runnable() {
//                @Override
//                public void run() {
//                    me.updatePendingTransmissions();
//                }
//            }, 5000);
        }
    }

    @SuppressWarnings("ConstantConditions")
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        this.setTitle(R.string.title_settings);
        this.getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        FragmentManager fragment = this.getSupportFragmentManager();

        FragmentTransaction transaction = fragment.beginTransaction();

        this.mSettingsFragment = new PhoneDashboardPreferenceFragment();

        transaction.replace(android.R.id.content, this.mSettingsFragment);

        transaction.commit();
    }

    protected void onResume() {
        super.onResume();

        final SettingsActivity me = this;

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                me.mSettingsFragment.onResume();
            }
        }, 1000);

        AppLogger.getInstance(this).log("settings_screen_appeared");
    }

    protected void onPause() {
        super.onPause();

        AppLogger.getInstance(this).log("settings_screen_dismissed");
    }

    @SuppressLint("InflateParams")
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home)
        {
            AppLogger.getInstance(this).log("settings_menu_home");

            this.finish();
        }

        return true;
    }
}

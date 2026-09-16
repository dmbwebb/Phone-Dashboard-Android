package com.audacious_software.phone_dashboard;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.audacious_software.phone_dashboard.survey.SurveyScheduler;

public class BootReceiver extends BroadcastReceiver {
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    public void onReceive(Context context, Intent intent) {
        Schedule.getInstance(context).updateSchedule(true, null, false);

        // Re-arm after reboot or app replacement so old schedules cannot linger.
        SurveyScheduler.schedule(context);
    }
}

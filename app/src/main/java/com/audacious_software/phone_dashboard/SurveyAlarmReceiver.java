package com.audacious_software.phone_dashboard;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.audacious_software.phone_dashboard.survey.SurveyScheduler;

/**
 * Fires when the nightly survey alarm goes off: surfaces the notification and
 * immediately re-arms the alarm for the following night. Posting a notification
 * is fast enough to complete inside the receiver window, so no goAsync() is
 * needed.
 */
public class SurveyAlarmReceiver extends BroadcastReceiver {
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    @Override
    public void onReceive(Context context, Intent intent) {
        SurveyScheduler.postNotification(context);
        SurveyScheduler.schedule(context);
    }
}

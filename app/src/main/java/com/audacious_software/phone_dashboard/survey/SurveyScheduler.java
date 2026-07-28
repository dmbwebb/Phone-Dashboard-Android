package com.audacious_software.phone_dashboard.survey;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import com.audacious_software.phone_dashboard.R;
import com.audacious_software.phone_dashboard.SurveyActivity;
import com.audacious_software.phone_dashboard.SurveyAlarmReceiver;

import java.util.Calendar;
import java.util.TimeZone;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * Owns the nightly survey schedule and notification. The trigger uses the same
 * robust primitive the project standardised on for DailyUsageAggregateGenerator:
 * an inexact {@code setAndAllowWhileIdle(RTC_WAKEUP, ...)} alarm anchored to an
 * absolute wall-clock time in America/Bogota and re-armed after every fire (and
 * on boot / app start). ±15 min slack is fine for a daily survey.
 */
public final class SurveyScheduler {
    private static final String TIMEZONE = "America/Bogota";
    private static final int PROMPT_HOUR_LOCAL = 20; // 8:00 PM Bogota
    private static final int PROMPT_MINUTE_LOCAL = 0;

    private static final int ALARM_REQUEST_CODE = 0x5C0FF;

    public static final String NOTIFICATION_CHANNEL_ID = "daily-survey";
    public static final int NOTIFICATION_ID = 0x5A5A01;

    public static final String ACTION_FIRE = "com.audacious_software.phone_dashboard.survey.FIRE";
    public static final String EXTRA_PROMPT_SHOWN_AT = "com.audacious_software.phone_dashboard.survey.PROMPT_SHOWN_AT";

    private SurveyScheduler() {
    }

    /** Arm (or re-arm) the alarm for the next PROMPT_HOUR_LOCAL:PROMPT_MINUTE_LOCAL in Bogota. */
    public static void schedule(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager alarmManager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerMillis(), alarmIntent(app));
    }

    private static PendingIntent alarmIntent(Context app) {
        Intent intent = new Intent(app, SurveyAlarmReceiver.class);
        intent.setAction(SurveyScheduler.ACTION_FIRE);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(app, SurveyScheduler.ALARM_REQUEST_CODE, intent, flags);
    }

    static long nextTriggerMillis() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(SurveyScheduler.TIMEZONE));
        cal.set(Calendar.HOUR_OF_DAY, SurveyScheduler.PROMPT_HOUR_LOCAL);
        cal.set(Calendar.MINUTE, SurveyScheduler.PROMPT_MINUTE_LOCAL);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DATE, 1);
        }
        return cal.getTimeInMillis();
    }

    /** Post the survey notification. Tapping it opens {@link SurveyActivity}. */
    public static void postNotification(Context context) {
        Context app = context.getApplicationContext();
        SurveyScheduler.ensureChannel(app);

        long promptShownAt = System.currentTimeMillis();

        Intent intent = new Intent(app, SurveyActivity.class);
        intent.putExtra(SurveyScheduler.EXTRA_PROMPT_SHOWN_AT, promptShownAt);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(app, SurveyScheduler.ALARM_REQUEST_CODE, intent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(app, SurveyScheduler.NOTIFICATION_CHANNEL_ID)
                .setContentIntent(contentIntent)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(app, R.color.colorNotification))
                .setContentTitle(app.getString(R.string.survey_note_title))
                .setContentText(app.getString(R.string.survey_note_message))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        if (ActivityCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManagerCompat.from(app).notify(SurveyScheduler.NOTIFICATION_ID, builder.build());
    }

    public static void cancelNotification(Context context) {
        NotificationManagerCompat.from(context.getApplicationContext()).cancel(SurveyScheduler.NOTIFICATION_ID);
    }

    private static void ensureChannel(Context app) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(SurveyScheduler.NOTIFICATION_CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                SurveyScheduler.NOTIFICATION_CHANNEL_ID,
                app.getString(R.string.survey_note_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(app.getString(R.string.survey_note_channel_description));
        channel.setShowBadge(true);
        manager.createNotificationChannel(channel);
    }
}

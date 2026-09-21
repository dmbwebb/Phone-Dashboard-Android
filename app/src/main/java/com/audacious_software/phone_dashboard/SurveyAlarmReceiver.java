package com.audacious_software.phone_dashboard;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

import com.audacious_software.phone_dashboard.survey.SurveyScheduler;

/**
 * Fires when the survey alarm goes off: surfaces the notification and
 * refreshes policy off the main thread, then surfaces or suppresses the notification
 * and re-arms the next eligible alarm.
 */
public class SurveyAlarmReceiver extends BroadcastReceiver {
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pending = goAsync();
        final AppApplication app = (AppApplication) context.getApplicationContext();
        final AtomicBoolean completed = new AtomicBoolean(false);
        final Runnable finish = new Runnable() {
            @Override public void run() {
                if (!completed.compareAndSet(false, true)) return;
                try {
                    SurveyScheduler.postNotification(app);
                    SurveyScheduler.schedule(app);
                } finally {
                    pending.finish();
                }
            }
        };
        // A successful forced refresh normally completes first. After 8 seconds,
        // fall back to the persisted last-known policy so the receiver stays bounded offline.
        new Handler(Looper.getMainLooper()).postDelayed(finish, 8_000);
        app.refreshSurveyPolicy(finish);
    }
}

package com.audacious_software.phone_dashboard;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android owns the lifetime of configuration refresh and the bounded upload attempt. */
public class MonitoringJobService extends JobService {
    static final int IMMEDIATE_JOB_ID = 12346;
    static final int PERIODIC_JOB_ID = 12347;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<JobParameters, AtomicBoolean> running = new ConcurrentHashMap<>();
    private final Map<JobParameters, okhttp3.Call> calls = new ConcurrentHashMap<>();

    public static void schedulePeriodic(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) scheduler.cancel(KeepAliveJobService.JOB_ID);
        if (scheduler != null && scheduler.getPendingJob(PERIODIC_JOB_ID) == null) {
            scheduler.schedule(new JobInfo.Builder(PERIODIC_JOB_ID,
                    new ComponentName(context, MonitoringJobService.class))
                    .setPeriodic(15 * 60 * 1000L).setPersisted(true).build());
        }
    }

    public static void scheduleNow(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null && scheduler.getPendingJob(IMMEDIATE_JOB_ID) == null) {
            scheduler.schedule(new JobInfo.Builder(IMMEDIATE_JOB_ID,
                    new ComponentName(context, MonitoringJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(30000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
        }
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        AtomicBoolean active = new AtomicBoolean(true);
        running.put(parameters, active);
        worker.execute(() -> {
            boolean configurationOk = Schedule.getInstance(this).refreshMonitoringConfiguration(active::get,
                    call -> calls.put(parameters, call));
            calls.remove(parameters);
            if (!active.get()) return;
            Schedule.getInstance(this).runScheduledWork(active::get, () -> {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (active.compareAndSet(true, false)) {
                        running.remove(parameters);
                        jobFinished(parameters, !configurationOk);
                    }
                });
            });
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        AtomicBoolean active = running.remove(parameters);
        if (active != null) active.set(false);
        okhttp3.Call call = calls.remove(parameters);
        if (call != null) call.cancel();
        return true;
    }

    @Override public void onDestroy() {
        for (AtomicBoolean active : running.values()) active.set(false);
        running.clear();
        for (okhttp3.Call call : calls.values()) call.cancel();
        calls.clear();
        worker.shutdownNow();
        super.onDestroy();
    }
}

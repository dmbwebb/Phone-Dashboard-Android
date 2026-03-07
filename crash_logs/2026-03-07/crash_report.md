# Crash Report: 2026-03-07

**Source:** Firebase Crashlytics (Mi Ritmo Digital)
**Date range:** Mar 1-7, 2026 (last 7 days)
**App version:** 87
**Crash-free users:** 87.5% (-12.5%)
**Crash-free sessions:** 95% (-5%)

## Summary

Two related crash issues, both caused by **Android 16's new foreground service time limits for `dataSync` type services**. All crashes occur on Samsung Galaxy A33 5G devices running Android 16, all in the background.

## Issue 1: ForegroundServiceStartNotAllowedException

- **Issue ID:** 35b02932553eb884c23a7763ae8fa35d
- **Crashes:** 66
- **Users affected:** 3
- **Exception:** `android.app.ForegroundServiceStartNotAllowedException: Time limit already exhausted for foreground service type dataSync`
- **Blame frame:** `ForegroundService.onStartCommand (ForegroundService.java:47)`
- **User ID:** 34757197
- **Device:** Samsung Galaxy A33 5G, Android 16

**What happens:** The app tries to call `startForeground()` with `FOREGROUND_SERVICE_TYPE_DATA_SYNC`, but Android 16 has already exhausted the 6-hour cumulative time limit for dataSync services in the past 24 hours. The OS throws this exception, crashing the app.

## Issue 2: ForegroundServiceDidNotStopInTimeException

- **Issue ID:** b9baeaede585fc3bc9b515c27cde532c
- **Crashes:** 10
- **Users affected:** 3
- **Exception:** `android.app.RemoteServiceException$ForegroundServiceDidNotStopInTimeException: A foreground service of type dataSync did not stop within its timeout`
- **Blame frame:** `ActivityThread.generateForegroundServiceDidNotStopInTimeException (ActivityThread.java:2684)`
- **User ID:** 34757197
- **Device:** Samsung Galaxy A33 5G, Android 16

**What happens:** The dataSync foreground service runs beyond its allowed timeout. Android 16 kills it and generates this crash.

## Root Cause

Android 16 enforces a **6-hour cumulative time limit** per 24-hour period for `dataSync` foreground services. The PDK ForegroundService is designed to run continuously (24/7) for phone usage monitoring, which exceeds this limit.

**Affected code:**
- `Passive-Data-Kit/java/src/com/audacious_software/passive_data_kit/ForegroundService.java:47`
  - Calls `startForeground(NOTIFICATION_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)`
- `Passive-Data-Kit/AndroidManifest.xml:89`
  - Declares `android:foregroundServiceType="dataSync"`

## Fix

Switch from `dataSync` to `specialUse` foreground service type on Android 14+ (API 34+). The `specialUse` type has no time limits and is appropriate for apps with legitimate use cases not covered by other types (phone usage monitoring for research).

### Changes required:
1. **ForegroundService.java:** Use `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34+, keep `DATA_SYNC` for API 29-33
2. **PDK AndroidManifest.xml:** Add `specialUse` to foreground service type, add `FOREGROUND_SERVICE_SPECIAL_USE` permission, add required `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property
3. **Add try-catch** around `startForeground()` for resilience against future platform changes

### Google Play note:
`specialUse` foreground services require justification during Play Store review. Justification: "Continuous phone usage monitoring for behavioral research study on smartphone habits."

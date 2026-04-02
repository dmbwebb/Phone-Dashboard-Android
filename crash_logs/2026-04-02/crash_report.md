# Crash Report — April 2, 2026

Reviewing crashes from Firebase Crashlytics since last review (March 12, 2026).

## Summary

| # | Issue | Version | Date | Status |
|---|-------|---------|------|--------|
| 1 | ForegroundServiceStartNotAllowedException | v87 | Mar 8 | Old version — already fixed in v89+ |
| 2 | HttpTransmitter file race condition | v91 | Mar 29 | **Fixed** — added file existence check |
| 3 | Google Sign-In NPE | v89 | Mar 12 | **Fixed** — bumped play-services-auth to 21.5.1 |
| 4 | ForegroundServiceDidNotStopInTime | v87 | Mar 7 | Old version — already fixed in v89+ |

## Crash 1: ForegroundServiceStartNotAllowedException (v87)

**Exception:** `ForegroundServiceStartNotAllowedException: Service.startForeground() not allowed due to mAllowStartForeground false`

**Root cause:** Android 12+ prevents starting foreground services from background. The PDK `ForegroundService.onStartCommand()` called `startForeground()` without proper background start exemption.

**Status:** Already fixed. The current code (v89+) wraps `startForeground()` in a try-catch (ForegroundService.java:48-58). This crash is from v87, which predates that fix.

## Crash 2: HttpTransmitter file race condition (v91) — FIXED

**Exception:** `IllegalArgumentException: Parameter 'srcFile' is not a file: .../1774800901427.in-progress`

**Root cause:** Race condition in `HttpTransmitter.closeOpenSession()` at line 739. The method closes the JSON generator and nulls `mCurrentFile`/`mJsonGenerator`, then tries to move the `.in-progress` temp file. Between closing and moving, another thread can delete the file, causing `FileUtils.moveFile()` to crash.

**Fix:** Added `tempFile.exists()` check before calling `FileUtils.moveFile()` in HttpTransmitter.java:739.

## Crash 3: Google Sign-In NullPointerException (v89) — FIXED

**Exception:** `NullPointerException: Attempt to invoke virtual method 'java.lang.Class java.lang.Object.getClass()' on a null object reference` in `SignInHubActivity.onCreate()`

**Root cause:** Bug inside Google Play Services `play-services-auth:21.0.0`. The NPE occurs in Google's internal `SignInHubActivity`, not our code. This was on an older device (Android 11, API 30).

**Fix:** Bumped `play-services-auth` from 21.0.0 to 21.5.1 (Feb 2026 release, which includes a ProGuard/R8 crash fix).

## Crash 4: ForegroundServiceDidNotStopInTimeException (v87)

**Exception:** `ForegroundServiceDidNotStopInTimeException: A foreground service of type dataSync did not stop within its timeout`

**Root cause:** The PDK `ForegroundService` didn't stop promptly enough after Android requested it. This is an Android 14+ enforcement for `dataSync` foreground service types.

**Status:** Already addressed. In v89+, the service uses `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on Android 14+ (API 34+) instead of `dataSync`, which has a more lenient timeout policy.

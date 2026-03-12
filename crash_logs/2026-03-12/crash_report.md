# Crash Report: 2026-03-12

**Source:** Firebase Crashlytics (Mi Ritmo Digital)
**Date range:** Mar 6-12, 2026 (last 7 days)
**Latest release:** 88
**Crash-free users:** 92.31% (+3.42%)
**Crash-free sessions:** 97.87% (+3.43%)
**Total crashes:** 41 (-38.8% from prior week)
**Users affected:** 6

## Overview

5 open issues. Two are carry-over from v87 (same root cause as March 7 report, fixed in v88). Three are **new fresh issues** on v88, each with 1 event.

The v87 foreground service crashes are declining as users update to v88 where the `specialUse` fix is active. Crash-free rates are improving.

---

## Known Issues (v87 carry-over, already fixed in v88)

### Issue 1: ForegroundServiceStartNotAllowedException (dataSync time limit)
- **Events:** 35 | **Users:** 3 | **Version:** 87
- **Same as March 7 report** - Android 16 dataSync 6-hour time limit
- Fix applied in v88: `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34+
- **Status:** Resolved in v88. Will disappear as remaining v87 users update.

### Issue 2: ForegroundServiceDidNotStopInTimeException
- **Events:** 3 | **Users:** 2 | **Version:** 87
- **Same as March 7 report** - dataSync service timeout on Android 16
- **Status:** Resolved in v88. Will disappear as remaining v87 users update.

---

## New Issues (v88)

### Issue 3: RequestPermissionActivity.onResume - NullPointerException
- **Events:** 1 | **Users:** 1 | **Version:** 88
- **Device:** OnePlus8Pro, Android 11
- **Date:** Mar 7, 2026, 1:52:59 PM
- **User ID:** Not set

**Exception:** `java.lang.NullPointerException: Attempt to invoke virtual method 'java.lang.String android.os.BaseBundle.getString(java.lang.String)' on a null object reference`

**Blame frame:** `RequestPermissionActivity.onResume (RequestPermissionActivity.java:41)`

**Root cause:** `this.getIntent().getExtras()` returns null at line 39, then line 41 calls `extras.getString(...)` on the null bundle. This happens when the system recreates the activity after process death without preserving the original intent extras.

**Code (RequestPermissionActivity.java:33-41):**
```java
protected void onResume() {
    super.onResume();
    if (this.mIsRequesting == false) {
        this.mIsRequesting = true;
        Bundle extras = this.getIntent().getExtras();  // line 39 - can be null
        String permission = extras.getString(...);       // line 41 - NPE!
```

The same pattern exists in `onRequestPermissionsResult` at line 63.

**Severity:** Low (1 event, edge case)

---

### Issue 4: PassiveDataKit.initializeNotifications - ForegroundServiceStartNotAllowedException
- **Events:** 1 | **Users:** 1 | **Version:** 88
- **Device:** Xiaomi Redmi A2, Android 13
- **Date:** Mar 11, 2026, 4:25:26 PM
- **User ID:** 43912332
- **State:** 100% background

**Exception:** `android.app.ForegroundServiceStartNotAllowedException: startForegroundService() not allowed due to mAllowStartForeground false`

**Blame frame:** `PassiveDataKit.initializeNotifications (PassiveDataKit.java:144)`

**Call chain:**
```
OkHttp background thread
  -> Schedule$3.onResponse (Schedule.java:420)
  -> Schedule.start (Schedule.java:345)
  -> PassiveDataKit.start (PassiveDataKit.java:128)
  -> PassiveDataKit.initializeNotifications (PassiveDataKit.java:144)
  -> ContextCompat.startForegroundService()  // CRASH
```

**Root cause:** This is **different from the v87 Android 16 time-limit issue**. This is Android 12+ (API 31+) restricting foreground service starts from the background. The OkHttp `onResponse` callback runs on a background thread while the app has no visible activity. Android blocks the `startForegroundService()` call.

The try-catch in `ForegroundService.onStartCommand` (line 48-58) does NOT help here because the crash happens *before* the service starts - at the `ContextCompat.startForegroundService()` call in `PassiveDataKit.initializeNotifications`.

**Severity:** Medium (affects any Android 12+ device where the config HTTP response arrives while app is backgrounded)

---

### Issue 5: SignInHubActivity.onCreate - NullPointerException
- **Events:** 1 | **Users:** 1 | **Version:** 88
- **Device:** OnePlus8Pro, Android 11
- **Date:** Mar 7, 2026, 1:55:27 PM (same device/session as Issue 3)
- **User ID:** Not set

**Exception:** `java.lang.NullPointerException: Attempt to invoke virtual method 'java.lang.Class java.lang.Object.getClass()' on a null object reference`

**Blame frame:** `SignInHubActivity.onCreate (com.google.android.gms:play-services-auth@@21.0.0:23)`

**Root cause:** Crash is entirely within Google Play Services `play-services-auth@@21.0.0`. Likely triggered by `GoogleSignIn.requestPermissions()` called from `RequestPermissionActivity` (line 54) after a null account from `GoogleSignIn.getLastSignedInAccount()`. This is a known GMS bug on some devices.

Note: This crash occurred 2.5 minutes after Issue 3 on the same device, suggesting the user retried after the first crash.

**Severity:** Low (Google's code, not directly fixable)

---

## Fix Plan

### Fix 1: Null-safe extras in RequestPermissionActivity (Issue 3)
**File:** `Passive-Data-Kit/.../RequestPermissionActivity.java`

Add null check for extras bundle in `onResume()` and `onRequestPermissionsResult()`. If extras is null (activity recreated after process death), finish the activity gracefully.

```java
// onResume() - line 39-41
Bundle extras = this.getIntent().getExtras();
if (extras == null) {
    this.finish();
    return;
}
String permission = extras.getString(RequestPermissionActivity.PERMISSION);
```

Same pattern in `onRequestPermissionsResult()` at line 63.

### Fix 2: Try-catch around startForegroundService in initializeNotifications (Issue 4)
**File:** `Passive-Data-Kit/.../PassiveDataKit.java`

Wrap `ContextCompat.startForegroundService()` at line 144 in a try-catch to handle `ForegroundServiceStartNotAllowedException` when the app is backgrounded.

```java
if (this.mStartForegroundService || this.mAlwaysNotify) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        try {
            ContextCompat.startForegroundService(this.mContext, intent);
            notificationStarted = true;
        } catch (Exception e) {
            Log.e("PDK", "Cannot start foreground service from background: " + e.getMessage());
            // Fall through to notification-only path below
        }
    }
}
```

This mirrors the existing try-catch pattern in `ForegroundService.onStartCommand` (lines 48-58).

### Fix 3: Update play-services-auth or guard GoogleSignIn call (Issue 5)
**File:** `Passive-Data-Kit/.../RequestPermissionActivity.java`

Optional/low-priority: Add null check for `GoogleSignIn.getLastSignedInAccount()` before calling `requestPermissions()` at line 54. Or update play-services-auth dependency.

```java
GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
if (account != null) {
    GoogleSignIn.requestPermissions(this, GOOGLE_FIT_PERMISSIONS_REQUEST_CODE, account, options);
} else {
    this.finish();
}
```

### Priority
1. **Fix 2** (initializeNotifications) - most likely to recur on any Android 12+ device
2. **Fix 1** (RequestPermissionActivity null extras) - defensive but low recurrence
3. **Fix 3** (GoogleSignIn null account) - lowest priority, Google's bug

# Participant Flow Documentation

This document describes all user-facing screens and flows in the Phone Dashboard Android app, including the exact text shown to participants.

## Table of Contents

1. [Onboarding Flow](#1-onboarding-flow)
2. [Main Usage Flow](#2-main-usage-flow)
3. [Warning/Blocker Flow](#3-warningblocker-flow)
4. [Budget Editing Flow](#4-budget-editing-flow)
5. [Settings Flow](#5-settings-flow)

---

## 1. Onboarding Flow

Sequential wizard that runs when the app first opens. Users must complete all steps before accessing the main app.

### Flow Overview

```
App Launch → Email Enrollment → App Explanation → Usage Permission →
Notification Permission → Overlay Permission → Conclusion → Main App
```

---

### Step 1: Email Enrollment

**Screen Title:** "Welcome!"
**Screen Subtitle:** "Phone Dashboard Setup"
**Button:** "Sign In"

#### Text Shown to User

> Welcome to Phone Dashboard. This app has been developed for the Harvard Smartphone Study.
>
> To continue, please enter your e-mail address below:

**Input Field:** "E-Mail Address"

#### Validation Dialogs

**If invalid email entered:**

| Element | Text |
|---------|------|
| Dialog Title | "Invalid E-Mail Address" |
| Dialog Message | "The provided e-mail address is not valid. Please enter a valid e-mail address." |
| Button | "Continue" |

**If enrollment fails:**

| Element | Text |
|---------|------|
| Toast | "Invalid identifier. Please try again." |

**If enrollment succeeds:**

| Element | Text |
|---------|------|
| Dialog Title | "Enrollment Successful" |
| Dialog Message | "You were successfully enrolled in the Phone Use study. Your app code is<br><br>**[8-DIGIT CODE]**<br><br>You will receive an e-mail with this code. Please record this 8-digit code in the survey to confirm your identity." |
| Button | "Continue" |

---

### Step 2: App Explanation

**Screen Title:** "About Phone Dashboard"
**Screen Subtitle:** "Phone Dashboard Setup"
**Button:** "Next ›"

#### Text Shown to User

> Welcome to Phone Dashboard! This app has been developed for the Harvard Smartphone Study and will allow us to track your phone usage.
>
> More specifically, the app will collect the following information from your phone:
>
> - Overall phone use
> - Use of specific apps
> - Battery life
> - How long your phone has been running
> - Phone hardware and software versions
>
> **To reiterate, your data will be kept private and stored securely.**

---

### Step 3: App Usage Permission

**Screen Title:** "App Usage Permission"
**Screen Subtitle:** "Phone Dashboard Setup"
**Button:** "Next ›"

#### Text Shown to User

> In order to function properly, Phone Dashboard needs permission to read your app usage data.
>
> In the next screen, you will be taken to your device's settings to give Phone Dashboard permission to access this information. When on the *Apps with usage access* screen, please scroll to and select *Phone Dashboard*.
>
> [Screenshot: usage-1.png]
>
> On the next screen after you select Phone Dashboard, you will see a switch labeled *Permit usage access*. Toggle that switch on to give permission.
>
> [Screenshot: usage-2.png]
>
> While our research team will have access to data on which apps you have installed and how often you use them, we will not be able to access any details on how you interact with your apps or information you put into them.
>
> Once this is complete, use the Android back button to return to this screen for your next steps.
>
> *Note that different device manufacturers may have screens that look different. If you have problems completing this step, please contact the research team for assistance.*

---

### Step 4: Notification Permission

**Screen Title:** "Enable Notifications"
**Screen Subtitle:** "Phone Dashboard Setup"
**Button:** "Next ›"

#### Text Shown to User

> In order to function properly, Phone Dashboard needs permission to monitor your app notifications.
>
> In the next screen, you will be taken to your device's settings to give Phone Dashboard permission to access this information. When on the *Device & app notifications* screen, please scroll to and select *Phone Dashboard*.
>
> [Screenshot: notifications-1.png]
>
> On the next screen after you select Phone Dashboard, you will see a switch labeled *Allow notification access*. Toggle that switch on to give permission.
>
> [Screenshot: notifications-2.png]
>
> Note that Phone Dashboard does not record the content of your notifications, only which apps are posting them and when those notifications are removed.
>
> Once this is complete, use the Android back button to return to this screen for your next steps.
>
> *Note that different device manufacturers may have screens that look different. If you have problems completing this step, please contact the research team for assistance.*

---

### Step 5: Overlay Permission

**Screen Title:** "Allow Window Management"
**Screen Subtitle:** "Phone Dashboard Setup"
**Button:** "Next ›"

#### Text Shown to User

> In order to function properly, Phone Dashboard needs permission to draw over the screen.
>
> In the next screen, you will be taken to your device's settings to give Phone Dashboard permission to draw over the screen. When on the *Display over other apps* screen, please scroll to and select *Phone Dashboard*.
>
> [Screenshot: screen-1.png]
>
> On the next screen after you select Phone Dashboard, you will see a switch labeled *Allow display over other apps*. Toggle that switch on to give permission.
>
> [Screenshot: screen-2.png]
>
> Once this is complete, use the Android back button to return to this screen for your next steps.
>
> *Note that different device manufacturers may have screens that look different. If you have problems completing this step, please contact the research team for assistance.*

---

### Step 6: Conclusion

**Screen Title:** "Final Notes"
**Screen Subtitle:** "Phone Dashboard Setup"
**Button:** "Done"

#### Text Shown to User

> **IMPORTANT:** You must keep the Phone Dashboard app installed and running properly in the background with permissions enabled until the end of the study, or you will lose your completion payment.
>
> For any questions, comments or complaints, please contact the Smartphone Research Team at smartphone.research@harvard.edu.
>
> For questions about your rights as a research subject, or if you would like to speak to someone independent of the research team, contact the Harvard Institutional Review Board (IRB) at cuhs@harvard.edu.

---

## 2. Main Usage Flow

The daily experience where participants view their app usage and budgets.

### Screen: MainActivity

**Tabs:**
- "Today" - Shows daily usage
- "Week" - Shows weekly usage

**Menu Buttons:**
- Settings (gear icon)
- "Review Budget" (only visible if treatment active)
- "Refresh Configuration"

### App Card Display

Each app card shows:

| Element | Example Text |
|---------|--------------|
| App name | "Instagram" |
| Usage today | "[duration] used" or "No usage observed." |
| Time remaining | "Time Remaining: [duration]" or "No usage restrictions." or "Budget exhausted until tomorrow." |

### Toast Messages

| Event | Toast Text |
|-------|------------|
| Refreshing config | "Refreshing configuration from server..." |
| Config update failed | "Unable to refresh configuration. Please check your network connection..." |
| Config update succeeded | "Configuration updated successfully." |

---

## 3. Warning/Blocker Flow

Triggered when app usage approaches or exceeds the set budget.

### Dialog Type A: Usage Warning (Approaching Limit)

**Title:** [App Name]

**Message (multiple minutes remaining):**
> Your daily limit for this app will expire in less than [X] minutes of additional usage today.

**Message (1 minute remaining):**
> Your daily limit for this app will expire in less than one minute of additional usage.

**Button:** "Continue"

---

### Dialog Type B: Budget Exceeded (Snooze Available)

**Title:** [App Name]

**Message (single app, with snooze):**
> You have reached your limit of 1 minute. You may resume using it in [X] minutes if you snooze the app limit.
>
> You can revise your app limits in Phone Dashboard, effective tomorrow.

**Message (multiple minutes limit, with snooze):**
> You have reached your limit of [X] minutes. You may resume using it in [Y] minutes if you snooze the app limit.
>
> You can revise your app limits in Phone Dashboard, effective tomorrow.

**Buttons:**
- "Snooze App Limit" (opens snooze dialog)
- "Continue"

---

### Dialog Type C: Snooze Active

**Title:** [App Name]

**Message:**
> You may resume using [App Name] after [time].

**Button:** "Continue"

---

### Dialog Type D: Budget Exceeded (No Snooze)

**Title:** [App Name]

**Message (single app):**
> You have reached your limit of 1 minute. You cannot use this app until tomorrow.
>
> You can revise your app limits in Phone Dashboard, effective tomorrow.

**Message (multiple minutes):**
> You have reached your limit of [X] minutes. You cannot use this app until tomorrow.
>
> You can revise your app limits in Phone Dashboard, effective tomorrow.

**Button:** "Continue"

---

### Snooze Dialog

**Title:** "Snooze App Limit"

**Message (free snooze):**
> You may resume using [App Name] after [time].
>
> How many additional minutes would you like?

**Message (costly snooze):**
> You may resume using [App Name] after [time] for $[cost].
>
> How many additional minutes would you like?

**Input Field:** "Additional Usage (Minutes)"

**Buttons:**
- "Snooze Limit"
- "Cancel"

**Toast (on success):**
> App usage limits will be temporarily lifted. You may resume using the app shortly.

**Toast (on parse error):**
> Could not parse provided value. Please try again...

---

## 4. Budget Editing Flow

### Initial Setup Introduction Dialog

**Title:** "Set App Limits"

**Message:**
> The limits that you choose on this screen will become active tomorrow.
>
> When you change your app limits in the future, those limits will become active on the NEXT calendar day.

**Button:** "Continue"

---

### Budget Screen

**Screen Title:** "App Usage Limits"
**Screen Subtitle:** "New limits effective tomorrow"

### App Card Display

| Element | Example Text |
|---------|--------------|
| App name | "Instagram" |
| Today's limit | "Daily usage limit: 30 minutes" or "No usage restrictions" or "Exempt from usage restrictions" |
| Tomorrow's limit | "Effective tomorrow: 45 minutes" or "Effective tomorrow: No usage limits" |

---

### Set Limit Dialog

**Title:** [App Name]

**Input Field:** "App Usage Limit (Minutes)"

**Buttons:**
- "Set Limit"
- "Clear Limit"

**Note shown below input:**
> Set the limit to "0" to disable usage of the app.

---

### Exempt App Dialog

**Title:** [App Name]

**Message:**
> This app is exempt from usage limits.

**Button:** "Continue"

---

### Save Confirmation (Initial Setup)

**Title:** "Set Initial Limits?"

**Message (single app):**
> You have set the initial limit for one app.
>
> These limits will be applied tomorrow.

**Message (multiple apps):**
> You have set the initial limits for [X] apps.
>
> These limits will be applied tomorrow.

**Buttons:**
- "Continue"
- "Cancel"

---

### Save Confirmation (Regular Edit)

**Title:** "App Limits Updated"

**Message:**
> Your new app limits will take effect tomorrow.

**Button:** "Continue"

---

### Unsaved Changes Dialog

**Title:** "Save Limits?"

**Message:**
> You have unsaved changes to your limits.
>
> Would you like to save them before exiting this screen?

**Buttons:**
- "Save"
- "Skip and Exit"

---

## 5. Settings Flow

### Settings Screen

**Screen Title:** "Settings"

### Available Settings

| Setting | Summary |
|---------|---------|
| Upload Data | "Send data to Phone Dashboard cloud." |
| Refresh Study Configuration | Fetches latest config from server |
| App Usage Limits | "Active" or "Inactive" |
| App Limit Type | "No App Limits" / "Free Snooze" / "Costly Snooze" / "No Snooze" |
| Snooze Delay | "[X] Minutes" or "Snooze Disabled" or "Snooze Immediately" |
| Change Snooze Delay | Opens snooze delay dialog |
| App Code | Shows participant's 8-digit code |
| Harvard IRB Number | "IRB-FY2020-3618" |
| Acknowledgements | Opens acknowledgements screen |

---

### Change Snooze Delay Dialog

**Title:** "Change Snooze Delay"

**Message:**
> Snooze Delay specifies the length of time you have to wait for snoozes to become effective when you hit the daily screen time limit for apps. Changes are effective tomorrow.

**Options:**
- Snooze After 1 Minute
- Snooze After 2 Minutes
- Snooze After 5 Minutes
- Snooze After 10 Minutes
- Snooze After 20 Minutes
- Snooze Immediately
- Disable Snoozes

**Confirmation Toasts:**
- "Your Snooze Delay of [X] minutes will become effective tomorrow."
- "No snooze will become effective tomorrow."
- "Snoozes will be disabled completely effective tomorrow."

---

### Opt-Out Dialog

**Title:** "Opt Out of Blocking?"

**Message:**
> Are you sure you want to opt-out of App Usage Limits?
>
> This permanently removes your current limits and you will not be able to impose any future limits until the configuration update at the next survey.

**Buttons:**
- "Yes, Opt Out"
- "Cancel"

**Toast (success):**
> You have successfully opted-out of this phase of the experiment.

**Toast (failure):**
> Unable to opt out at this moment. Please verify that you have an active network connection and try again...

---

## Navigation Summary

```
┌─────────────────┐
│   App Launch    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐     ┌─────────────────┐
│  Onboarding     │────▶│   MainActivity  │◀────────┐
│  (if needed)    │     │                 │         │
└─────────────────┘     └────────┬────────┘         │
                                 │                  │
              ┌──────────────────┼──────────────────┤
              │                  │                  │
              ▼                  ▼                  ▼
    ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
    │ SettingsActivity│ │EditBudgetActivity│ │ WarningActivity │
    └─────────────────┘ └─────────────────┘ │ (auto-triggered)│
                                            └─────────────────┘
```

---

## Blocker Types Reference

| Type | Display Name | Description | Snooze |
|------|--------------|-------------|--------|
| `none` | "No App Limits" | No usage blocking | N/A |
| `free_snooze` | "Free Snooze" | Can snooze without cost | Yes, free |
| `costly_snooze` | "Costly Snooze" | Snoozing costs virtual currency | Yes, with cost |
| `no_snooze` | "No Snooze" | Cannot bypass warnings | No |

---

## Notification Messages

| Notification | Title | Message |
|--------------|-------|---------|
| Attention Needed | "Attention Needed!" | "Tap to fix Phone Dashboard issue." |

---

## Event Logging Reference

All user interactions are logged for research purposes:

| Event | Description |
|-------|-------------|
| `app-block-warning` | Warning dialog shown |
| `app-blocked-can-snooze` | Blocked with snooze option |
| `app-blocked-delayed` | Blocked but snooze active |
| `app-blocked-no-snooze` | Blocked permanently |
| `snoozed-app-limit` | User activated snooze |
| `skipped-snooze` | User dismissed without snoozing |
| `cancelled-snooze` | User cancelled snooze dialog |
| `closed-warning` | User dismissed warning |
| `closed-delay-warning` | User dismissed snooze-active warning |
| `app-blocked-no-snooze-closed` | User dismissed no-snooze warning |
| `edit-budget-screen-visited` | User opened budget editor |
| `edit-budget-save` | User saved budget changes |
| `harvard-onboarding-complete` | User completed onboarding |

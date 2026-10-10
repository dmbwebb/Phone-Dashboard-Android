# Version 111: recover interrupted usage databases

Status: the corrected candidate passes independent review, all 88 JVM tests, the signed production build and the complete Android 14 and Android 16 retained-data recovery gates. Submitted to Google Play on 10 October 2026 at approximately 12:44 UTC; Publishing overview confirms release 111 under "Changes in review", with Google's automated checks still running. It is not yet published. Android 14 also showed a transient WebView/onboarding startup ANR, described below; Android 16 passed after two infrastructure failures were diagnosed and preserved. The first v111 candidate was rejected by its complete startup-log gate and has never been uploaded. The superseded v110 draft was withdrawn without submission. Earlier artifacts and failure evidence remain preserved.

## What changed

Collectors can encounter a database whose table or added column was created but whose schema-version marker was never saved. Most previous initializers committed these steps separately. On the next launch, repeating `CREATE TABLE history` or `ALTER TABLE` fails because that schema element already exists. A failed version write could also leave an inconsistent marker in the already transactional daily-aggregate initializer.

`ForegroundApplication` produces the fatal startup failure reported in Firebase issue `7a612a64cef90c6fe7847ca2cd665fb0`. The trace identifies the inconsistent database state, but does not establish what interrupted the original devices. Their relationship to study participants has not been established. The signed retained-data test also exposed interrupted `UsageStatsGenerator` initialization. That second failure was caught and logged, leaving the app running without its raw usage-event collector. The test includes both a deliberate crash and process stops between instrumentation phases; the exact interruption point was not established. This is why the intermediate [v110 candidate](release-110-database-recovery.md) was held.

At the 10 October approximately 11:55 UTC Firebase refresh, that issue still had five events across two reported users on OnePlus devices running Android 11 and v109. Its latest event remained 8 October 23:31:11 UTC; there was no newer event in that issue.

The first v111 signed candidate fixed those two databases but failed the complete startup-log gate: `DailyBudgetGenerator` had another fatal `history already exists` error, and `ScreenState` logged the same error during generator startup. This candidate could upload a queued record while its activity crashed. That is insufficient evidence of recovery.

The corrected candidate covers every initializer in the app's enabled inventory: AppEvent, Battery, ScreenState, ForegroundApplication, UsageStats, DailyUsageAggregate, SystemStatus, User, NotificationEvents, DailyBudget, AppSnooze, SnoozeDelay and DailySurvey. Each database now starts a transaction before reading or creating metadata, creates only missing schema elements, verifies that the final version marker was saved, and commits those changes together. Failed initialization rolls back and closes its database connection. Existing rows, foreground substitutions and queued uploads remain intact. Daily aggregate delivery flags remain intact; rows gaining that column receive its existing default of zero, so migration does not replay old aggregates. Completed and newer schema markers are preserved. Inactive PDK initializers are unchanged.

ScreenState and DailySurvey previously dropped their old active table during certain migrations. A current-shaped table with a stale marker now remains live. An incompatible legacy table is renamed inside the same database, with an unused archive name, before the intended empty current table is created. Its original rows remain available for inspection; they are not guessed into new fields or automatically replayed. A failed version write rolls back that rename and creation together. ScreenState's historical reset dates to its 2016 introduction of SQLite; no particular earlier integer-valued SQLite schema is claimed.

The version-marker check matters because Android's `SQLiteDatabase.insert` can return `-1` without throwing, and an update can affect no rows. See the official [SQLiteDatabase reference](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase).

The corrected source and tests are committed and pushed on `duncan/v111-usage-database-recovery-20261010`: [app `3c4d49d`](https://github.com/dmbwebb/Phone-Dashboard-Android/commit/3c4d49d5ec80e1c05880e681400f6ca44c2b2780) and [PDK `21b82e4`](https://github.com/dmbwebb/PassiveDataKit-Android/commit/21b82e400135037abc50d97bd6513de58c494d7a). The first candidate's earlier commits remain in history.

The final fixture-only delivery assertion is [app `5f4b825`](https://github.com/dmbwebb/Phone-Dashboard-Android/commit/5f4b8253434b6fe17d0006c202e56ce6ce379224); it changes neither the production source nor the frozen APK/AAB.

## Verification

The foreground regression reproduced three failures in four tests on unchanged v109. It covers 34 interrupted migration states, retained history/substitutions, completed schemas and rollback when the version write is rejected. The new raw-event database regression also reproduces three failures in four tests before its fix: existing history with a missing marker, an unreported failed version write, and overwriting a newer marker. Both regression classes use Robolectric's Android 11/API 30 model.

The first two fixes passed the complete release JVM suite: 70 tests, zero failures, zero errors and zero skips. The expanded suite adds grouped tests for the other eleven initializers: missing, empty and stale metadata; fresh, current and future schemas; every additive column boundary; exact retained rows and delivery flags; unique keys; legacy archive preservation and collisions; refused version insertion and update with rollback and retry; and metadata creation rollback.

The final expanded suite passes: **88 tests across 21 classes, zero failures, errors or skips**, in 5 minutes 25 seconds. Its log is `/private/tmp/mrd-v111-all-initializers-jvm2.log`; XML results are in `app/build/test-results/testReleaseUnitTest/`. The first expanded attempt stopped at test compilation because PDK resource names used the app's non-transitive resource namespace; correcting the test imports required no production change. Independent review found no blocking issue in the production diff, grouped tests or signed fixture lifecycle.

The signed release fixture explicitly seeds all thirteen inconsistent v109 databases and retains a recent synthetic record in each, plus a foreground substitution. It reproduces the original fatal startup and queues an additional record before installing the new APK with `adb install -r`. Before staging that update it restores interrupted version markers in case v109 crash logging initialized a database; it never clears history or the original queue. The update must complete normal startup for all twelve fixture-enabled collectors, preserve every seeded row and repaired version marker, and deliver the original queued record to a local receiver. New daily aggregate collection is disabled to keep this fixture bounded. Its pre-upgrade pending aggregate must still be delivered with its original observation time and values; a subsequent inspection requires its row retained and pending flag cleared. The unit test independently verifies that migration preserves a pending flag of one before any uploader runs. This distinction matters because uploader initialization replays pending aggregates even when new aggregation is disabled.

The final cold start must also collect and upload a new raw Android usage event whose observation time is after the launch cutoff. A queued test marker alone cannot satisfy that gate. The real transmitter waits five minutes between unforced attempts, while Android normally schedules its periodic job every fifteen minutes. The fixture retains the five-minute throttle, then asks Android to run that existing periodic job, with a seven-minute bound on the receiver check. It does not create a raw record through instrumentation or restart the collector to make the check pass.

The driver rejects SQLite schema errors across captured post-upgrade startup logs, including errors caught by background-generator startup, and checks native-library operation and a compressed upload. No synthetic records or crash telemetry are sent to production: the dedicated emulator must confirm airplane mode enabled and Wi-Fi disabled before APK installation, and receivers use localhost through `adb reverse`. This isolation uses Android's documented [connectivity shell command](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/service/src/com/android/server/ConnectivityService.java).

Local baseline evidence is in `/private/tmp/mrd-v110-red-tests.{log,xml}` and `/private/tmp/mrd-v111-red-tests.{log,xml}`. The rejected first candidate's build output is in `/private/tmp/mrd-v111-production-build.log`; that build passed including `lintVitalRelease` in 10 minutes 53 seconds. Its separate test APK build is recorded in `/private/tmp/mrd-v111-instrumentation-build.log`.

The second fixture build is `/private/tmp/mrd-v111-instrumentation-build2.log` (4 minutes 58 seconds). Its gzip-marker preparation deactivates the old uploader and uses a test-only dormant uploader to save the marker without sending it. The subsequent normal app process creates its unmodified production uploader. This removes a race between the earlier configuration refresh's upload and marker staging without changing the release APK or the raw-event collection check; this staging method remains in the expanded fixture.

Preliminary Android 14 fixture runs are retained. `/private/tmp/mrd-v111-recovery-api34/` stopped when the baseline v109 instrumentation process died during emulator startup; its exact stack was not captured. The same APKs passed that stage on retry. Android's persisted diagnostics and live retry log also showed ANRs across System UI, the launcher, phone and Google services. `/private/tmp/mrd-v111-recovery-api34-run2/` passed retained databases, the original queue, native bindings and the gzip marker, but its original one-minute fresh-event deadline expired before the transmitter's five-minute throttle. `/private/tmp/mrd-v111-recovery-api34-run3/` passed the retained database inspection, then waited for the original upload after Android had killed the finished instrumentation process. Its saved log identifies that process termination; it contains no SQLite, fatal or native error. The host driver now explicitly opens the normal app after the inspection instead of relying on background upload completing before instrumentation exits. None of these attempts is reported as a complete pass. The driver timestamps progress, records service state and waiting logcat, continuously captures all post-upgrade phases, and captures full logcat on exceptions or timeouts before teardown.

The fourth Android 14 attempt, `/private/tmp/mrd-v111-recovery-api34-run4/`, rejected the first signed candidate because complete logs contained the DailyBudget fatal and ScreenState schema failure described above. Its continuous and phase logs are preserved. The corrected candidate is rebuilt into a different directory without overwriting these files or hashes.

The corrected candidate passed the complete Android 14/API 34 recovery driver at 12:19:31 UTC on 10 October, on ARM64 with 4 KB memory pages. All thirteen histories and schema markers survived, all twelve fixture-enabled collectors started, the original queued upload and pending aggregate arrived intact, the aggregate pending flag cleared, native bindings worked, and the normal cold start delivered a gzip upload and a genuinely new raw usage event. The raw event's `observed` value was `1791634420978`, after the strict device-clock cutoff `1791634419000`. There were eleven localhost upload requests. Evidence is in `/private/tmp/mrd-v111-candidate2-recovery-api34/`, including `result.json`, `fresh-raw-event.json`, `retained-aggregate.json` and continuous logs. No post-upgrade SQLite schema error, fatal exception or native linkage error was captured.

This Android 14 run was not free of startup delays. Its synthetic account had identity and monitoring configuration but lacked completed onboarding preferences and notification-listener/battery access. It therefore opened OnboardingActivity and its WebView on normal launches. Two app watchdog warnings occurred there, including one in the final normal process; Android also reported that process's delayed `onStartJob` callback. The main thread was inside WebView startup. The final activity rendered after 30.3 seconds, its main thread continued rendering, and the same process remained alive through the subsequent fresh-event delivery with no repeated ANR report over approximately five minutes. The emulator showed substantial internal scheduling pressure. This establishes recovery from that transient startup delay, not an absence of ANRs or a physical-device performance result. The evidence remains preserved; the test was not rerun to remove the warning. A future fixture representing completed enrollment must set the existing explanation/conclusion preferences, grant actual notification-listener access (not merely notification permission), battery exemption and usage access, and verify `outstandingIssues` is empty.

The first corrected-candidate Android 16 attempt failed before v111 was installed. The baseline v109 instrumentation process could not complete startup, amid repeated ANRs in System UI, phone, launcher, Google services, input method and media services, plus native Bluetooth/service aborts. The 2 GB guest showed memory and I/O pressure; the host had approximately 80% memory availability, 59% idle CPU and no current swap I/O. That infrastructure failure is preserved in `/private/tmp/mrd-v111-candidate2-recovery-api36/` and in the candidate directory's `api36-infrastructure-failure/`. The bounded retry gives this guest 4 GB RAM, keeps two cores and background priority, and requires Android's broadcast loopers and queues to confirm they are idle within 120 seconds after radio isolation, before installing either APK. The readiness command and success message come from Android's [ActivityManager implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/am/ActivityManagerService.java). It changes no release code or recovery assertion.

That infrastructure retry also failed: Android did not confirm broadcast-idle readiness within 120 seconds, and its full log still showed repeated systemwide ANRs. Neither APK was installed in that retry. Evidence is in `/private/tmp/mrd-v111-candidate2-recovery-api36-run2/`, also preserved as `api36-readiness-failure/` beside the artifacts.

A third attempt tested a distinct infrastructure explanation: Darwin background QoS can starve child processes even while host CPU is idle. This emulator-only run omitted `taskpolicy -b` while retaining `nice -n 19`, two cores, 4 GB RAM, identical isolation and readiness checks, and every recovery assertion. Both driver and emulator were verified at nice 19; the snapshot is `host-priority.txt` in `/private/tmp/mrd-v111-candidate2-recovery-api36-run3/`. Other jobs and all release artifacts remained unchanged. The different RAM/readiness conditions and accumulated AVD state mean the comparison does not isolate QoS as a proven cause.

The third Android 16/API 36 attempt passed completely at 12:41:20 UTC on 10 October, on ARM64 with 16 KB memory pages. It reproduced v109's fatal, repaired and retained all thirteen databases, started all twelve fixture-enabled collectors, delivered the original queued record and exact pending aggregate with its flag cleared, passed native bindings and gzip upload checks, and collected a genuinely new raw event. That event's `observed` value was `1791635771275`, after cutoff `1791635771000`; eleven localhost requests arrived. No app ANR/watchdog warning, SQLite schema error, fatal exception or native linkage error was captured in the post-upgrade logs. The final normal process remained alive and its OnboardingActivity rendered in 406 milliseconds. Evidence is in `/private/tmp/mrd-v111-candidate2-recovery-api36-run3/`, also preserved as `api36-evidence/` beside the artifacts. The Android 14 evidence is likewise preserved as `api34-evidence/`.

## Corrected candidate artifacts

The corrected files are frozen separately in `/Users/duncanwebb/Downloads/MRD-v111-20261010-candidate2/`:

- `MiRitmoDigital-v111.aab`: SHA-256 `92eb7d0f422c3a3f7965d87a07e47857917b45ade247ae7eb63931f6ed0b6d89`.
- `MiRitmoDigital-v111.apk`: SHA-256 `5eeb7023f6efa7202713ff806e08bade2005bd81003707acdbe18bcbe7a4de1a`.
- `MiRitmoDigital-v111-instrumentation.apk`: SHA-256 `f350b756543afbde954d41221ccd07c8d48f8928c858343a14e6dab9aa3b72d4`. This test-only APK is never uploaded to Play.
- Signing certificate SHA-256: `a4f38dc9dfca09ac12f07aacfdea431f2237cfe93870e99bea2825112dbea54a`, unchanged from v109.

The APK reports package `com.miritmodigital.app`, version code/name `111`, and `debuggable=false`. APK signature verification and AAB JAR verification exit successfully; JAR verification reports self-signed certificate and archive ordering warnings. Both DEX files are byte-identical between the APK and AAB. DEX inspection finds no fixture identity, retained synthetic application, queued marker or instrumentation test class in either production artifact. The production mapping file is preserved beside the artifacts. Build output is `/private/tmp/mrd-v111-candidate2-production-build.log`: successful, including `lintVitalRelease`, in 7 minutes 9 seconds. The final separate instrumentation build is `/private/tmp/mrd-v111-candidate2-instrumentation-build2.log`: successful in 3 minutes 6 seconds. It did not overwrite the frozen production files. Both signed upgrade gates pass with the qualifications recorded above; these are the verified release-candidate artifacts for root's Play submission.

## Rejected first candidate artifacts

Files are in `/Users/duncanwebb/Downloads/MRD-v111-20261010/`:

- `MiRitmoDigital-v111.aab`: SHA-256 `58aa0b838e26b526307539a246361107724634baa5bdf1bd8beb7e253634d823`.
- `MiRitmoDigital-v111.apk`: SHA-256 `17043882aa62b8ce57d1121704d449297e82202e1c1babc268d81ca7c6d4016c`.
- Signing certificate SHA-256: `a4f38dc9dfca09ac12f07aacfdea431f2237cfe93870e99bea2825112dbea54a`, unchanged from the preceding release.

The APK reports package `com.miritmodigital.app`, version code/name `111`, and `debuggable=false`. APK signature verification and AAB JAR verification succeed. DEX inspection of both artifacts finds no synthetic fixture identity, queued test marker or instrumentation test class. These files were copied before the separate instrumentation build and remain unchanged as rejected-candidate evidence. They must not be uploaded.

## Reproducing release checks

Run heavy work serially at low priority:

```sh
JAVA_HOME=~/micromamba/envs/java/lib/jvm nice -n 19 taskpolicy -b \
  ./gradlew --offline --no-daemon --max-workers=1 \
  :app:testReleaseUnitTest :app:assembleRelease :app:bundleRelease
```

Copy the verified production APK/AAB into a separate versioned Downloads directory before building the fixture APK. The `compressionRecoveryE2E` setting belongs only to that separate instrumentation build.

```sh
JAVA_HOME=~/micromamba/envs/java/lib/jvm nice -n 19 taskpolicy -b \
  ./gradlew --offline --no-daemon --max-workers=1 -PcompressionRecoveryE2E=true \
  :app:assembleReleaseAndroidTest
nice -n 19 taskpolicy -b python3 tools/foreground_database_recovery_emulator_e2e.py \
  --baseline ~/Downloads/MRD-v109-20261008/MiRitmoDigital-v109.apk \
  --current ~/Downloads/MRD-v111-20261010-candidate2/MiRitmoDigital-v111.apk \
  --test-apk app/build/outputs/apk/androidTest/release/app-release-androidTest.apk \
  --avd MRD_API34_Reliability --serial emulator-5580 \
  --output /private/tmp/mrd-v111-candidate2-recovery-api34
```

The successful Android 16 run used the following command, keeping low CPU priority without additional Darwin background QoS:

```sh
nice -n 19 python3 tools/foreground_database_recovery_emulator_e2e.py \
  --baseline ~/Downloads/MRD-v109-20261008/MiRitmoDigital-v109.apk \
  --current ~/Downloads/MRD-v111-20261010-candidate2/MiRitmoDigital-v111.apk \
  --test-apk ~/Downloads/MRD-v111-20261010-candidate2/MiRitmoDigital-v111-instrumentation.apk \
  --avd MRD_API36_16K --serial emulator-5580 \
  --memory-mb 4096 --wait-for-system-idle \
  --output /private/tmp/mrd-v111-candidate2-recovery-api36-run3
```

Use a new output directory when repeating a check; the driver refuses existing directories. The readiness check establishes that broadcast queues and loopers settled at that moment, not that all Android activity has stopped. The fixture retains the existing compression harness's setting that disables daily aggregate generation; it proves new raw-event collection, not every production collector's output. No Android 11 emulator image is installed locally; these signed emulator runs cannot establish OnePlus-specific behavior. They also do not establish whether a particular participant's historical data has returned; that requires the separate production-data audit. Global upload compression remains off.

## Historical-data recovery limits

Version 111 keeps the existing recovery policy. [Raw usage-event collection](https://github.com/dmbwebb/PassiveDataKit-Android/blob/e6089b164d2756a702fbf51ae34fad9bdcd8d61b/java/src/com/audacious_software/passive_data_kit/generators/device/UsageStatsGenerator.java#L188) resumes one millisecond after the later of the latest local record and seven days before the current time. [Daily totals](https://github.com/dmbwebb/PassiveDataKit-Android/blob/e6089b164d2756a702fbf51ae34fad9bdcd8d61b/java/src/com/audacious_software/passive_data_kit/generators/device/DailyUsageAggregateGenerator.java#L57) have a default thirty-day lookback. These are query limits, not a guarantee that Android still has every observation. Already queued payloads have no age cutoff and are retained until the server acknowledges them.

The 10 October 2026 private production recovery audit observed about four hours of actual raw-event backfill from one v109 phone. That establishes that recovery can occur; it does not establish recovery for the phones affected by the original interruption. At the final 12:42 UTC refresh, the originally affected phone roles still had no observed v109 uploads. The private audit holds the device-level evidence; none is included here.

## Separate Google test-crawler failure

Firebase issue `31e4148a1ff64120e09bcd92b84e229b` is a separate `FocusRingDrawable.wrap` linkage failure. The trace identifies the declaring class as loaded from Google's `androidx.test.tools.crawler` APK, while the signed v109 app contains the exact expected method. This is a test-crawler library conflict, not an observed failure from an ordinary participant installation. Version 111 does not change UI dependencies. Raw Firebase traces remain local and are not committed.

## Google Play release notes

Corregimos errores que podían cerrar la app o interrumpir el registro de uso. La actualización conserva los datos guardados y ayuda a reanudar su sincronización.

## Google Play submission

The verified candidate2 AAB was uploaded at approximately 12:42 UTC on 10 October 2026, after both signed upgrade gates passed. Its SHA-256 was independently rechecked immediately before upload and remains `92eb7d0f422c3a3f7965d87a07e47857917b45ade247ae7eb63931f6ed0b6d89`. Play recognized version code/name 111, target SDK 36, and attached mapping/native symbols. The preview reported no change in supported devices.

The production release retains the existing 100% rollout and all already targeted countries (Colombia); no availability settings were changed. "Send changes for review" was confirmed at approximately 12:44 UTC, and [Publishing overview](https://play.google.com/console/u/0/developers/5689648767328885776/app/4973458947249947064/publishing) then showed **Changes in review: Production, 111 (111), Start full rollout**. Automated checks were still running, with review to follow successful completion. Managed publishing remains off, so approval will permit automatic publication. This is a submission receipt, not evidence that participants can already install v111; v109 was the live release at submission.

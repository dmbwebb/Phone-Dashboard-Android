# Version 110: recover interrupted usage-database initialization

Status: superseded before Play submission. The foreground-database fix passed its assertions, but inspection of the signed upgrade's logs exposed the same interrupted-initialization defect in `UsageStatsGenerator`. Version 111 extends recovery to the complete startup inventory and requires successful raw-event collection before release. The v110 draft was withdrawn; its bundle remains in Play's artifact library and its local artifacts are retained for provenance. Version 110 was never submitted for review or released to participants.

## Diagnosis

Firebase issue `7a612a64cef90c6fe7847ca2cd665fb0` records `SQLiteException: table history already exists` at `ForegroundApplication.<init>`, reached from `BudgetAdapter` while opening the app. Five events on two OnePlus 8 Pro / Android 11 devices were recorded on v109; their relationship to study participants has not been established. The latest downloaded event is 8 October 2026 at 23:31:11 UTC.

The saved schema version is zero even though `history` already exists. The legacy constructor executes each schema statement separately, then saves version 7 after all seven statements. An interruption or failed version write leaves committed schema changes with an old/missing marker. The next launch repeats the initial `CREATE TABLE` or an already-applied `ALTER TABLE` and crashes. The singleton and constructor already synchronize threads; they do not make the database migration atomic. The trace identifies the inconsistent state, but does not establish what originally interrupted these devices.

The recovery preserves existing rows and substitutions. It resumes only missing schema additions and commits them together with a verified version marker in one SQLite transaction. Failure rolls back the new changes and closes the unsuccessful connection. No table is dropped, no usage data is deleted, and global upload compression stays off.

Android documents that transactions roll back unless marked successful and ended; `insert` can return `-1` instead of throwing. The version marker must therefore be checked before committing. See [SQLiteDatabase](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase).

## Verification plan and evidence

- JVM regression: reproduce the missing-version/history-present failure; exercise all interrupted migration boundaries, retain history and substitutes, reopen version 7, and inject version-write failure to prove full rollback and retry.
- Signed, minified release: seed the inconsistent database in v109 on an isolated emulator and reproduce the normal app-start crash. Install v110 with `install -r`, retaining the database, identity and upload queue. Verify the original history row, added columns and version marker, and receive the pre-upgrade queued record locally.
- Launch the real app normally after repair and verify a compressed queued upload plus native cryptography; this guards against regressing v109's independent compression recovery.
- Emulators have Wi-Fi/mobile data disabled and local receivers reachable through `adb reverse`; no synthetic records or crash telemetry go to production. The driver uninstalls fixtures at teardown. Test classes remain in the separate instrumentation APK.

On 10 October 2026 the four regression tests reproduced three failures on unchanged v109: the exact history-already-exists exception at constructor line 169, the same error at the first interrupted migration boundary, and failure to report a rejected version-marker insert. With the fix, all 66 release JVM tests pass. The boundary test covers 34 combinations of committed schema statements and stale version markers. The JVM tests run with the Android 11/API 30 model.

The default production `assembleRelease` and `bundleRelease` build passed, including `lintVitalRelease`. The package is `com.miritmodigital.app`, version code/name `110`, with debugging disabled and the same signer as v109. Synthetic fixture classes and identities are absent from both APK and AAB DEX files. Artifacts are copied to `~/Downloads/MRD-v110-20261010/` before building the separate test APK:

- `MiRitmoDigital-v110.aab`: SHA-256 `b2339b5e1de39c5e2c8b06adb3a716ab1a6c5bb34a7bb8258bc0b104e6e56d14`.
- `MiRitmoDigital-v110.apk`: SHA-256 `14fdafd9472b51361efd10996a5ba1aae73ad11d9254fa4d2b69a68869741204`.
- Signing certificate SHA-256: `a4f38dc9dfca09ac12f07aacfdea431f2237cfe93870e99bea2825112dbea54a`.

Local evidence: `/private/tmp/mrd-v110-red-tests.{log,xml}`, `/private/tmp/mrd-v110-production-build.log`, and `/private/tmp/mrd-v110-instrumentation-build.log`.

The signed retained-data upgrade passed on Android 14/API 34, arm64, with 4 KB memory pages. Normal v109 startup reproduced the reported crash; installing v110 over it retained the original history row and delivered the record queued before the update. Native-library checks and a normal v110 cold start with a compressed upload also passed. Evidence is in `/private/tmp/mrd-v110-recovery-api34-run2/`, including `result.json`, instrumentation output, startup logs and the local receiver journal.

Those assertions did not establish that every collector started. The same run logs a nonfatal `SQLiteException: table history already exists` from `UsageStatsGenerator`; its raw-event collector could not initialize. The test exercises a fatal startup and process stops between instrumentation phases, either of which can interrupt nontransactional initialization; the precise interruption point was not established. This finding blocks v110 submission. Two Android 16 runs stopped before APK installation because the emulator phone service could not disable mobile data; they provide no app test result. The revised fixture uses positively verified airplane mode and Wi-Fi isolation.

The app source commit is `c1860e11ae31336367b673ef5ec7e545bc7e99d5`; its PDK submodule commit is `1f5d7ae161416ee153cf532469e5b24b7d6b708d`. Both are pushed on `duncan/v110-database-recovery-20261010` in their respective repositories.

The fixture has since been strengthened to require all thirteen databases' recovery and new raw-event collection on v111; use the commands in [the v111 release record](release-111-database-recovery.md). The local v110 output above preserves the original, narrower assertions. An Android 11 device image is not installed locally; these emulator tests cannot establish OnePlus-specific behavior.

## Separate test-crawler issue

Firebase issue `31e4148a1ff64120e09bcd92b84e229b` records one `NoSuchMethodError` for Material's `FocusRingDrawable.wrap` on an Android 11 `Sdk_gphone_arm64` emulator at 04:26 UTC on 10 October. The missing method's class is explicitly loaded from `androidx.test.tools.crawler`'s APK, and the trace contains `CrawlPlatform` and `AndroidJUnitRunner`. DEX inspection of the signed v109 release APK confirms it contains the exact `public static wrap(Context, Drawable): Drawable` method. This is a test-crawler library conflict; it is not an observed crash from an ordinary participant installation. The database fix does not change UI dependencies or suppress this report. Raw Firebase traces are kept locally, not committed.

## Google Play release notes

Corregimos un error que podía cerrar la app al abrirla. La actualización conserva los datos guardados y ayuda a reanudar su sincronización.

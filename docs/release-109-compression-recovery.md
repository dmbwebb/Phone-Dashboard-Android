# Version 109: recovery from compressed-upload crashes

Version 108 could crash before making an HTTP request when a cached configuration enabled compression. Its Base64 helper initialized LazySodium, which loaded JNA. R8 had removed the `Native.fromNative` callbacks used by JNA's native code. Firebase issue `4a6288d5b0edfdcea09c415cf2d393ab` records the resulting `UnsatisfiedLinkError` at `Native.initIDs`, `Toolbox.<clinit>` and `HttpTransmitter.transmitHttpPayload`.

Version 109 isolates native initialization inside the cryptography methods and preserves JNA and LazySodium bindings in the app's effective release shrinker rules. Uploads also contain `LinkageError`, including a subsequent `NoClassDefFoundError` from a failed initializer. That error follows the existing visible failure and retry path; the upload is never acknowledged and the queued records remain on disk. Cached monitoring still starts without requiring network connectivity.

The Android test framework dependencies are restricted to the test configurations. Previously they also appeared in production dependencies, causing release instrumentation to omit the test runner from its APK even though the release shrinker had removed it from the application.

The server's global compression setting remains off. This app fix does not change it.

## Release verification

The release test uses the real signed, minified APK and a separate instrumentation APK. It first reproduces the v108 crash with a known synthetic record and cached compression enabled. Android then installs v109 with `install -r`, keeping the same identity, preferences and upload queue. The receiver must decode that original record from gzip. The app subsequently fetches a configuration with compression disabled and delivers an uncompressed record. Separate tests verify the native SHA-512 hash and nonce functions.

A final check stages another compressed upload, stops instrumentation, and launches the actual `MainActivity` and `AppApplication` normally. The retained record must reach the receiver and the app must stay alive. The emulator's Wi-Fi and mobile data are disabled throughout; local receivers remain reachable through `adb reverse`. Production services receive no synthetic data. Fixtures are uninstalled at teardown.

The fixture replaces configuration resources only inside its test Application. It does not alter production resources, disable release shrinking, or change the production package. The last cold-start check uses the normal Application, with the cached local upload endpoint. It establishes offline cached recovery, not Google Play's automatic update timing or production TLS behavior.

Build the test APK:

```sh
JAVA_HOME=~/micromamba/envs/java/lib/jvm nice -n 19 taskpolicy -b \
  ./gradlew --no-daemon --max-workers=1 -PcompressionRecoveryE2E=true \
  :app:assembleReleaseAndroidTest
```

Build the production artifacts without test properties:

```sh
JAVA_HOME=~/micromamba/envs/java/lib/jvm nice -n 19 taskpolicy -b \
  ./gradlew --no-daemon --max-workers=1 \
  :app:assembleRelease :app:bundleRelease :app:testReleaseUnitTest
```

Then run the staged test against a dedicated emulator, supplying a signed v108 APK built before the fix. The baseline must use the same signing key as v109:

```sh
nice -n 19 taskpolicy -b python3 tools/compression_recovery_emulator_e2e.py \
  --baseline /path/to/v108-baseline.apk \
  --current app/build/outputs/apk/release/app-release.apk \
  --test-apk app/build/outputs/apk/androidTest/release/app-release-androidTest.apk \
  --avd MRD_API34_Reliability --serial emulator-5580 \
  --output /private/tmp/mrd-compression-recovery-20261008/api34
```

Do not run all methods of `CompressionRecoveryInstrumentedTest` as one suite. The seed method deliberately crashes v108 and the driver controls the installation boundary. Never run this fixture on a participant's phone.

Verified on 8 October 2026:

- The default signed release APK and AAB build successfully; all 62 release JVM tests pass.
- Android 14, API 34, arm64: the staged v108-to-v109 upgrade, gzip delivery, downloaded configuration rollback, native hash/nonce, and normal application cold start all pass.
- The package is `com.miritmodigital.app`, version code/name `109`, with debugging disabled. Fixture classes and the synthetic identity are absent from the production DEX files.
- The unchanged signing certificate SHA-256 is `a4f38dc9dfca09ac12f07aacfdea431f2237cfe93870e99bea2825112dbea54a`.

The verified default-build AAB SHA-256 is `fd1723c5d8fcccadff64f7dad5729e734c8dadb6b6338fc2dd74e3e2464c4f27`; the APK SHA-256 is `96e30e605a83367ad634f15bddfccab5f5911c20e322847a1db2d6d20f9bba20`. Local release copies are in `~/Downloads/MRD-v109-20261008/`. Build logs and API 34 test evidence are under `/private/tmp/mrd-compression-recovery-20261008/` (`production-build.log`, `api34-run3/`).

## Google Play release notes

Corrige un error que podía cerrar la app e impedir el envío de datos. Permite recuperar los datos pendientes al actualizar.

#!/usr/bin/env python3
"""Reproduce v109's partial-schema failures and verify a data-preserving v111 update.

Only dedicated emulators, synthetic records and localhost receivers are allowed.
The signed minified production APKs run with radios off, including normal starts.
"""
import argparse
from datetime import datetime, timezone
import http.server
import json
from pathlib import Path
import re
import ssl
import subprocess
import threading
import time

from compression_recovery_emulator_e2e import Receiver, PKG, RUNNER, TEST as COMPRESSION_TEST
from reliability_emulator_e2e import ADB, SDK, adb, command, wait_boot

DATABASE_TEST = "com.audacious_software.phone_dashboard.ForegroundDatabaseRecoveryInstrumentedTest"
RETAINED_MARKER = "queued-before-v111-database-recovery"


def progress(message):
    print(datetime.now(timezone.utc).isoformat(timespec="seconds") + " " + message, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--current", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--avd", default="MRD_API34_Reliability")
    parser.add_argument("--serial", default="emulator-5580")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise ValueError("Physical devices are forbidden")
    if args.serial in command([ADB, "devices"]):
        raise ValueError("Emulator serial is occupied; refusing to touch it")
    args.output.mkdir(parents=True, exist_ok=False)
    Receiver.journal = args.output / "http-journal.jsonl"
    Receiver.compression = False
    certificate = args.output / "fixture-cert.pem"
    key = args.output / "fixture-key.pem"
    command(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
             "-keyout", str(key), "-out", str(certificate), "-config",
             str(Path(__file__).with_name("compression_fixture_tls.cnf"))])
    # Android keeps stable fixture URLs; ephemeral host ports avoid occupying
    # services such as AnkiConnect that already listen on localhost:8765.
    plain = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Receiver)
    secure = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Receiver)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(certificate, key)
    secure.socket = tls.wrap_socket(secure.socket, server_side=True)
    for server in (plain, secure):
        threading.Thread(target=server.serve_forever, daemon=True).start()

    def instrument(test, method):
        output = adb(args.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class",
                     test + "#" + method, RUNNER, timeout=150)
        (args.output / (method + ".txt")).write_text(output)
        if "OK (1 test)" not in output:
            raise AssertionError("Instrumentation failed: " + method + "\n" + output)
        progress("PASS " + method)
        return output

    def delivered(marker, compression):
        with Receiver.lock:
            return any(row.get("marker") == marker
                       for request in Receiver.requests if request["compression"] == compression
                       for row in request["records"])

    def await_delivery(marker, compression):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if delivered(marker, compression):
                return
            time.sleep(1)
        raise AssertionError("Retained record did not arrive: " + marker)

    def retained_aggregate(observed):
        with Receiver.lock:
            for request in Receiver.requests:
                for row in request["records"]:
                    metadata = row.get("passive-data-metadata", {})
                    if (metadata.get("generator-id") == "pdk-daily-usage-aggregate"
                            and row.get("package") == "retained.synthetic.app"
                            and row.get("observed") == observed
                            and row.get("day_bucket") == observed
                            and row.get("total_ms") == 60000):
                        return row
        return None

    def await_retained_aggregate(observed):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            row = retained_aggregate(observed)
            if row is not None:
                (args.output / "retained-aggregate.json").write_text(json.dumps(row, indent=2))
                return
            time.sleep(1)
        raise AssertionError("Pre-upgrade pending aggregate was not delivered")

    def isolate_network():
        # Connectivity's airplane-mode command works on images without a phone
        # service. Never install a fixture until airplane mode and Wi-Fi state
        # positively confirm isolation; allow bounded boot-service readiness.
        deadline = time.monotonic() + 60
        while True:
            try:
                adb(args.serial, "shell", "cmd", "connectivity", "airplane-mode", "enable", timeout=15)
                adb(args.serial, "shell", "svc", "wifi", "disable", timeout=15)
                airplane = adb(args.serial, "shell", "cmd", "connectivity", "airplane-mode", timeout=15).strip()
                wifi = adb(args.serial, "shell", "settings", "get", "global", "wifi_on", timeout=15).strip()
                if airplane != "enabled" or wifi != "0":
                    raise RuntimeError("Network isolation failed: airplane=" + airplane + ", wifi=" + wifi)
                (args.output / "network-isolation.json").write_text(json.dumps({"airplane_mode": airplane, "wifi_on": wifi}))
                progress("PASS airplane mode enabled and Wi-Fi disabled before APK installation")
                return
            except (RuntimeError, subprocess.TimeoutExpired):
                if time.monotonic() >= deadline:
                    raise
                time.sleep(2)

    def assert_no_release_error(log, phase):
        if re.search(r"SQLiteException|SQLITE_ERROR|table .+ already exists|duplicate column name"
                     r"|FATAL EXCEPTION|UnsatisfiedLinkError", log):
            raise AssertionError("Post-upgrade schema, fatal or native error during " + phase)

    def await_new_raw_event(after_millis):
        # Collection starts after the initial queued upload. The real transmitter
        # throttles subsequent sends for five minutes, and its periodic job runs
        # every fifteen minutes. Keep the real throttle; advance only Android's
        # job scheduling after it expires, without restarting the app/collector.
        deadline = time.monotonic() + 420
        next_job = time.monotonic() + 305
        next_status = time.monotonic()
        while time.monotonic() < deadline:
            with Receiver.lock:
                for request in Receiver.requests:
                    for row in request["records"]:
                        if (row.get("passive-data-metadata", {}).get("generator-id") == "pdk-usage-stats"
                                and row.get("observed", 0) >= after_millis):
                            (args.output / "fresh-raw-event.json").write_text(json.dumps({
                                "cutoff_millis": after_millis, "record": row}, indent=2))
                            return
            if time.monotonic() >= next_status:
                with Receiver.lock:
                    request_count = len(Receiver.requests)
                    raw_count = sum(row.get("passive-data-metadata", {}).get("generator-id") == "pdk-usage-stats"
                                    for request in Receiver.requests for row in request["records"])
                progress("WAIT fresh raw event: receiver requests=" + str(request_count)
                         + ", raw records=" + str(raw_count))
                wait_log = adb(args.serial, "logcat", "-d", timeout=30)
                (args.output / "raw-event-wait-logcat.txt").write_text(wait_log)
                assert_no_release_error(wait_log, "raw-event upload wait")
                next_status = time.monotonic() + 30
            if time.monotonic() >= next_job:
                output = adb(args.serial, "shell", "cmd", "jobscheduler", "run", "-f", PKG, "12347")
                with (args.output / "scheduled-upload-job.txt").open("a") as job_log:
                    job_log.write(output + "\n")
                progress("Requested existing periodic upload job after transmitter throttle")
                next_job = time.monotonic() + 20
            time.sleep(1)
        raise AssertionError("Normal startup did not collect and upload a new raw usage event")

    with (args.output / "emulator.log").open("w") as log:
        continuous_log = None
        log_capture = None
        emulator = subprocess.Popen([str(SDK / "emulator/emulator"), "-avd", args.avd,
            "-port", args.serial.split("-")[1], "-no-window", "-no-audio", "-no-snapshot",
            "-no-boot-anim", "-gpu", "swiftshader_indirect", "-cores", "2"], stdout=log, stderr=log)
        try:
            wait_boot(args.serial)
            isolate_network()
            metadata = {name: adb(args.serial, "shell", "getprop", name).strip() for name in
                        ("ro.build.version.sdk", "ro.build.version.release", "ro.product.cpu.abi")}
            metadata["page_size"] = adb(args.serial, "shell", "getconf", "PAGESIZE").strip()
            (args.output / "device.json").write_text(json.dumps(metadata, indent=2))
            for device_port, host_port in ((8765, plain.server_port), (8766, secure.server_port)):
                adb(args.serial, "reverse", "tcp:" + str(device_port), "tcp:" + str(host_port))
            for package in (PKG + ".test", PKG):
                subprocess.run([ADB, "-s", args.serial, "uninstall", package],
                               capture_output=True, check=False, timeout=30)
            adb(args.serial, "install", str(args.baseline))
            adb(args.serial, "install", str(args.test_apk))
            seed_output = instrument(DATABASE_TEST, "seedInterruptedV109DatabaseAndConfirmCrash")
            observed = re.search(r"INSTRUMENTATION_STATUS: database_fixture_observed=(\d+)", seed_output)
            if observed is None:
                raise AssertionError("Baseline fixture did not report its retained observation timestamp")
            retained_observed = int(observed.group(1))
            adb(args.serial, "shell", "am", "force-stop", PKG)
            adb(args.serial, "logcat", "-c")
            adb(args.serial, "shell", "am", "start", "-W", "-n",
                PKG + "/com.audacious_software.phone_dashboard.MainActivity")
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                crash = adb(args.serial, "logcat", "-d")
                if "table history already exists" in crash and "FATAL EXCEPTION" in crash:
                    break
                time.sleep(1)
            else:
                raise AssertionError("Normal v109 startup did not reproduce the reported crash")
            (args.output / "v109-normal-start-crash.txt").write_text(crash)
            progress("PASS normal v109 startup reproduces history-already-exists crash")
            adb(args.serial, "shell", "am", "force-stop", PKG)
            instrument(DATABASE_TEST, "stageRetainedUploadBeforeUpgrade")
            adb(args.serial, "shell", "am", "force-stop", PKG)
            if delivered(RETAINED_MARKER, "none"):
                raise AssertionError("The pre-upgrade fixture record was sent before the update")
            if retained_aggregate(retained_observed) is not None:
                raise AssertionError("The pending aggregate was sent before the update")
            # install -r preserves preferences, SQLite data and upload queue.
            adb(args.serial, "install", "-r", str(args.current))
            adb(args.serial, "logcat", "-c")
            continuous_log = (args.output / "v111-continuous-post-upgrade.txt").open("w")
            log_capture = subprocess.Popen([ADB, "-s", args.serial, "logcat"],
                                           stdout=continuous_log, stderr=subprocess.STDOUT)
            instrument(DATABASE_TEST, "upgradedDatabaseKeepsHistoryAndCanReopen")
            progress("PASS all thirteen seeded databases retained; twelve enabled collectors started normally")
            # Android kills the instrumentation process as soon as its test
            # finishes. Normal startup, not that short inspection, owns upload.
            adb(args.serial, "shell", "am", "start", "-W", "-n",
                PKG + "/com.audacious_software.phone_dashboard.MainActivity")
            await_delivery(RETAINED_MARKER, "none")
            progress("PASS original queued record delivered after retained-data update")
            await_retained_aggregate(retained_observed)
            instrument(DATABASE_TEST, "deliveredAggregateClearsPendingFlag")
            progress("PASS pre-upgrade pending aggregate delivered intact and flag cleared")
            instrument(COMPRESSION_TEST, "retainedNativeBindingsWorkInMinifiedRelease")
            instrument(COMPRESSION_TEST, "prepareQueuedCompressedPayloadForNormalColdStart")
            upgrade_log = adb(args.serial, "logcat", "-d")
            (args.output / "v111-post-upgrade-start.txt").write_text(upgrade_log)
            assert_no_release_error(upgrade_log, "retained-data upgrade")
            adb(args.serial, "shell", "am", "force-stop", PKG)
            if delivered("normal-application-cold-start", "gzip"):
                raise AssertionError("Cold-start marker arrived before the normal launch")
            adb(args.serial, "shell", "appops", "set", PKG, "GET_USAGE_STATS", "allow")
            adb(args.serial, "logcat", "-c")
            # Ignore any raw event queued before force-stop. The new activity
            # launch occurs strictly after this device-clock cutoff.
            raw_event_cutoff = (int(adb(args.serial, "shell", "date", "+%s").strip()) + 1) * 1000
            time.sleep(1.1)
            adb(args.serial, "shell", "am", "start", "-W", "-n",
                PKG + "/com.audacious_software.phone_dashboard.MainActivity")
            await_delivery("normal-application-cold-start", "gzip")
            (args.output / "normal-start-services.txt").write_text(
                adb(args.serial, "shell", "dumpsys", "activity", "services", PKG))
            await_new_raw_event(raw_event_cutoff)
            progress("PASS normal startup collected and uploaded a new raw usage event")
            time.sleep(5)
            cold_log = adb(args.serial, "logcat", "-d")
            (args.output / "v111-normal-cold-start.txt").write_text(cold_log)
            assert_no_release_error(cold_log, "normal cold start")
            if not adb(args.serial, "shell", "pidof", PKG).strip():
                raise AssertionError("Normal v111 startup did not survive")
            if log_capture.poll() is not None:
                raise AssertionError("Continuous post-upgrade log capture stopped early")
            log_capture.terminate()
            log_capture.wait(timeout=15)
            continuous_log.close()
            assert_no_release_error((args.output / "v111-continuous-post-upgrade.txt").read_text(),
                                    "all captured post-upgrade phases")
            progress("PASS normal v111 cold start and compressed upload without schema errors")
            (args.output / "result.json").write_text(json.dumps({"passed": True, "device": metadata,
                "baseline": str(args.baseline), "current": str(args.current),
                "received_requests": len(Receiver.requests)}, indent=2))
        except Exception as failure:
            (args.output / "failure.txt").write_text(repr(failure))
            try:
                (args.output / "failure-logcat.txt").write_text(adb(args.serial, "logcat", "-d", timeout=30))
            except (RuntimeError, subprocess.TimeoutExpired) as capture_failure:
                progress("Failure logcat could not be captured: " + str(capture_failure))
            raise
        finally:
            if log_capture is not None and log_capture.poll() is None:
                log_capture.terminate()
                log_capture.wait(timeout=15)
            if continuous_log is not None:
                continuous_log.close()
            plain.shutdown()
            secure.shutdown()
            for package in (PKG + ".test", PKG):
                subprocess.run([ADB, "-s", args.serial, "uninstall", package],
                               capture_output=True, check=False, timeout=30)
            try:
                adb(args.serial, "emu", "kill", timeout=15)
            except (RuntimeError, subprocess.TimeoutExpired):
                emulator.terminate()
            emulator.wait(timeout=30)


if __name__ == "__main__":
    main()

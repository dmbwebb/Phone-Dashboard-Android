#!/usr/bin/env python3
"""Run only against named local Android emulators and synthetic HTTP fixtures.

Build first with -PreliabilityE2E=true :app:assembleDebug :app:assembleDebugAndroidTest.
This script never invokes Gradle or contacts the production service.
"""
import argparse
import email.parser
import email.policy
import http.server
import json
import os
import re
import socket
from pathlib import Path
import subprocess
import threading
import time
import urllib.parse
import xml.etree.ElementTree as ET

SDK = Path.home() / "Library/Android/sdk"
ADB = str(SDK / "platform-tools/adb")
PKG = "com.miritmodigital.app"
TEST = "com.audacious_software.phone_dashboard.ReliabilityInstrumentedTest"
RUNNER = PKG + ".test/com.audacious_software.phone_dashboard.ReliabilityTestRunner"
HOST_PORT = 8765
CONFIG = {
    "identifier": "E2E-SYNTHETIC",
    "transmitters": [{"type": "pdk-http-transmitter", "upload-uri": "http://127.0.0.1:8765/upload",
                      "wifi-only": False, "charging-only": False, "compression": False}],
    "generators": [{"identifier": "pdk-daily-usage-aggregate", "enabled": True, "lookback-days": 7}],
}


class Fixture(http.server.BaseHTTPRequestHandler):
    state = {"attempts": 0, "records": [], "status": 201, "config_requests": 0, "config_mode": "valid", "rejected": [], "delay": 0}
    lock = threading.Lock()
    journal = None

    def log_message(self, *args):
        pass

    def respond(self, value, status=200):
        body = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        with self.lock:
            if url.path == "/reset":
                self.state.update(attempts=0, records=[], status=201, config_requests=0, config_mode="valid", rejected=[], delay=0)
            elif url.path == "/mode":
                query = urllib.parse.parse_qs(url.query)
                if "status" in query:
                    self.state["status"] = int(query["status"][0])
                if "config" in query:
                    self.state["config_mode"] = query["config"][0]
                if "delay" in query:
                    self.state["delay"] = float(query["delay"][0])
            elif url.path == "/config":
                self.state["config_requests"] += 1
                if self.state["config_mode"] == "malformed":
                    return self.respond({"transmitters": "invalid-type", "generators": []})
                return self.respond(CONFIG)
            self.respond(self.state)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        if urllib.parse.urlsplit(self.path).path == "/study":
            fields = urllib.parse.parse_qs(raw.decode())
            if fields.get("identifier") != ["E2E-SYNTHETIC"]:
                return self.respond({"error": "Unexpected fixture identity"}, 400)
            return self.respond({"treatment_active": False, "blocker_type": "none"})
        if urllib.parse.urlsplit(self.path).path != "/upload":
            return self.respond({"error": "Unknown fixture endpoint"}, 404)
        message = email.parser.BytesParser(policy=email.policy.default).parsebytes(
            ("Content-Type: " + self.headers["Content-Type"] + "\r\n\r\n").encode() + raw)
        try:
            if not message.is_multipart() or message.defects:
                raise ValueError("Malformed multipart request")
            fields = {}
            for part in message.iter_parts():
                name = part.get_param("name", header="content-disposition")
                if name in fields:
                    raise ValueError("Duplicate multipart field: " + str(name))
                fields[name] = part.get_payload(decode=True).decode()
            if fields.get("compression") != "none" or fields.get("encrypted", "false") != "false":
                raise ValueError("Fixture requires configured uncompressed, unencrypted payloads")
            records = json.loads(fields["payload"])
            if not isinstance(records, list) or not records:
                raise ValueError("Payload must be a nonempty JSON array")
            for record in records:
                metadata = record["passive-data-metadata"]
                if metadata["source"] != "E2E-SYNTHETIC":
                    raise ValueError("Unexpected source identity")
                if not isinstance(metadata["generator-id"], str) or not metadata["generator-id"]:
                    raise ValueError("Missing generator identity")
                if not isinstance(metadata["timestamp"], (int, float)) or metadata["timestamp"] <= 0:
                    raise ValueError("Missing numeric timestamp")
                if metadata["generator-id"] == "pdk-daily-usage-aggregate":
                    if not isinstance(record["package"], str) or not record["package"]:
                        raise ValueError("Missing package")
                    for key in ("day_bucket", "total_ms"):
                        if not isinstance(record[key], (int, float)) or record[key] < 0:
                            raise ValueError("Invalid daily aggregate " + key)
        except (ValueError, KeyError, TypeError, UnicodeDecodeError) as error:
            with self.lock:
                self.state["rejected"].append(str(error))
                with self.journal.open("a") as output:
                    output.write(json.dumps({"time": time.time(), "rejected": str(error)}) + "\n")
            return self.respond({"added": False, "error": str(error)}, 400)
        with self.lock:
            self.state["attempts"] += 1
            status = self.state["status"]
            delay = self.state["delay"]
            if status == 201:
                self.state["records"].extend(records)
            with self.journal.open("a") as output:
                output.write(json.dumps({"time": time.time(), "status": status, "records": records}) + "\n")
        if status == 0:
            # Keep the TCP socket open without any response, exceeding the
            # production read/call timeout. State/control requests remain live.
            time.sleep(55)
            self.close_connection = True
            return
        if delay:
            time.sleep(delay)
        if status == 444:
            try:
                self.connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            self.connection.close()
            return
        try:
            self.respond({"added": status == 201}, status)
        except (BrokenPipeError, ConnectionResetError):
            pass


def command(args, timeout=120):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    output = result.stdout.decode(errors="replace")
    if result.returncode:
        raise RuntimeError("Command failed: " + repr(args) + "\n" + output)
    return output


def adb(serial, *args, timeout=120):
    return command([ADB, "-s", serial, *args], timeout)


def wait_boot(serial, previous_boot=None):
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        try:
            if adb(serial, "shell", "getprop", "sys.boot_completed", timeout=10).strip() == "1":
                if previous_boot == adb(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id").strip():
                    time.sleep(1)
                    continue
                adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
                adb(serial, "shell", "wm", "dismiss-keyguard")
                return
        except (RuntimeError, subprocess.TimeoutExpired):
            pass
        time.sleep(2)
    raise RuntimeError("Emulator did not boot: " + serial)


def wait_condition(description, check, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(1)
    raise RuntimeError(description)


def lifecycle_recovery(serial, output):
    """After setup, neither instrumentation nor an activity reopens the killed app."""
    adb(serial, "shell", "appops", "set", PKG, "GET_USAGE_STATS", "allow")
    adb(serial, "shell", "dumpsys", "deviceidle", "whitelist", "+" + PKG)
    with Fixture.lock:
        Fixture.state.update(attempts=0, records=[], status=503)
    adb(serial, "shell", "am", "start", "-n", PKG + "/com.audacious_software.phone_dashboard.MainActivity")
    wait_condition("Real app did not attempt initial upload", lambda: Fixture.state["attempts"] > 0)
    def retry_deadline():
        xml = adb(serial, "shell", "run-as", PKG, "cat", "shared_prefs/" + PKG + "_preferences.xml")
        for item in ET.fromstring(xml):
            if item.attrib.get("name", "").startswith("pdk-upload-retry:"):
                return int(item.attrib.get("value", "0"))
        return 0
    wait_condition("Failing request did not persist its retry deadline", lambda: retry_deadline() > time.time() * 1000, timeout=20)
    persisted_retry = retry_deadline()
    adb(serial, "shell", "input", "keyevent", "KEYCODE_HOME")
    old_pid = adb(serial, "shell", "pidof", PKG).strip()
    if not old_pid or " " in old_pid:
        raise RuntimeError("Expected one app process, got " + repr(old_pid))
    summary = {"ordinary_death": {"old_pid": old_pid, "persisted_retry_deadline": persisted_retry, "result": "pending"}}
    (output / "autonomous-lifecycle.json").write_text(json.dumps(summary, indent=2))
    adb(serial, "shell", "run-as", PKG, "kill", "-9", old_pid)
    with Fixture.lock:
        Fixture.state.update(records=[], status=201)
    wait_condition("Ordinary process death did not recover uploads without reopening app",
                   lambda: bool(Fixture.state["records"]), timeout=150)
    new_pid = adb(serial, "shell", "pidof", PKG).strip()
    if new_pid == old_pid:
        raise RuntimeError("Process was not actually replaced")
    summary["ordinary_death"].update(new_pid=new_pid, received_records=len(Fixture.state["records"]), result="passed")
    (output / "autonomous-lifecycle.json").write_text(json.dumps(summary, indent=2))
    print(serial + " PASS autonomous death recovery with persisted backoff", flush=True)
    # Stage an explicit synthetic outbox fixture. Real collection is tested
    # independently; this known marker proves delivery actually occurred AFTER
    # boot, rather than an in-flight request satisfying the check before reboot.
    with Fixture.lock:
        Fixture.state.update(records=[], attempts=0, status=503)
    boot_record = {"marker": "boot-queued-synthetic", "observed": int(time.time() * 1000),
                   "passive-data-metadata": {"source": "E2E-SYNTHETIC", "generator-id": "e2e-synthetic",
                                             "timestamp": time.time(), "generator": "e2e-synthetic"}}
    seeded = subprocess.run([ADB, "-s", serial, "shell",
                             "run-as " + PKG + " sh -c 'cat > files/http-transmitter/e2e-boot-probe.json'"],
                            input=json.dumps([boot_record]).encode(), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15)
    if seeded.returncode:
        raise RuntimeError("Cannot stage boot fixture: " + seeded.stdout.decode(errors="replace"))
    assert json.loads(adb(serial, "shell", "run-as", PKG, "cat", "files/http-transmitter/e2e-boot-probe.json")) == [boot_record]
    # Shell appops changes are buffered. Persist the synthetic permission grant
    # so an abrupt adb reboot does not discard the synthetic test setup.
    adb(serial, "shell", "appops", "write-settings")
    previous_boot = adb(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id").strip()
    adb(serial, "reboot")
    time.sleep(3)
    wait_boot(serial, previous_boot)
    permission_after_boot = adb(serial, "shell", "appops", "get", PKG, "GET_USAGE_STATS")
    if "GET_USAGE_STATS: allow" not in permission_after_boot:
        raise RuntimeError("Synthetic usage permission did not survive reboot: " + permission_after_boot)
    adb(serial, "reverse", "tcp:8765", "tcp:" + str(HOST_PORT))
    with Fixture.lock:
        Fixture.state.update(records=[], attempts=0, status=201)
    wait_condition("Boot did not restart upload without reopening app",
                   lambda: any(record.get("marker") == "boot-queued-synthetic" for record in Fixture.state["records"]), timeout=180)
    summary["boot"] = {"received_records": len(Fixture.state["records"]),
                       "known_synthetic_bundle_received_after_boot": True,
                       "usage_permission_after_boot": permission_after_boot.strip(),
                       "pid": adb(serial, "shell", "pidof", PKG).strip(),
                       "old_boot_id": previous_boot,
                       "new_boot_id": adb(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id").strip()}
    (output / "autonomous-lifecycle.json").write_text(json.dumps(summary, indent=2))
    print(serial + " PASS autonomous boot recovery", flush=True)
    # This checks real Android idle entry and later recovery. Whitelisted apps
    # may retain networking; it intentionally does not assert OEM kill behavior.
    adb(serial, "shell", "dumpsys", "deviceidle", "whitelist", "-" + PKG)
    adb(serial, "shell", "dumpsys", "deviceidle", "enable")
    adb(serial, "shell", "dumpsys", "battery", "unplug")
    adb(serial, "shell", "input", "keyevent", "KEYCODE_SLEEP")
    idle = adb(serial, "shell", "dumpsys", "deviceidle", "force-idle")
    summary["doze_entry"] = idle.strip()
    idle_state = adb(serial, "shell", "dumpsys", "deviceidle")
    (output / "doze-state.txt").write_text(idle_state)
    if "now forced in to deep idle mode" not in idle.lower() or not re.search(r"\bmState=IDLE\b", idle_state):
        raise RuntimeError("Could not enter Doze: " + idle)
    time.sleep(5)
    adb(serial, "shell", "dumpsys", "deviceidle", "unforce")
    adb(serial, "shell", "dumpsys", "battery", "reset")
    adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb(serial, "shell", "wm", "dismiss-keyguard")
    summary["doze_exit_pid"] = adb(serial, "shell", "pidof", PKG).strip()
    with Fixture.lock:
        Fixture.state.update(records=[], attempts=0)
    collection_start_ms = int(adb(serial, "shell", "date", "+%s").strip()) * 1000
    summary["post_doze_collection_start_ms"] = collection_start_ms
    adb(serial, "shell", "am", "start", "-a", "android.settings.SETTINGS")
    time.sleep(3)
    adb(serial, "shell", "input", "keyevent", "KEYCODE_HOME")
    # Expedite the actual persisted periodic job after idle. This is a forced
    # job execution, not evidence that Android schedules it immediately itself.
    summary["post_doze_job_trigger"] = adb(serial, "shell", "cmd", "jobscheduler", "run", "-f", PKG, "12347").strip()
    (output / "autonomous-lifecycle.json").write_text(json.dumps(summary, indent=2))
    (output / "post-doze-jobs.txt").write_text(adb(serial, "shell", "dumpsys", "jobscheduler", PKG))
    # The job collects immediately, while upload honors the production
    # five-minute interval. Retry the actual scheduled job after that interval;
    # do not call the uploader directly or wait on a moving upload deadline.
    print(serial + " WAIT production five-minute interval for post-Doze receipt", flush=True)
    began_wait = time.monotonic()
    def received_post_doze():
        return any(
        record.get("package") == "com.android.settings" and
        record.get("observed", 0) >= collection_start_ms and
        record.get("passive-data-metadata", {}).get("generator-id") == "pdk-daily-usage-aggregate"
        for record in Fixture.state["records"])
    while not received_post_doze() and time.monotonic() - began_wait < 305:
        time.sleep(2)
    if not received_post_doze():
        summary["post_doze_job_retrigger"] = adb(serial, "shell", "cmd", "jobscheduler", "run", "-f", PKG, "12347").strip()
        wait_condition("Post-Doze real collection/upload did not recover", received_post_doze, timeout=90)
    summary["post_doze_interval_wait_seconds"] = round(time.monotonic() - began_wait, 1)
    summary["post_doze_received_records"] = len(Fixture.state["records"])
    (output / "autonomous-lifecycle.json").write_text(json.dumps(summary, indent=2))
    print(serial + " PASS autonomous process death, boot, Doze entry/exit", flush=True)


def run_method(serial, method, output, lifecycle=False, clear=True):
    if clear:
        adb(serial, "shell", "pm", "clear", PKG)
    adb(serial, "reverse", "tcp:8765", "tcp:" + str(HOST_PORT))
    args = ["shell", "am", "instrument", "-w", "-r", "-e", "class", TEST + "#" + method]
    if lifecycle:
        args += ["-e", "lifecycle", "true"]
    text = adb(serial, *args, RUNNER, timeout=180)
    (output / (method + ".txt")).write_text(text)
    if "OK (1 test)" not in text:
        raise RuntimeError(method + " failed:\n" + text)
    print(serial + " PASS " + method, flush=True)


def run_device(serial, avd, repo, output, method=None, lifecycle=False, clear=True, lifecycle_only=False):
    if not serial.startswith("emulator-"):
        raise RuntimeError("This destructive test setup only accepts emulator serials")
    output.mkdir(parents=True, exist_ok=True)
    process = None
    if avd:
        devices = command([ADB, "devices"])
        if serial in devices:
            raise RuntimeError("Refusing to commandeer occupied emulator " + serial)
        log = (output / "emulator.log").open("w")
        process = subprocess.Popen([str(SDK / "emulator/emulator"), "-avd", avd,
                                    "-port", serial.split("-")[1], "-no-window", "-no-audio",
                                    "-no-snapshot", "-timezone", "America/Bogota",
                                    "-gpu", "swiftshader_indirect"], stdout=log, stderr=log)
    try:
        wait_boot(serial)
        for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            adb(serial, "shell", "settings", "put", "global", setting, "0")
        metadata = {key: adb(serial, "shell", "getprop", key).strip() for key in
                    ("ro.build.version.sdk", "ro.build.version.release", "ro.product.model", "ro.product.cpu.abi")}
        try:
            metadata["page_size"] = adb(serial, "shell", "getconf", "PAGE_SIZE").strip()
        except RuntimeError as error:
            if "getconf: not found" not in str(error):
                raise
            # Android 7's toolbox lacks getconf. Read the kernel's own report.
            smaps = adb(serial, "shell", "cat", "/proc/self/smaps")
            match = re.search(r"KernelPageSize:\s+(\d+)\s+kB", smaps)
            if not match:
                raise RuntimeError("Cannot determine kernel page size on this emulator")
            metadata["page_size"] = str(int(match.group(1)) * 1024)
        metadata["timezone"] = adb(serial, "shell", "getprop", "persist.sys.timezone").strip()
        (output / "device.json").write_text(json.dumps(metadata, indent=2))
        adb(serial, "install", "-r", str(repo / "app/build/outputs/apk/debug/app-debug.apk"))
        adb(serial, "install", "-r", str(repo / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
        if method:
            run_method(serial, method, output, lifecycle=lifecycle, clear=clear)
            if method == "spanishHealthScreenOpensRealPermissionSettings":
                for suffix in ("", "-top"):
                    adb(serial, "pull", "/sdcard/Android/data/" + PKG + "/cache/e2e-health-es" + suffix + ".png",
                        str(output / ("health-es" + suffix + ".png")))
            return
        if lifecycle_only:
            run_method(serial, "repeatedCachedStartupKeepsOneTransmitter", output, lifecycle=True)
            lifecycle_recovery(serial, output)
            run_method(serial, "spanishHealthScreenOpensRealPermissionSettings", output, lifecycle=True)
            for suffix in ("", "-top"):
                adb(serial, "pull", "/sdcard/Android/data/" + PKG + "/cache/e2e-health-es" + suffix + ".png",
                    str(output / ("health-es" + suffix + ".png")))
            return
        for method in ("realUsageCollectedBeforeUploaderIsEventuallyDelivered",
                       "permissionRevokedAndRestoredResumesActualCollection",
                       "http503RetainsQueueAndBackoffDoesNotBlockNewRecords",
                       "disconnectedSocketRetainsQueueAndRecovers",
                       "realReadTimeoutPreservesQueueWithoutBlockingDisk",
                       "simultaneousUploaderDrainsSendOneCopyAndClearQueue",
                       "upgradingLegacyDatabaseDoesNotReplayHistoricRows"):
            run_method(serial, method, output)
        run_method(serial, "preparePendingRealUsageForProcessDeath", output)
        adb(serial, "shell", "am", "force-stop", PKG)
        run_method(serial, "recoverPendingRealUsageAfterProcessDeath", output, clear=False)
        # A second independent persistence test includes an actual OS reboot.
        run_method(serial, "preparePendingRealUsageForProcessDeath", output)
        previous_boot = adb(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id").strip()
        adb(serial, "reboot")
        time.sleep(3)
        wait_boot(serial, previous_boot)
        reboot_output = output / "after-reboot"
        reboot_output.mkdir(exist_ok=True)
        run_method(serial, "recoverPendingRealUsageAfterProcessDeath", reboot_output, clear=False)
        run_method(serial, "repeatedCachedStartupKeepsOneTransmitter", output, lifecycle=True)
        run_method(serial, "malformedConfigurationPreservesCachedMonitoring", output, lifecycle=True)
        lifecycle_recovery(serial, output)
        run_method(serial, "spanishHealthScreenOpensRealPermissionSettings", output, lifecycle=True)
        for suffix in ("", "-top"):
            adb(serial, "pull", "/sdcard/Android/data/" + PKG + "/cache/e2e-health-es" + suffix + ".png",
                str(output / ("health-es" + suffix + ".png")))
        (output / "logcat.txt").write_text(adb(serial, "logcat", "-d", "-t", "5000"))
        print(serial + " COMPLETE " + json.dumps(metadata), flush=True)
    finally:
        try:
            (output / "logcat.txt").write_text(adb(serial, "logcat", "-d", "-t", "5000", timeout=20))
        except (RuntimeError, subprocess.TimeoutExpired) as error:
            (output / "logcat-error.txt").write_text(str(error))
        if process:
            try:
                adb(serial, "emu", "kill", timeout=15)
            except (RuntimeError, subprocess.TimeoutExpired):
                process.terminate()
            process.wait(timeout=30)


def main():
    global HOST_PORT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--avd", default="MRD_API34_Reliability")
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--existing", action="store_true", help="Explicitly use an already-running dedicated emulator")
    parser.add_argument("--method", help="Run one named instrumentation method for diagnosis")
    parser.add_argument("--lifecycle", action="store_true", help="Seed synthetic identity/config for --method")
    parser.add_argument("--no-clear", action="store_true", help="Preserve state for staged --method recovery")
    parser.add_argument("--lifecycle-only", action="store_true", help="Run startup, autonomous lifecycle and Spanish UI cases")
    parser.add_argument("--fixture-port", type=int, default=8765, help="Independent host sink port; device port remains 8765")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    Fixture.journal = args.output / "http-journal.jsonl"
    HOST_PORT = args.fixture_port
    server = http.server.ThreadingHTTPServer(("127.0.0.1", HOST_PORT), Fixture)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        run_device(args.serial, None if args.existing else args.avd, args.repo, args.output,
                   method=args.method, lifecycle=args.lifecycle, clear=not args.no_clear,
                   lifecycle_only=args.lifecycle_only)
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()

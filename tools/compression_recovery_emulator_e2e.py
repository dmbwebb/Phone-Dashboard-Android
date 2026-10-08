#!/usr/bin/env python3
"""Reproduce v108's release-only crash, then verify an in-place v109 recovery.

Uses only a dedicated emulator, synthetic study ID, and local HTTP/TLS receivers.
The test APK substitutes configuration resources; the app APK is the signed,
minified production artifact, and its preferences/queue survive the update.
"""
import argparse
import base64
import email.parser
import email.policy
import gzip
import http.server
import json
from pathlib import Path
import ssl
import subprocess
import threading
import time
import urllib.parse

from reliability_emulator_e2e import ADB, SDK, adb, command, wait_boot

PKG = "com.miritmodigital.app"
TEST = "com.audacious_software.phone_dashboard.CompressionRecoveryInstrumentedTest"
RUNNER = PKG + ".test/com.audacious_software.phone_dashboard.CompressionRecoveryTestRunner"
IDENTITY = "E2E-COMPRESSION-RECOVERY"


class Receiver(http.server.BaseHTTPRequestHandler):
    compression = True
    requests = []
    journal = None
    lock = threading.Lock()

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
        if url.path == "/mode":
            Receiver.compression = urllib.parse.parse_qs(url.query)["compression"] == ["true"]
        if url.path == "/config":
            params = urllib.parse.parse_qs(url.query)
            if params.get("id") != [IDENTITY]:
                return self.respond({"error": "Unexpected identity"}, 400)
            return self.respond({"transmitters": [{"type": "pdk-http-transmitter",
                "upload-uri": "https://127.0.0.1:8766/upload", "strict-ssl-verification": False,
                "compression": Receiver.compression, "wifi-only": False, "charging-only": False}],
                "generators": [{"identifier": "pdk-daily-usage-aggregate", "enabled": False}]})
        with self.lock:
            self.respond({"requests": self.requests})

    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.path == "/study":
            return self.respond({"treatment_active": False, "blocker_type": "none"})
        if self.path != "/upload":
            return self.respond({"error": "Unknown path"}, 404)
        try:
            parts = email.parser.BytesParser(policy=email.policy.default).parsebytes(
                ("Content-Type: " + self.headers["Content-Type"] + "\r\n\r\n").encode() + raw)
            if not parts.is_multipart() or parts.defects:
                raise ValueError("Malformed multipart body")
            fields = {part.get_param("name", header="content-disposition"):
                      part.get_payload(decode=True).decode() for part in parts.iter_parts()}
            if fields.get("encrypted", "false") != "false":
                raise ValueError("Unexpected encrypted payload")
            compression = fields["compression"]
            payload = fields["payload"]
            if compression == "gzip":
                payload = gzip.decompress(base64.b64decode(payload)).decode()
            elif compression != "none":
                raise ValueError("Unknown compression")
            records = json.loads(payload)
            assert isinstance(records, list) and records
            for record in records:
                assert record["passive-data-metadata"]["source"] == IDENTITY
            request = {"compression": compression, "records": records}
            with self.lock:
                self.requests.append(request)
                with self.journal.open("a") as output:
                    output.write(json.dumps(request) + "\n")
            self.respond({"added": True}, 201)
        except (ValueError, KeyError, AssertionError) as error:
            self.respond({"added": False, "error": str(error)}, 400)


def run_test(serial, method):
    return adb(serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", TEST + "#" + method,
               RUNNER, timeout=150)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--current", required=True, type=Path)
    parser.add_argument("--test-apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--avd", default="MRD_API34_Reliability")
    parser.add_argument("--serial", default="emulator-5580")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise ValueError("Physical devices are forbidden")
    if args.serial in command([ADB, "devices"]):
        raise ValueError("Emulator serial is occupied; refusing to touch it")
    args.output.mkdir(parents=True, exist_ok=True)
    Receiver.journal = args.output / "http-journal.jsonl"
    certificate = args.output / "fixture-cert.pem"
    key = args.output / "fixture-key.pem"
    command(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
             "-keyout", str(key), "-out", str(certificate), "-config",
             str(Path(__file__).with_name("compression_fixture_tls.cnf"))])
    plain = http.server.ThreadingHTTPServer(("127.0.0.1", 8765), Receiver)
    secure = http.server.ThreadingHTTPServer(("127.0.0.1", 8766), Receiver)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(certificate, key)
    secure.socket = tls.wrap_socket(secure.socket, server_side=True)
    for server in (plain, secure):
        threading.Thread(target=server.serve_forever, daemon=True).start()
    with (args.output / "emulator.log").open("w") as log:
        emulator = subprocess.Popen([str(SDK / "emulator/emulator"), "-avd", args.avd,
            "-port", args.serial.split("-")[1], "-no-window", "-no-audio", "-no-snapshot",
            "-no-boot-anim", "-gpu", "swiftshader_indirect", "-cores", "2"], stdout=log, stderr=log)
        try:
            wait_boot(args.serial)
            # adb reverse still reaches localhost with both radios off. No fixture
            # process can contact production, including after instrumentation exits.
            adb(args.serial, "shell", "svc", "wifi", "disable")
            adb(args.serial, "shell", "svc", "data", "disable")
            metadata = {key: adb(args.serial, "shell", "getprop", key).strip() for key in
                ("ro.build.version.sdk", "ro.build.version.release", "ro.product.cpu.abi")}
            (args.output / "device.json").write_text(json.dumps(metadata, indent=2))
            for port in (8765, 8766):
                adb(args.serial, "reverse", "tcp:" + str(port), "tcp:" + str(port))
            # Dedicated test AVD: clear stale fixture installations, never a physical phone.
            for package in (PKG + ".test", PKG):
                subprocess.run([ADB, "-s", args.serial, "uninstall", package], capture_output=True, check=False)
            adb(args.serial, "install", str(args.baseline))
            adb(args.serial, "install", str(args.test_apk))
            adb(args.serial, "logcat", "-c")
            baseline = run_test(args.serial, "seedV108CompressedQueueAndReproduceCrash")
            (args.output / "v108-instrumentation.txt").write_text(baseline)
            crash = adb(args.serial, "logcat", "-d")
            (args.output / "v108-logcat.txt").write_text(crash)
            if "Can't obtain static method fromNative" not in crash or "Toolbox.<clinit>" not in crash:
                raise AssertionError("The expected v108 JNI crash was not reproduced")
            if Receiver.requests:
                raise AssertionError("v108 unexpectedly delivered a compressed payload")
            print(args.serial + " v108 crash reproduced", flush=True)
            # No clear/uninstall between versions: Android verifies signer and retains data.
            adb(args.serial, "install", "-r", str(args.current))
            adb(args.serial, "logcat", "-c")
            for method in ("upgradeRecoversCachedCompressionAndFetchesRevertedConfiguration",
                           "retainedNativeBindingsWorkInMinifiedRelease",
                           "prepareQueuedCompressedPayloadForNormalColdStart"):
                result = run_test(args.serial, method)
                (args.output / (method + ".txt")).write_text(result)
                if "OK (1 test)" not in result:
                    raise AssertionError("Release test failed: " + method + "\n" + result)
                print(args.serial + " PASS " + method, flush=True)
            (args.output / "v109-logcat.txt").write_text(adb(args.serial, "logcat", "-d"))
            adb(args.serial, "shell", "am", "force-stop", PKG)
            with Receiver.lock:
                if any(row.get("marker") == "normal-application-cold-start"
                       for request in Receiver.requests for row in request["records"]):
                    raise AssertionError("Cold-start marker was sent before the normal launch")
            adb(args.serial, "shell", "appops", "set", PKG, "GET_USAGE_STATS", "allow")
            adb(args.serial, "logcat", "-c")
            adb(args.serial, "shell", "am", "start", "-W", "-n",
                PKG + "/com.audacious_software.phone_dashboard.MainActivity")
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                with Receiver.lock:
                    delivered = any(request["compression"] == "gzip" and
                        any(row.get("marker") == "normal-application-cold-start" for row in request["records"])
                        for request in Receiver.requests)
                if delivered:
                    break
                time.sleep(1)
            if not delivered:
                raise AssertionError("Normal application launch failed to deliver retained compressed payload")
            time.sleep(5)
            cold_log = adb(args.serial, "logcat", "-d")
            (args.output / "v109-normal-cold-start-logcat.txt").write_text(cold_log)
            if "FATAL EXCEPTION" in cold_log or "UnsatisfiedLinkError" in cold_log:
                raise AssertionError("Normal application startup crashed")
            if not adb(args.serial, "shell", "pidof", PKG).strip():
                raise AssertionError("Normal application startup did not survive")
            print(args.serial + " PASS normal AppApplication/MainActivity cold start", flush=True)
            (args.output / "result.json").write_text(json.dumps({"passed": True, "device": metadata,
                "baseline": str(args.baseline), "current": str(args.current),
                "received_requests": len(Receiver.requests)}, indent=2))
        finally:
            plain.shutdown(); secure.shutdown()
            # Do not leave synthetic enrollment or pending crash telemetry on the AVD.
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

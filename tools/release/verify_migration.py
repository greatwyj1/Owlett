#!/usr/bin/env python3
"""Compare installed legacy signing certificate before any adb install -r."""
import argparse
import os
import pathlib
import re
import subprocess
import tempfile

def output(args, env=None):
    return subprocess.check_output([str(arg) for arg in args], text=True, env=env).strip()
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=pathlib.Path)
    parser.add_argument("--serial")
    parser.add_argument("--install", action="store_true")
    args = parser.parse_args()
    sdk = pathlib.Path(os.getenv("ANDROID_HOME", str(pathlib.Path.home() / "Library/Android/sdk")))
    adb = [str(sdk / "platform-tools/adb")] + (["-s", args.serial] if args.serial else [])
    tools = sorted((sdk / "build-tools").iterdir(), key=lambda p: [int(x) for x in p.name.split(".") if x.isdigit()])[-1]
    java = "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    env = dict(os.environ, JAVA_HOME=os.getenv("JAVA_HOME", java))
    def fingerprint(path):
        text = output([tools / "apksigner", "verify", "--print-certs", path], env)
        return re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", text)
    devices = output(adb + ["devices"]).splitlines()[1:]
    if len([line for line in devices if line.endswith("\tdevice")]) != 1 and not args.serial:
        raise SystemExit("Connect exactly one authorized device or specify --serial. Nothing installed.")
    paths = output(adb + ["shell", "pm", "path", "com.example.birdingsoundmvp"]).splitlines()
    if not paths:
        raise SystemExit("Legacy app not found. Nothing installed.")
    package = output([tools / "aapt", "dump", "badging", args.apk]).splitlines()[0]
    if "name='com.example.birdingsoundmvp'" not in package:
        raise SystemExit("Not a legacy migration APK.")
    with tempfile.TemporaryDirectory(prefix="owlett-signature-") as temporary:
        installed = pathlib.Path(temporary) / "installed.apk"
        output(adb + ["pull", paths[0].removeprefix("package:"), installed])
        before, after = fingerprint(installed), fingerprint(args.apk)
        if not before or before != after:
            raise SystemExit("SIGNATURE MISMATCH. Do not uninstall the old app. Nothing installed.")
        print("Legacy signing certificate verified:", before[0])
        if args.install:
            print(output(adb + ["install", "-r", str(args.apk)]))
        else:
            print("Check only. Re-run with --install to update while retaining data.")
if __name__ == "__main__":
    main()

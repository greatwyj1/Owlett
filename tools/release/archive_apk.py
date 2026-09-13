#!/usr/bin/env python3
"""Verify and archive local APKs, preserving previous versioned artifacts. Never uploads."""
import argparse
import configparser
import datetime
import hashlib
import json
import pathlib
import re
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(data)
    return value.hexdigest()


def inspect(path, build_tools):
    badging = subprocess.check_output([str(build_tools / "aapt"), "dump", "badging", str(path)], text=True)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if package is None:
        raise SystemExit("Cannot read APK version")
    signature = subprocess.check_output([str(build_tools / "apksigner"), "verify", "--print-certs", str(path)], text=True)
    certificate = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)", signature)
    if len(certificate) != 1:
        raise SystemExit("Expected one verified signing certificate")
    return dict(applicationId=package[1], versionCode=int(package[2]), versionName=package[3],
                certificateSha256=certificate[0].lower(), sha256=digest(path),
                builtAt=datetime.datetime.fromtimestamp(path.stat().st_mtime).astimezone().isoformat())


def archive(path, metadata, output, mode):
    stamp = datetime.datetime.fromisoformat(metadata["builtAt"]).strftime("%Y%m%d-%H%M%S")
    version = metadata["versionName"]
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise SystemExit("Unsafe version label")
    target = output / f"owlett-{mode}-{version}-{stamp}-{metadata['sha256'][:8]}.apk"
    if target.exists() and digest(target) != metadata["sha256"]:
        raise SystemExit("Refusing to overwrite a versioned artifact")
    if not target.exists():
        with path.open("rb") as source, target.open("xb") as destination:
            shutil.copyfileobj(source, destination)
        shutil.copystat(path, target)
    record = target.with_suffix(".json")
    if not record.exists():
        with record.open("x") as stream:
            json.dump(metadata, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
    return target


def replace_latest(target, latest):
    if target.parent.resolve() != latest.parent.resolve() or target.name == latest.name or not target.is_file():
        raise SystemExit("Latest alias must reference an archived APK in the same directory")
    staged = latest.with_suffix(".apk.tmp")
    staged.unlink(missing_ok=True)
    try:
        try:
            staged.symlink_to(target.name)
        except (OSError, NotImplementedError):
            staged.unlink(missing_ok=True)
            shutil.copy2(target, staged)
        staged.replace(latest)
    finally:
        staged.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=pathlib.Path)
    parser.add_argument("--mode", choices=["internal", "public", "public-release"], required=True)
    parser.add_argument("--build-tools", type=pathlib.Path, required=True)
    parser.add_argument("--expected-certificate", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    metadata = inspect(args.apk, args.build_tools)
    config = configparser.ConfigParser()
    config.read_string("[version]\n" + (ROOT / "version.properties").read_text())
    if (metadata["versionName"], metadata["versionCode"]) != (config["version"]["versionName"], config["version"].getint("versionCode")):
        raise SystemExit("APK version differs from version.properties")
    expected_id = "com.example.birdingsoundmvp" if args.mode == "internal" else "io.github.greatwyj1.owlett"
    if metadata["applicationId"] != expected_id or metadata["certificateSha256"] != args.expected_certificate.lower():
        raise SystemExit("Package/signature mismatch; refusing update delivery")
    args.output.mkdir(parents=True, exist_ok=True)
    latest = args.output / {"internal": "owlett-internal-debug.apk", "public": "owlett-public-debug.apk",
                            "public-release": "owlett-public-release.apk"}[args.mode]
    if latest.exists():
        old = inspect(latest, args.build_tools)
        if old["applicationId"] != expected_id or old["certificateSha256"] != metadata["certificateSha256"]:
            raise SystemExit("Existing latest uses another ID/signature; preserved unchanged")
        if old["versionCode"] > metadata["versionCode"]:
            raise SystemExit("Refusing to replace a newer APK")
        archive(latest, old, args.output, args.mode)
    target = archive(args.apk, metadata, args.output, args.mode)
    replace_latest(target, latest)
    manifest = args.output / "latest.json"
    temp = manifest.with_suffix(".json.tmp")
    temp.write_text(json.dumps(dict(metadata, artifact=target.name), ensure_ascii=False, indent=2) + "\n")
    temp.replace(manifest)
    print(json.dumps(dict(metadata, artifact=str(target)), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

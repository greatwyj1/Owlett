#!/usr/bin/env python3
"""Read-only heuristic scan. Reports locations, never matched secret values."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import zipfile

RULES = {
    "private-key": rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----",
    "provider-key": rb"(?:sk-[A-Za-z0-9_-]{24,}|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|AKIA[0-9A-Z]{16})",
    "credential-url": rb"https?://[^/\s:@]{2,}:[^/\s@]{4,}@",
    "literal-credential": rb"(?i)(?:api[_-]?key|api[_-]?token|auth[_-]?token|password|secret)\s*[=:]\s*[\"'][A-Za-z0-9_+/=.-]{20,}[\"']",
}
PRIVATE_NAMES = {".DS_Store", ".env", "local.properties", "detections.jsonl", "summary.json", "metadata.json", "owlett_chat.db", "birding_trips.db"}
def inspect(label, content):
    found = []
    for name, pattern in RULES.items():
        for match in re.finditer(pattern, content):
            found.append({"location": label, "line": content[:match.start()].count(b"\n") + 1, "rule": name})
    # UTF-16 strings in Android compiled resources.
    for name, pattern in RULES.items():
        if b"\0" in content[:256]:
            for match in re.finditer(pattern, content.replace(b"\0", b"")):
                found.append({"location": label, "line": None, "rule": name + "-utf16"})
    return found

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=pathlib.Path)
    parser.add_argument("--apk", action="append", type=pathlib.Path, default=[])
    parser.add_argument("--history", type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    findings, scanned = [], 0
    if args.source:
        for path in args.source.rglob("*"):
            if not path.is_file():
                continue
            label = path.relative_to(args.source).as_posix()
            if path.name in PRIVATE_NAMES or path.suffix in {".p12", ".jks", ".keystore", ".key", ".log", ".wav", ".mp3"} or ".git" in path.parts:
                findings.append({"location": label, "rule": "excluded-private-file"})
            findings.extend(inspect(label, path.read_bytes()))
            scanned += 1
    for apk in args.apk:
        with zipfile.ZipFile(apk) as archive:
            for entry in archive.infolist():
                if entry.is_dir():
                    continue
                if pathlib.PurePosixPath(entry.filename).name in PRIVATE_NAMES:
                    findings.append({"location": apk.name + ":" + entry.filename, "rule": "private-file-in-apk"})
                findings.extend(inspect(apk.name + ":" + entry.filename, archive.read(entry)))
                scanned += 1
    if args.history:
        output = subprocess.check_output(["git", "-C", str(args.history), "rev-list", "--objects", "--all"]).decode()
        for row in output.splitlines():
            oid, _, path = row.partition(" ")
            kind = subprocess.check_output(["git", "-C", str(args.history), "cat-file", "-t", oid]).strip()
            if kind != b"blob":
                continue
            content = subprocess.check_output(["git", "-C", str(args.history), "cat-file", "blob", oid])
            findings.extend(inspect("history:" + oid[:12] + ":" + path, content))
            if pathlib.PurePosixPath(path).name in PRIVATE_NAMES:
                findings.append({"location": "history:" + oid[:12] + ":" + path, "rule": "historical-private-file-review"})
            scanned += 1
        remote = subprocess.run(["git", "-C", str(args.history), "config", "--get-regexp", r"remote\..*\.url"], capture_output=True).stdout
        findings.extend(inspect("git-remote-config", remote))
    report = {"scanner": "Owlett heuristic scanner v1", "objects_scanned": scanned, "findings": findings,
              "limits": "Pattern scan only; not proof of absence. Does not establish prior GitHub exposure, inspect cloud backups, or validate third-party redistribution rights."}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"objects_scanned": scanned, "findings": len(findings), "report": str(args.output)}))

if __name__ == "__main__":
    main()

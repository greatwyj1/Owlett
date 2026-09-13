#!/usr/bin/env python3
"""Create a latest-workspace snapshot. No history, network upload, or git mutation."""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
DIRECTORIES = ["app/src", "chatui/src", "gradle/wrapper", "licenses", "docs", "server/precise_recognition/app", "server/precise_recognition/scripts", "server/precise_recognition/tests", "tools/release"]
FILES = [".gitignore", "AGENTS.md", "version.properties", "CONTRIBUTING.md", "SECURITY.md", "docs/RESOURCES.md", "docs/PUBLISHING.md", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
         "app/build.gradle.kts", "chatui/build.gradle.kts", "chatui/LICENSE", "chatui/UPSTREAM.md",
         "LICENSE", "NOTICE", "USER_GUIDE.md", "PRIVACY.md", "THIRD_PARTY_NOTICES.md", "CHANGELOG.md",
         "README.md", "PROJECT_STATUS.md", "docs/OWLETT_TOOLS.md", "resources/manifest.json", "resources/backend-models.json", "server/precise_recognition/README.md", "server/precise_recognition/DEPLOYMENT.md",
         "server/precise_recognition/requirements.txt", "server/precise_recognition/requirements.lock.txt",
         "server/precise_recognition/.env.example"]
DENY = {".git", ".DS_Store", "__pycache__", ".gradle", ".idea", "build", "release-output", "private-release"}
def allowed(path):
    rel = path.relative_to(ROOT)
    if set(rel.parts) & DENY or path.is_symlink():
        return False
    if str(rel).startswith("app/src/main/assets/"):
        return False  # Exact resource manifest and separate authorized bundle.
    return path.suffix.lower() not in {".pyc", ".key", ".pem", ".jks", ".p12", ".keystore", ".log", ".apk", ".wav", ".mp3", ".mp4", ".db", ".sqlite", ".zip"}

def validate_public_sources(paths):
    for path in paths:
        relative = path.relative_to(ROOT).as_posix()
        if relative.startswith(("app/src/main/", "chatui/src/main/")):
            if b"birdreport" in path.read_bytes().lower():
                raise SystemExit("Internal source reference detected: " + relative)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    branch = subprocess.run(["git", "branch", "--show-current"], cwd=ROOT, text=True, capture_output=True).stdout.strip() if (ROOT / ".git").exists() else ""
    if branch.endswith("-internal") or (ROOT / "INTERNAL_TEST_ONLY").exists() or (ROOT / "internal").exists():
        raise SystemExit("Refusing public snapshot: internal testing branch/module detected. Use the verified common branch in a separate clean checkout.")
    destination = args.output.resolve()
    archive_path = pathlib.Path(str(destination) + ".zip")
    if destination.exists() or archive_path.exists():
        raise SystemExit("Output already exists; choose a new directory.")
    paths = {ROOT / name for name in FILES if (ROOT / name).is_file()}
    paths.update(ROOT.glob("*.en.md"))
    paths.update((ROOT / "server/precise_recognition").glob("*.md"))
    for name in DIRECTORIES:
        paths.update(path for path in (ROOT / name).rglob("*") if path.is_file() and allowed(path))
    validate_public_sources(path for path in paths if allowed(path))
    destination.mkdir(parents=True)
    for source in sorted(paths):
        if not allowed(source):
            continue
        target = destination / source.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    inventory = [{"path": path.relative_to(destination).as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                 for path in sorted(destination.rglob("*")) if path.is_file()]
    (destination / "SOURCE_SHA256.json").write_text(json.dumps(inventory, indent=2) + "\n")
    with zipfile.ZipFile(archive_path, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(destination.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(destination))
    print("Source files:", len(inventory))
    print("Snapshot:", archive_path)
    print("No .git directory copied; existing development history unchanged.")

if __name__ == "__main__":
    main()

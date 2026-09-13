#!/usr/bin/env python3
"""Inventory, package, or restore the exact authorized Android resource snapshot."""
import argparse
import hashlib
import json
import pathlib
import shutil
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets"
MANIFEST = ROOT / "resources/manifest.json"
def digest(path):
    with path.open("rb") as stream:
        result = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
        return result.hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", action="store_true")
    parser.add_argument("--pack", type=pathlib.Path)
    parser.add_argument("--bundle", type=pathlib.Path)
    args = parser.parse_args()
    if args.inventory:
        files = []
        for path in sorted(ASSETS.rglob("*")):
            if not path.is_file() or path.name.startswith("."):
                continue
            relative = path.relative_to(ROOT).as_posix()
            if "ibirding_cn" in relative:
                license_id, source = "Permission-required", "https://www.ibirding.cn/birding/bird/"
            elif "taxonomy" in relative or "zheng" in relative:
                license_id, source = "Per-record; redistribution-review-required", "https://birdnet.cornell.edu/taxonomy/"
            elif path.suffix == ".tflite" or path.name == "labels.txt":
                license_id, source = "CC-BY-NC-SA-4.0", "https://github.com/birdnet-team/BirdNET-Analyzer"
            else:
                license_id, source = "See-NOTICE", "https://github.com/greatwyj1/Owlett"
            files.append(dict(path=relative, bytes=path.stat().st_size, sha256=digest(path), license=license_id, source=source))
        MANIFEST.write_text(json.dumps(dict(version="1.0.0", created="2026-09-07", redistribution="Hold until resource permissions are verified", files=files), indent=2) + "\n")
    manifest = json.loads(MANIFEST.read_text())
    if args.pack:
        args.pack.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.pack, "x", compression=zipfile.ZIP_DEFLATED) as archive:
            for entry in manifest["files"]:
                path = ROOT / entry["path"]
                if digest(path) != entry["sha256"]:
                    raise SystemExit("Resource changed after inventory: " + entry["path"])
                archive.write(path, entry["path"])
        print("Private resource bundle:", args.pack)
    if args.bundle:
        with tempfile.TemporaryDirectory(prefix="owlett-resources-") as temporary:
            stage = pathlib.Path(temporary)
            with zipfile.ZipFile(args.bundle) as archive:
                expected = {entry["path"]: entry for entry in manifest["files"]}
                if len(archive.namelist()) != len(expected) or set(archive.namelist()) != set(expected):
                    raise SystemExit("Resource bundle inventory mismatch")
                for name, entry in expected.items():
                    path = pathlib.PurePosixPath(name)
                    if path.is_absolute() or ".." in path.parts or not name.startswith("app/src/main/assets/"):
                        raise SystemExit("Unsafe resource path")
                    info = archive.getinfo(name)
                    if info.file_size != entry["bytes"] or info.file_size > 1024 * 1024 * 1024:
                        raise SystemExit("Resource size mismatch")
                    target = stage / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with archive.open(name) as source, target.open("wb") as output:
                        shutil.copyfileobj(source, output)
                    if digest(target) != entry["sha256"]:
                        raise SystemExit("Resource checksum mismatch: " + name)
            for entry in manifest["files"]:
                target = ROOT / entry["path"]
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(stage / entry["path"], target)
        print("Resources verified and restored:", len(manifest["files"]))

if __name__ == "__main__":
    main()

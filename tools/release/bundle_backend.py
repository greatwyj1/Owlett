#!/usr/bin/env python3
"""Package backend source and verified local models, never credentials or caches."""
import argparse
import hashlib
import json
import pathlib
import shutil
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def model_entries(manifest):
    entries = []
    for item in manifest["files"]:
        path = pathlib.PurePosixPath(item["path"])
        if any(part.startswith("._") for part in path.parts):
            continue
        if path.is_absolute() or ".." in path.parts or path.parts[0] not in {"perch", "geomodel", "birdnetlib"}:
            raise ValueError("Unsafe model inventory")
        entries.append(item)
    return entries


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--perch", required=True, type=pathlib.Path)
    parser.add_argument("--geomodel", required=True, type=pathlib.Path)
    parser.add_argument("--birdnetlib", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    destination = args.output.resolve()
    archive = pathlib.Path(str(destination) + ".zip")
    if destination.exists() or archive.exists():
        raise SystemExit("Refusing to overwrite existing bundle")
    entries = model_entries(json.loads((ROOT / "resources/backend-models.json").read_text()))
    roots = {"perch": args.perch, "geomodel": args.geomodel, "birdnetlib": args.birdnetlib}
    sources = []
    for entry in entries:
        path = pathlib.PurePosixPath(entry["path"])
        source = roots[path.parts[0]].joinpath(*path.parts[1:])
        if source.stat().st_size != entry["bytes"] or digest(source) != entry["sha256"]:
            raise SystemExit("Model checksum mismatch: " + entry["path"])
        sources.append((source, destination / "models" / entry["path"]))
    server = ROOT / "server/precise_recognition"
    for folder in ("app", "scripts", "tests"):
        sources.extend((p, destination / p.relative_to(server)) for p in (server / folder).rglob("*.py"))
    for name in ("requirements.txt", "requirements.lock.txt", ".env.example"):
        sources.append((server / name, destination / name))
    sources.append((server / "BUNDLE_README.md", destination / "README.md"))
    for name in ("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md", "version.properties"):
        sources.append((ROOT / name, destination / name))
    sources.extend((p, destination / p.relative_to(ROOT)) for p in (ROOT / "licenses").glob("*") if p.is_file())
    for source, target in sources:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    (destination / "models/manifest.json").write_text(json.dumps({"files": entries}, indent=2) + "\n")
    inventory = {p.relative_to(destination).as_posix(): digest(p) for p in sorted(destination.rglob("*")) if p.is_file()}
    (destination / "SHA256.json").write_text(json.dumps(inventory, indent=2) + "\n")
    with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as output:
        for p in sorted(destination.rglob("*")):
            if p.is_file():
                output.write(p, p.relative_to(destination))
    print(json.dumps({"archive": str(archive), "bytes": archive.stat().st_size,
                      "sha256": digest(archive), "model_files": len(entries)}, indent=2))


if __name__ == "__main__":
    main()

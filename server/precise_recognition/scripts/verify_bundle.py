#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[1]
    entries = json.loads((root / "models/manifest.json").read_text())["files"]
    for entry in entries:
        path = (root / "models" / entry["path"]).resolve()
        if not path.is_relative_to((root / "models").resolve()):
            raise SystemExit("Unsafe inventory path")
        h = hashlib.sha256()
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                h.update(block)
        if path.stat().st_size != entry["bytes"] or h.hexdigest() != entry["sha256"]:
            raise SystemExit("Model verification failed: " + entry["path"])
    print("All bundled model files verified:", len(entries))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Stage only app model/field-guide assets, excluding private runtime data."""
import argparse
import json
import pathlib
import shutil
import sqlite3
from contextlib import closing

from bundle_backend import digest


def backup_database(source_path, target):
    with closing(sqlite3.connect(source_path.resolve().as_uri() + "?mode=ro", uri=True)) as source:
        with closing(sqlite3.connect(target)) as dest:
            source.backup(dest)
            assert dest.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
            dest.execute("PRAGMA journal_mode=DELETE")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit("Refusing to overwrite resources")
    files = ["birdnet_model.tflite", "labels.txt", "BirdNET_GLOBAL_6K_V2.4_MData_Model_.tflite",
             "birdnet_taxonomy_zh.json", "zheng_bird_names.json", "notices/components.json"]
    files += [p.relative_to(args.source).as_posix() for p in (args.source / "ibirding_cn/assets").rglob("*")
              if p.is_file() and not p.is_symlink() and p.suffix.lower() in {".jpg", ".png", ".webp"} and not p.name.startswith(".")]
    for name in files:
        source, target = args.source / name, args.output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    for name in ("taxonomy/birdnet_taxonomy.db", "ibirding_cn/bird_species.db"):
        target = args.output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        backup_database(args.source / name, target)
    notice = args.output / "notices/components.json"
    notices = json.loads(notice.read_text())
    notices[0]["name"] = "Owlett"
    notices[0]["url"] = "https://github.com/greatwyj1/Owlett"
    notice.write_text(json.dumps(notices, ensure_ascii=False, indent=2) + "\n")
    inventory = {p.relative_to(args.output).as_posix(): digest(p) for p in sorted(args.output.rglob("*")) if p.is_file()}
    (args.output / "resource-checksums.json").write_text(json.dumps(inventory, indent=2) + "\n")
    print("Staged resources:", len(inventory))


if __name__ == "__main__":
    main()

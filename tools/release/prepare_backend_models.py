#!/usr/bin/env python3
"""Prepare and verify the exact CPU models. Downloads only upstream model artifacts."""
import argparse
import hashlib
import json
import pathlib
import importlib.util

ROOT = pathlib.Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "resources/backend-models.json"
REVISION = "892c1958a00d53d5073217ca4cbfb5c32499d4c7"
PERCH = "google/bird-vocalization-classifier/tensorFlow2/perch_v2_cpu/1"
def digest(file):
    value = hashlib.sha256()
    with file.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", action="store_true")
    args = parser.parse_args()
    import kagglehub
    from huggingface_hub import hf_hub_download
    perch = pathlib.Path(kagglehub.model_download(PERCH))
    files = []
    locations = {}
    for path in sorted(perch.rglob("*")):
        if path.is_file():
            key = "perch/" + path.relative_to(perch).as_posix()
            locations[key] = path
            files.append(dict(path=key, version=PERCH, license="Apache-2.0", source="https://www.kaggle.com/models/google/bird-vocalization-classifier/tensorFlow2/perch_v2_cpu/1", bytes=path.stat().st_size, sha256=digest(path)))
    for filename in ["BirdNET+_Geomodel_V3.0.2_Global_12K_FP16.onnx", "geomodel_v3.0.2_labels.txt"]:
        path = pathlib.Path(hf_hub_download("tphakala/BirdNET-Geomodel", filename=filename, revision=REVISION))
        key = "geomodel/" + filename
        locations[key] = path
        files.append(dict(path=key, version=REVISION, license="See upstream model card; redistribution review required", source="https://huggingface.co/tphakala/BirdNET-Geomodel/tree/" + REVISION, bytes=path.stat().st_size, sha256=digest(path)))
    package = pathlib.Path(importlib.util.find_spec("birdnetlib").origin).parent
    for path in sorted((package / "models/analyzer").glob("*")):
        if path.is_file() and path.suffix in {".tflite", ".txt"}:
            key = "birdnetlib/" + path.name
            locations[key] = path
            files.append(dict(path=key, version="birdnetlib-0.18.1/BirdNET-2.4", license="CC-BY-NC-SA-4.0", source="https://github.com/birdnet-team/BirdNET-Analyzer", bytes=path.stat().st_size, sha256=digest(path)))
    if args.inventory:
        MANIFEST.write_text(json.dumps(dict(version="1.0.0", files=files), indent=2) + "\n")
    expected = json.loads(MANIFEST.read_text())["files"]
    if {x["path"] for x in expected} != set(locations):
        raise SystemExit("Unexpected model inventory")
    for entry in expected:
        if digest(locations[entry["path"]]) != entry["sha256"]:
            raise SystemExit("Model checksum mismatch: " + entry["path"])
    print("Model files verified:", len(expected))
if __name__ == "__main__":
    main()

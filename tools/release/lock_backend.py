#!/usr/bin/env python3
"""Generate the backend lock from a tested environment, excluding unrelated packages."""
import importlib.metadata as meta
import json
from packaging.requirements import Requirement

roots = ["fastapi", "uvicorn", "python-multipart", "pydantic", "numpy", "soundfile", "librosa", "birdnetlib", "birdnet", "onnxruntime", "huggingface-hub", "tensorflow"]
seen = {}
def visit(name):
    dist = meta.distribution(name)
    key = dist.metadata["Name"]
    if key.lower().replace("_", "-") in seen:
        return
    seen[key.lower().replace("_", "-")] = (key, dist.version)
    for dependency in dist.requires or []:
        req = Requirement(dependency)
        if req.marker is None or req.marker.evaluate():
            visit(req.name)
for name in roots:
    visit(name)
print(json.dumps({"direct": [f"{name}=={meta.version(name)}" for name in roots],
    "locked": [f"{name}=={version}" for name, version in sorted(seen.values())]}))

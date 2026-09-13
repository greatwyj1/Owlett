#!/usr/bin/env python3
import argparse
import getpass
import os
from pathlib import Path
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", default=8000, type=int)
    args = parser.parse_args()
    token = os.environ.get("API_TOKEN") or getpass.getpass("Set your private API Token (at least 20 characters): ")
    if len(token) < 20:
        raise SystemExit("Please use a long random Token of at least 20 characters")
    os.environ["API_TOKEN"] = token
    os.environ["OWLETT_MODEL_DIR"] = str(Path(__file__).resolve().parents[1] / "models")
    os.environ["BIRDNET_GEOMODEL_V3_AUTO_DOWNLOAD"] = "false"
    os.environ["BIRDNET_V24_ENGINE"] = "birdnetlib"
    os.environ["PERCH_DEVICE"] = "CPU"
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    import uvicorn
    uvicorn.run("app.main:app", host=args.host, port=args.port)


if __name__ == "__main__":
    main()

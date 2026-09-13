#!/usr/bin/env python3
"""Real model + API smoke test, using generated silence only."""
import argparse
import io
import json
import os
import pathlib
import secrets
import sys

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", choices=["birdnet_v2_4", "perch_v2"], default="birdnet_v2_4")
    parser.add_argument("--geo", action="store_true")
    parser.add_argument("--offline", action="store_true", help="Reject socket connections to prove model inference is local")
    args = parser.parse_args()
    if args.offline:
        import socket
        def deny_network(*args, **kwargs):
            raise RuntimeError("Network connection forbidden during offline model check")
        socket.socket.connect = deny_network
        socket.create_connection = deny_network
    os.environ["API_TOKEN"] = secrets.token_urlsafe(32)
    os.environ["ACOUSTIC_MODEL"] = args.model
    os.environ["BIRDNET_ENABLE_GEO_FILTER"] = str(args.geo).lower()
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
    
    import numpy as np
    import soundfile as sf
    from fastapi.testclient import TestClient
    from app.main import app
    
    rate = 32000 if args.model == "perch_v2" else 48000
    duration = 5 if args.model == "perch_v2" else 3
    audio = io.BytesIO()
    sf.write(audio, np.zeros(rate * duration, dtype=np.float32), rate, format="WAV", subtype="PCM_16")
    meta = {"request_id": "synthetic-release-check", "trip_id": "synthetic", "selection_start_trip_audio_ms": 0,
            "selection_end_trip_audio_ms": duration * 1000, "acoustic_model": args.model, "min_confidence": 0.99, "overlap_sec": 0, "top_k": 3}
    if args.geo:
        meta.update(lat=0, lon=0, week=1)
    with TestClient(app) as client:
        health = client.get("/health")
        assert health.status_code == 200
        assert health.json()["loaded_acoustic_models"], "Model not loaded: " + str(health.json()["warnings"])
        files = {"audio": ("synthetic.wav", audio.getvalue(), "audio/wav")}
        assert client.post("/api/v1/precise-recognition", files=files, data={"meta": json.dumps(meta)}).status_code == 401
        response = client.post("/api/v1/precise-recognition", files=files, data={"meta": json.dumps(meta)},
                               headers={"Authorization": "Bearer " + os.environ["API_TOKEN"]})
        assert response.status_code == 200, response.text
        payload = response.json()
        assert not payload.get("warnings"), "Recognition warnings: " + str(payload["warnings"])
        assert payload["duration_sec"] == duration
        print(json.dumps({"status": "passed", "model": args.model, "geo": args.geo, "duration_sec": duration, "detections": len(payload["detections"])}))

if __name__ == "__main__":
    main()

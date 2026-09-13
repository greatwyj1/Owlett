# Precise Recognition Service

[简体中文](README.md) | [English](README.en.md)

FastAPI backend for the Android App precise-recognition flow. It accepts a short audio clip, normalizes it for the selected acoustic model, analyzes it with BirdNET V2.4 or Google Perch 2.0, and returns JSON detections plus a per-species summary.

## Backend choice

The preferred backend is `birdnetlib`, the Python library commonly used with BirdNET-Analyzer:

```bash
pip install birdnetlib
```

The service creates a `birdnetlib.analyzer.Analyzer` once during FastAPI startup and reuses it for requests. `birdnetlib` returns detections with `scientific_name`, `common_name`, `start_time`, `end_time`, and `confidence`, which maps cleanly to the API response. Geographic filtering is handled after acoustic inference by BirdNET GeoModel v3.0.2, so the service does not rely on birdnetlib's bundled v2.4 meta model for range filtering.

The service also supports Google Perch 2.0 through the official `birdnet` package (`birdnet.load_perch_v2`). For Perch requests, audio is normalized to mono 32 kHz and analyzed with 5-second windows. The Perch backend is post-processed like BirdNET-Go: it requests all raw logits, applies softmax across the full species vector, then applies `min_confidence` and `top_k`.

When `lat`, `lon`, and optionally `week` are provided, both BirdNET V2.4 and Perch detections are filtered through BirdNET GeoModel v3.0.2. The v3 model is open source in `birdnet-team/geomodel`; this service loads the compatible ONNX artifact used by BirdNET-Go (`tphakala/BirdNET-Geomodel`) or local files you provide via environment variables. Perch labels are mostly scientific names, so common names are filled from the v3 geomodel species list when available.

If `birdnetlib` is unavailable, the code falls back to the official `birdnet` package:

```python
import birdnet
model = birdnet.load("acoustic", "2.4", "tf")
```

As a last resort, the code can use a `birdnet-analyzer` CLI installation if one is available in the environment. The CLI fallback is useful for local experiments, but it starts a separate analyzer process per chunk, so it is slower and does not reuse an in-process model. If no backend is available, the API still starts and returns an empty `detections` array with a warning.

## Create environment

```bash
conda create -n birdnet python=3.11 -y
conda activate birdnet
```

If the `birdnet` environment already exists:

```bash
conda activate birdnet
```

## Install dependencies

From the project root:

```bash
cd server/precise_recognition
pip install -r requirements.txt
```

On macOS or Linux, if `soundfile` cannot read audio, install `libsndfile` with your system package manager.

GeoModel v3 filtering requires `onnxruntime`. If automatic model download is enabled, it also requires `huggingface-hub`. Both are listed in `requirements.txt`.

## Run

These development commands start from the project root. For token configuration and trusted-LAN setup, see the [deployment guide](DEPLOYMENT.en.md).

```bash
cd server/precise_recognition
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

Health check:

```bash
curl http://127.0.0.1:8000/health
```

## Test upload

This example assumes no token is set. With `API_TOKEN` configured, add the header described under Auth.

```bash
curl -X POST http://127.0.0.1:8000/api/v1/precise-recognition \
  -F 'audio=@/path/to/clip.wav;type=audio/wav' \
  -F 'meta={
    "request_id":"uuid",
    "trip_id":"trip_20260519_001",
    "selection_start_trip_audio_ms":123000,
    "selection_end_trip_audio_ms":135000,
    "lat":39.99,
    "lon":116.31,
    "week":18,
    "acoustic_model":"perch_v2",
    "min_confidence":0.05,
    "overlap_sec":1.5,
    "top_k":10
  }'
```

## Recognize a local audio file

Use the helper script to normalize MP3/WAV input and run the same service code directly:

```bash
conda activate birdnet
cd server/precise_recognition
python scripts/recognize_audio_file.py /path/to/clip.mp3 \
  --acoustic-model perch_v2 \
  --min-confidence 0.03 \
  --top-k 10 \
  --trim-to-max \
  --normalized-wav /private/tmp/precise_input.wav \
  --output-json /private/tmp/precise_result.json
```

`--trim-to-max` is explicit because the backend rejects audio longer than 15 seconds.

Android Emulator can call:

```text
http://10.0.2.2:8000/api/v1/precise-recognition
```

For a physical Android device, use the Mac's LAN IP address or deploy the service to a reachable server.

## Request

`POST /api/v1/precise-recognition`

`multipart/form-data` fields:

- `audio`: uploaded audio file. WAV is preferred.
- `meta`: JSON string.

The first version limits uploaded audio to 20 MB and 15 seconds after decoding. BirdNET V2.4 audio is converted server-side to mono 48 kHz `float32`; Perch 2.0 audio is converted to mono 32 kHz `float32`.

`meta.acoustic_model` is optional:

- `birdnet_v2_4`: default, BirdNET V2.4 acoustic model.
- `perch_v2`: Google Perch 2.0 acoustic model with BirdNET GeoModel v3 filtering when location metadata is provided.

## Smart chunking

The service always builds smart windows before calling the selected backend:

- BirdNET V2.4: `window_sec = 3.0`
- Perch 2.0: `window_sec = 5.0`
- `overlap_sec` defaults to `1.5`
- `hop_sec = window_sec - overlap_sec`

For clips shorter than or equal to the selected model window, it analyzes one padded window and reports the original clip end time. For longer clips, it creates overlapping windows and appends an end-aligned window if needed. Duplicate starts within 0.1 seconds are removed.

Run the chunker self-test:

```bash
python -m app.smart_chunker
```

## Auth

Authentication is optional for the first version. If `API_TOKEN` is set, requests must include:

```text
Authorization: Bearer <token>
```

Example:

```bash
export API_TOKEN=dev-secret
```

## Configuration

Environment variables:

- `API_TOKEN`: optional bearer token.
- `MAX_UPLOAD_BYTES`: default `20971520`.
- `MAX_AUDIO_DURATION_SEC`: default `15`.
- `TARGET_SAMPLE_RATE`: default `48000`.
- `DEFAULT_MIN_CONFIDENCE`: default `0.05`.
- `DEFAULT_OVERLAP_SEC`: default `1.5`.
- `DEFAULT_TOP_K`: default `10`.
- `ACOUSTIC_MODEL`: default `birdnet_v2_4`; set to `perch_v2` to make Perch 2.0 the default.
- `BIRDNET_BACKEND`: `auto`, `birdnetlib`, `python`, or `cli`; default `auto`.
- `BIRDNET_V24_ENGINE`: `birdnetlib` or `python`; default `birdnetlib`.
- `BIRDNET_GEO_MIN_CONFIDENCE`: default `0.03`; minimum GeoModel v3 occurrence score for range filtering.
- `BIRDNET_GEOMODEL_V3_MODEL_PATH`: optional local path to `BirdNET+_Geomodel_V3.0.2_Global_12K_FP16.onnx`.
- `BIRDNET_GEOMODEL_V3_LABELS_PATH`: optional local path to `geomodel_v3.0.2_labels.txt`.
- `BIRDNET_GEOMODEL_V3_AUTO_DOWNLOAD`: default `true`; downloads missing v3 files with `huggingface-hub`.
- `BIRDNET_GEOMODEL_V3_REPO`: default `tphakala/BirdNET-Geomodel`.
- `PERCH_DEVICE`: default `CPU`.
- `BIRDNET_ENABLE_GEO_FILTER`: default `true`.
- `PRECISE_RECOGNITION_TEMP_DIR`: optional temp directory.

Temporary upload and chunk files are created in the OS temp directory by default and deleted at the end of each request. Do not commit model files, uploaded audio, or temporary files.

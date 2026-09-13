# Set Up Backend Recognition on Your Computer

[简体中文](DEPLOYMENT.md) | [English](DEPLOYMENT.en.md)

When the phone's local model cannot identify a bird call, send the selected clip to Perch v2 or BirdNET 2.4 on your computer for another assessment. You run the service yourself; no public server is provided.

Return to [installation and API configuration](../../README.en.md#installation-and-api-configuration). The standalone backend Release ZIP is not yet published; its download entry is reserved in README. These steps also apply to `server/precise_recognition/` in the complete source tree.

## 1. Prepare the computer and environment

- Install Python 3.11 from the [official Python downloads page](https://www.python.org/downloads/).
- Internet is needed for dependencies and models, with enough free disk space. Initial setup may take time.
- A dedicated GPU is not required; this workflow uses CPU.
- Connect the phone and computer to the same trusted LAN. Keep the computer awake while using recognition.

Commands below target macOS / Linux. `requirements.lock.txt` is locked for Python 3.11 / macOS arm64; on Linux use `requirements.txt` to resolve platform-specific packages. Native Windows and WSL dependencies/networking have not been verified in this round and are not a verified one-click setup.

After extracting the package, open a terminal in its `server/precise_recognition` directory. Run subsequent commands there:

```bash
python3.11 -m venv .venv
source .venv/bin/activate
```

On Apple Silicon macOS:

```bash
python -m pip install -r requirements.lock.txt
python -m pip check
```

On Linux or another platform without a lock:

```bash
python -m pip install -r requirements.txt
python -m pip check
```

If `soundfile` cannot find `libsndfile`, install that library with your OS package manager and retry. Resolve installation errors before starting the service.

## 2. Prepare and check models

In the same activated terminal and directory:

```bash
python ../../tools/release/prepare_backend_models.py
```

The script prepares and verifies pinned Perch CPU, BirdNET 2.4, and GeoModel resources. It contacts upstream model sites without uploading your recordings. Keep `tools/release/prepare_backend_models.py` and `resources/backend-models.json` in the package; copying just `app/` is insufficient.

Each model retains its upstream license. If a download requires sign-in or acceptance of model terms, follow the upstream instructions. Download or hash-check failures do not mean a model is ready.

Check the model you plan to use:

```bash
# Recheck with a model different from the phone's BirdNET
python scripts/smoke_test.py --model perch_v2

# If you also want to choose BirdNET 2.4 in the app
python scripts/smoke_test.py --model birdnet_v2_4
```

The script uses synthetic silence to check model loading, token validation, and a complete audio request. `"status": "passed"` means that check passed, not that accuracy on real bird calls was validated.

The preparation script downloads the full manifest. Using only some models or operating offline requires your own configuration based on the manifest and backend source; do not ignore missing-resource failures.

## 3. Set a token and start

The access token is a password you set for this service. It does not require a third-party API application.

Find the computer's current Wi-Fi/Ethernet LAN IPv4 address in its network settings, for example `192.168.1.20`. Replace that sample address below with the actual address.

Run in the activated environment:

```bash
# Generate and display your token once; save it in the phone's settings
export API_TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
python -c 'import os; print(os.environ["API_TOKEN"])'

export ACOUSTIC_MODEL=perch_v2
export BIRDNET_ENABLE_GEO_FILTER=false
uvicorn app.main:app --host 192.168.1.20 --port 8000
```

If you checked only BirdNET 2.4, use `export ACOUSTIC_MODEL=birdnet_v2_4`. The model chosen in app requests must also be prepared.

Keep the terminal open. If the firewall asks, allow access only from the phone on the trusted LAN; do not disable the whole firewall. HTTP here is only for a trusted local network and does not encrypt audio or tokens. Internet-facing access requires HTTPS, access controls, and rate limits.

`.env.example` is a reference, not an automatically loaded configuration file. Environment variables do not automatically return after closing the terminal.

## 4. Connect the phone

1. Connect both devices to the same network, avoiding guest Wi-Fi with device isolation.
2. In the phone browser, visit `http://192.168.1.20:8000/health` with the actual IP. JSON indicates network reachability.
3. Check `loaded_acoustic_models` and `warnings`. `status=ok` alone does not mean a model loaded; the selected model must also pass its earlier check.
4. Open Owlett → Settings → Precise recognition (设置 → 精准识别):

| Field | Value |
| --- | --- |
| Server URL (服务地址) | `http://192.168.1.20:8000/api/v1/precise-recognition` using the real IP and full path. |
| Access token (访问令牌) | The computer's `API_TOKEN`. |
| Acoustic model (声学模型) | Perch v2 or BirdNET 2.4, after its check passes. |

Changes save automatically. `127.0.0.1` on the phone means the phone itself. Emulator-only addresses also do not apply to physical phones.

## 5. Submit a clip

1. Pause recording or open a saved recording trip.
2. Select at most **15 seconds** in the spectrogram and tap Precise recognition (精准识别).
3. Review the destination and submit. Wait for computer analysis; initial model loading may be slower.
4. Review species candidates, confidence, and warnings alongside local results and field observations.

A time selection sends that clip; a frequency selection sends bandpass-filtered audio. The full trip is not submitted. Coordinates may be attached when location is enabled. Uploads default to a 20 MB limit, and temporary audio is deleted after the request.

## 6. Restart and optional location filtering

Stop with `Ctrl+C`. Next time, return to `server/precise_recognition`, activate the environment, and set the **same token saved on the phone** before starting:

```bash
source .venv/bin/activate
export API_TOKEN='replace-with-your-saved-token'
export ACOUSTIC_MODEL=perch_v2
export BIRDNET_ENABLE_GEO_FILTER=false
uvicorn app.main:app --host 192.168.1.20 --port 8000
```

A newly generated token requires updating the phone. If the IP changes, update both the launch address and phone URL.

The steps above disable geographic filtering to check acoustic recognition first. To filter candidates by location and season, run:

```bash
python scripts/smoke_test.py --model perch_v2 --geo
```

After it passes, set `BIRDNET_ENABLE_GEO_FILTER=true`, restart, and enable location-assisted recognition and Android location permission. See the [backend technical reference](README.en.md) for variables.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Phone cannot open `/health` | Service running, correct IP/port, same network, firewall access, and router device isolation. |
| `401` | Phone token differs from the computer's `API_TOKEN`. |
| `400` | Clip over 15 seconds, oversized file, or unreadable audio. |
| `422` | Request format mismatch; check client/backend versions. |
| Server responds but no detections | Read `warnings` and model-check output to distinguish loading failure from no match. |
| Fails after restart | Reactivate the environment, set the token, and check for an IP change. |

This round checked documentation, source, and syntax, not dependency installation, model downloads, or phone-to-computer requests. See the [changelog](../../CHANGELOG.en.md) for the verification scope.

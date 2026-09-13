# Owlett Precise Recognition Backend · Model-Inclusive Bundle

[简体中文](BUNDLE_README.md) | [English](BUNDLE_README.en.md)

This bundle includes backend code, BirdNET 2.4 acoustic/prior models, the Perch v2 CPU model, GeoModel v3, labels, and verification manifests. These models do not need to be downloaded again. No public server is provided; run the service yourself.

**This is not a portable executable or a universal offline OS image.** Python 3.11 and its runtime dependencies are still required; installing Python packages initially needs internet access. The bundle does not include Python installers, drivers, or dependency packages for every Windows/Linux/macOS platform. The CPU version needs no GPU.

## Install and start

After extraction, open a terminal in the bundle directory:

```bash
python3.11 -m venv .venv
```

Activate on macOS/Linux with `source .venv/bin/activate`, or in Windows PowerShell with `.venv\Scripts\Activate.ps1`. On Windows, environment creation can use `py -3.11 -m venv .venv`.

```bash
python -m pip install -r requirements.txt
python scripts/verify_bundle.py
python scripts/smoke_test.py --model birdnet_v2_4 --geo --offline
python scripts/smoke_test.py --model perch_v2 --geo --offline
python scripts/start_bundle.py
```

The startup script privately prompts for an access token; input is not displayed. Choose a long random token, save it in a password manager, and enter the same value in the app. The default listener is `127.0.0.1`. For a phone on a trusted LAN, use `python scripts/start_bundle.py --host 0.0.0.0` and restrict firewall access to the required devices. `0.0.0.0` is not the address to enter on the phone.

On the phone, use `http://COMPUTER_LAN_IP:8000/api/v1/precise-recognition`. Internet-facing access requires HTTPS, access control, and rate limiting; do not expose this development service directly. After a restart, activate the environment, run the startup command, and enter the same token again.

## Resources and limitations

- Models default to the `models` directory beside `app`; move the whole bundle together. Missing files cause an error, not a silent download.
- Python dependencies use fixed versions, but platform compatibility may still require adjustments. Consult the delivery report for tested platforms and outstanding checks.
- Models run locally. GeoModel is a candidate-filtering prior, not a sighting probability. Silence tests verify loading and the interface, not real-call accuracy.
- Original code follows LICENSE; third-party models retain their licenses, described in THIRD_PARTY_NOTICES.md and models/manifest.json. Until redistribution rights for the converted GeoModel are clarified, the bundle is a local candidate, not an approved public resource package.
- No API keys, private recordings, uploads, account cookies, or chats are included. SHA256.json records file checksums.

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
import uuid
from pathlib import Path

_runtime_cache_dir = Path(tempfile.gettempdir()) / "precise_recognition_runtime"
_runtime_cache_dir.mkdir(parents=True, exist_ok=True)
os.environ.setdefault("MPLCONFIGDIR", str(_runtime_cache_dir / "matplotlib"))
os.environ.setdefault("NUMBA_CACHE_DIR", str(_runtime_cache_dir / "numba"))

import soundfile as sf

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.audio_utils import load_audio_mono
from app.birdnet_service import BirdNETService
from app.config import settings
from app.schemas import AnalysisParams, RecognitionMeta, RecognitionResponse


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Normalize an audio file and run precise BirdNET recognition.")
    parser.add_argument("audio_path", type=Path, help="Input audio file, such as mp3 or wav.")
    parser.add_argument("--request-id", default=None)
    parser.add_argument("--acoustic-model", choices=("birdnet_v2_4", "perch_v2"), default=None)
    parser.add_argument("--lat", type=float, default=None)
    parser.add_argument("--lon", type=float, default=None)
    parser.add_argument("--week", type=int, default=None)
    parser.add_argument("--min-confidence", type=float, default=settings.default_min_confidence)
    parser.add_argument("--overlap-sec", type=float, default=settings.default_overlap_sec)
    parser.add_argument("--top-k", type=int, default=settings.default_top_k)
    parser.add_argument("--trim-to-max", action="store_true", help="Trim audio to MAX_AUDIO_DURATION_SEC instead of failing.")
    parser.add_argument("--normalized-wav", type=Path, default=None, help="Optional path for the mono 48 kHz WAV copy.")
    parser.add_argument("--output-json", type=Path, default=None, help="Optional path for the recognition JSON.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.audio_path.exists():
        raise SystemExit(f"audio file not found: {args.audio_path}")

    service = BirdNETService(settings)
    meta = RecognitionMeta(
        request_id=args.request_id or str(uuid.uuid4()),
        acoustic_model=args.acoustic_model,
        lat=args.lat,
        lon=args.lon,
        week=args.week,
        min_confidence=args.min_confidence,
        overlap_sec=args.overlap_sec,
        top_k=args.top_k,
    )
    acoustic_model = service.acoustic_model_key(meta)
    audio = load_audio_mono(args.audio_path, service.sample_rate_for(acoustic_model))
    max_duration = settings.max_audio_duration_sec
    warnings: list[str] = []

    samples = audio.samples
    duration_sec = audio.duration_sec
    if duration_sec > max_duration:
        if not args.trim_to_max:
            raise SystemExit(
                f"audio duration {duration_sec:.3f}s exceeds {max_duration:.3f}s; "
                "rerun with --trim-to-max if you want to analyze the first allowed segment"
            )
        max_samples = int(round(max_duration * audio.sample_rate))
        samples = samples[:max_samples]
        duration_sec = float(samples.shape[0] / audio.sample_rate)
        warnings.append(f"Input was trimmed from {audio.duration_sec:.3f}s to {duration_sec:.3f}s.")

    normalized_wav = args.normalized_wav
    if normalized_wav is not None:
        normalized_wav.parent.mkdir(parents=True, exist_ok=True)
        sf.write(normalized_wav, samples, audio.sample_rate, subtype="PCM_16")

    detections, summary, service_warnings = service.analyze(samples, audio.sample_rate, duration_sec, meta)
    warnings.extend(service_warnings)

    response = RecognitionResponse(
        request_id=meta.request_id,
        duration_sec=round(duration_sec, 3),
        model=service.model_label_for(acoustic_model),
        analysis_params=AnalysisParams(
            acoustic_model=acoustic_model,
            window_sec=service.window_sec_for(acoustic_model),
            overlap_sec=meta.overlap_sec,
            min_confidence=meta.min_confidence,
            top_k=meta.top_k,
            smart_chunking=True,
        ),
        detections=detections,
        summary=summary,
        warnings=warnings,
    )
    payload = response.model_dump(mode="json")
    text = json.dumps(payload, ensure_ascii=False, indent=2)
    if args.output_json is not None:
        args.output_json.parent.mkdir(parents=True, exist_ok=True)
        args.output_json.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

from __future__ import annotations

import os
import tempfile
from dataclasses import dataclass
from pathlib import Path


_runtime_cache_dir = Path(tempfile.gettempdir()) / "precise_recognition_runtime"
_runtime_cache_dir.mkdir(parents=True, exist_ok=True)
os.environ.setdefault("MPLCONFIGDIR", str(_runtime_cache_dir / "matplotlib"))
os.environ.setdefault("NUMBA_CACHE_DIR", str(_runtime_cache_dir / "numba"))
os.environ.setdefault("BIRDNET_APP_DATA", str(_runtime_cache_dir / "birdnet_app_data"))


@dataclass(frozen=True)
class Settings:
    api_token: str | None
    max_upload_bytes: int
    max_audio_duration_sec: float
    target_sample_rate: int
    window_sec: float
    default_overlap_sec: float
    default_min_confidence: float
    default_top_k: int
    model_name: str
    birdnet_backend: str
    default_acoustic_model: str
    birdnet_v24_engine: str
    birdnet_geo_min_confidence: float
    birdnet_geo_v3_model_path: Path | None
    birdnet_geo_v3_labels_path: Path | None
    birdnet_geo_v3_revision: str
    birdnet_geo_v3_repo: str
    birdnet_geo_v3_model_file: str
    birdnet_geo_v3_labels_file: str
    birdnet_geo_v3_auto_download: bool
    perch_device: str
    enable_geo_filter: bool
    temp_dir: Path | None


def _get_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def get_settings() -> Settings:
    temp_dir_raw = os.getenv("PRECISE_RECOGNITION_TEMP_DIR")
    geo_model_path_raw = os.getenv("BIRDNET_GEOMODEL_V3_MODEL_PATH")
    geo_labels_path_raw = os.getenv("BIRDNET_GEOMODEL_V3_LABELS_PATH")
    from app.bundled_models import required_model
    if not geo_model_path_raw and not geo_labels_path_raw:
        model = required_model("geomodel/BirdNET+_Geomodel_V3.0.2_Global_12K_FP16.onnx")
        labels = required_model("geomodel/geomodel_v3.0.2_labels.txt")
        geo_model_path_raw = str(model) if model else None
        geo_labels_path_raw = str(labels) if labels else None
    return Settings(
        api_token=os.getenv("API_TOKEN"),
        max_upload_bytes=int(os.getenv("MAX_UPLOAD_BYTES", str(20 * 1024 * 1024))),
        max_audio_duration_sec=float(os.getenv("MAX_AUDIO_DURATION_SEC", "15")),
        target_sample_rate=int(os.getenv("TARGET_SAMPLE_RATE", "48000")),
        window_sec=float(os.getenv("BIRDNET_WINDOW_SEC", "3.0")),
        default_overlap_sec=float(os.getenv("DEFAULT_OVERLAP_SEC", "1.5")),
        default_min_confidence=float(os.getenv("DEFAULT_MIN_CONFIDENCE", "0.05")),
        default_top_k=int(os.getenv("DEFAULT_TOP_K", "10")),
        model_name=os.getenv("BIRDNET_MODEL_NAME", "BirdNET V2.4"),
        birdnet_backend=os.getenv("BIRDNET_BACKEND", "auto").strip().lower(),
        default_acoustic_model=os.getenv("ACOUSTIC_MODEL", "birdnet_v2_4").strip().lower(),
        birdnet_v24_engine=os.getenv("BIRDNET_V24_ENGINE", "birdnetlib").strip().lower(),
        birdnet_geo_min_confidence=float(os.getenv("BIRDNET_GEO_MIN_CONFIDENCE", "0.03")),
        birdnet_geo_v3_model_path=Path(geo_model_path_raw).expanduser() if geo_model_path_raw else None,
        birdnet_geo_v3_labels_path=Path(geo_labels_path_raw).expanduser() if geo_labels_path_raw else None,
        birdnet_geo_v3_revision=os.getenv("BIRDNET_GEOMODEL_V3_REVISION", "892c1958a00d53d5073217ca4cbfb5c32499d4c7"),
        birdnet_geo_v3_repo=os.getenv("BIRDNET_GEOMODEL_V3_REPO", "tphakala/BirdNET-Geomodel"),
        birdnet_geo_v3_model_file=os.getenv(
            "BIRDNET_GEOMODEL_V3_MODEL_FILE",
            "BirdNET+_Geomodel_V3.0.2_Global_12K_FP16.onnx",
        ),
        birdnet_geo_v3_labels_file=os.getenv("BIRDNET_GEOMODEL_V3_LABELS_FILE", "geomodel_v3.0.2_labels.txt"),
        birdnet_geo_v3_auto_download=_get_bool("BIRDNET_GEOMODEL_V3_AUTO_DOWNLOAD", True),
        perch_device=os.getenv("PERCH_DEVICE", "CPU").strip().upper(),
        enable_geo_filter=_get_bool("BIRDNET_ENABLE_GEO_FILTER", True),
        temp_dir=Path(temp_dir_raw) if temp_dir_raw else None,
    )


settings = get_settings()

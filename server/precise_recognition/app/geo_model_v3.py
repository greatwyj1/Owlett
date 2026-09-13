from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np

from app.config import Settings


@dataclass(frozen=True)
class GeoFilterResult:
    species: set[str]
    common_names: dict[str, str]
    scores: dict[str, float]

    @property
    def allowed_scientific_names(self) -> set[str]:
        return {extract_scientific_name(label) for label in self.species}


class GeoModelV3:
    def __init__(self, model_path: Path, labels_path: Path) -> None:
        try:
            import onnxruntime as ort
        except Exception as exc:
            raise RuntimeError("onnxruntime is required for BirdNET GeoModel v3 filtering") from exc

        self.model_path = model_path
        self.labels_path = labels_path
        self.labels = _load_labels(labels_path)
        if not self.labels:
            raise ValueError(f"GeoModel v3 labels file is empty: {labels_path}")

        self.session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
        self.input_name = self.session.get_inputs()[0].name
        self.output_name = self.session.get_outputs()[0].name

    def predict_scores(self, lat: float, lon: float, week: int | None) -> dict[str, float]:
        if not -90.0 <= lat <= 90.0:
            raise ValueError("lat must be in [-90, 90]")
        if not -180.0 <= lon <= 180.0:
            raise ValueError("lon must be in [-180, 180]")
        if week is not None and not 1 <= week <= 48:
            raise ValueError("week must be in [1, 48]")

        week_value = -1.0 if week is None else float(week)
        inputs = np.array([[lat, lon, week_value]], dtype=np.float32)
        raw = self.session.run([self.output_name], {self.input_name: inputs})[0]
        scores = np.asarray(raw, dtype=np.float32).reshape(-1)
        if scores.shape[0] != len(self.labels):
            raise ValueError(
                f"GeoModel v3 returned {scores.shape[0]} scores, but {len(self.labels)} labels were loaded"
            )
        return {label: float(score) for label, score in zip(self.labels, scores)}

    def species_filter(self, lat: float, lon: float, week: int | None, min_confidence: float) -> GeoFilterResult:
        scores = self.predict_scores(lat, lon, week)
        species = {label for label, score in scores.items() if score >= min_confidence}
        common_names = _common_name_map(species)
        return GeoFilterResult(species=species, common_names=common_names, scores=scores)


def load_geo_model_v3(settings: Settings) -> GeoModelV3:
    model_path, labels_path = resolve_geo_model_v3_paths(settings)
    return GeoModelV3(model_path=model_path, labels_path=labels_path)


def resolve_geo_model_v3_paths(settings: Settings) -> tuple[Path, Path]:
    model_path = settings.birdnet_geo_v3_model_path
    labels_path = settings.birdnet_geo_v3_labels_path

    if model_path is not None and labels_path is not None:
        if not model_path.is_file():
            raise FileNotFoundError(f"GeoModel v3 model file not found: {model_path}")
        if not labels_path.is_file():
            raise FileNotFoundError(f"GeoModel v3 labels file not found: {labels_path}")
        return model_path, labels_path

    if not settings.birdnet_geo_v3_auto_download:
        raise FileNotFoundError(
            "GeoModel v3 paths are not configured. Set BIRDNET_GEOMODEL_V3_MODEL_PATH and "
            "BIRDNET_GEOMODEL_V3_LABELS_PATH, or enable BIRDNET_GEOMODEL_V3_AUTO_DOWNLOAD."
        )

    try:
        from huggingface_hub import hf_hub_download
    except Exception as exc:
        raise RuntimeError(
            "huggingface-hub is required to auto-download GeoModel v3 files. "
            "Install it or set local BIRDNET_GEOMODEL_V3_MODEL_PATH and "
            "BIRDNET_GEOMODEL_V3_LABELS_PATH."
        ) from exc

    if model_path is None:
        model_path = Path(
            hf_hub_download(
                repo_id=settings.birdnet_geo_v3_repo,
                revision=settings.birdnet_geo_v3_revision,
                filename=settings.birdnet_geo_v3_model_file,
                repo_type="model",
            )
        )
    if labels_path is None:
        labels_path = Path(
            hf_hub_download(
                repo_id=settings.birdnet_geo_v3_repo,
                revision=settings.birdnet_geo_v3_revision,
                filename=settings.birdnet_geo_v3_labels_file,
                repo_type="model",
            )
        )
    return model_path, labels_path


def extract_scientific_name(label: str) -> str:
    scientific, _, _ = label.partition("_")
    return scientific.strip()


def split_species_name(species_name: str) -> tuple[str, str]:
    cleaned = species_name.strip()
    if "_" in cleaned:
        scientific_name, common_name = cleaned.split("_", 1)
        return scientific_name.strip(), common_name.strip()
    return cleaned, ""


def _common_name_map(species: Iterable[str]) -> dict[str, str]:
    result: dict[str, str] = {}
    for label in species:
        scientific_name, common_name = split_species_name(label)
        if common_name:
            result[scientific_name] = common_name
    return result


def _load_labels(path: Path) -> list[str]:
    return [line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]

from __future__ import annotations

import csv
import gc
import importlib.util
import inspect
import math
import shutil
import subprocess
import sys
import tempfile
import threading
from pathlib import Path
from typing import Any, Iterable

import numpy as np

from app.audio_utils import slice_and_pad, write_wav_temp
from app.config import Settings
from app.geo_model_v3 import GeoFilterResult, load_geo_model_v3
from app.schemas import Detection, RecognitionMeta, SpeciesSummary
from app.smart_chunker import AudioWindow, build_smart_windows

ACOUSTIC_MODEL_BIRDNET_V2_4 = "birdnet_v2_4"
ACOUSTIC_MODEL_PERCH_V2 = "perch_v2"


class BirdNETService:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.backend = "not_loaded"
        self.model_label = settings.model_name
        self.birdnetlib_analyzer: Any | None = None
        self.official_acoustic_models: dict[str, Any] = {}
        self.geo_model: Any | None = None
        self.geo_model_load_attempted = False
        self.startup_warnings: list[str] = []
        self.official_model_lock = threading.Lock()

    @property
    def loaded_acoustic_models(self) -> list[str]:
        loaded = sorted(self.official_acoustic_models)
        if self.birdnetlib_analyzer is not None:
            loaded.append(f"{ACOUSTIC_MODEL_BIRDNET_V2_4}:birdnetlib")
        return loaded

    def acoustic_model_key(self, meta: RecognitionMeta | None = None) -> str:
        key = (meta.acoustic_model if meta and meta.acoustic_model else self.settings.default_acoustic_model).strip().lower()
        aliases = {
            "birdnet": ACOUSTIC_MODEL_BIRDNET_V2_4,
            "birdnet_v2.4": ACOUSTIC_MODEL_BIRDNET_V2_4,
            "birdnet_v24": ACOUSTIC_MODEL_BIRDNET_V2_4,
            "perch": ACOUSTIC_MODEL_PERCH_V2,
            "perch_2.0": ACOUSTIC_MODEL_PERCH_V2,
            "perch_v20": ACOUSTIC_MODEL_PERCH_V2,
        }
        key = aliases.get(key, key)
        if key not in {ACOUSTIC_MODEL_BIRDNET_V2_4, ACOUSTIC_MODEL_PERCH_V2}:
            self.startup_warnings.append(f"Unknown acoustic model '{key}', falling back to BirdNET V2.4.")
            return ACOUSTIC_MODEL_BIRDNET_V2_4
        return key

    def model_label_for(self, acoustic_model: str) -> str:
        if acoustic_model == ACOUSTIC_MODEL_PERCH_V2:
            return "Google Perch 2.0 + BirdNET GeoModel V3"
        return "BirdNET V2.4 + BirdNET GeoModel V3"

    def window_sec_for(self, acoustic_model: str) -> float:
        if acoustic_model == ACOUSTIC_MODEL_PERCH_V2:
            return 5.0
        return self.settings.window_sec

    def sample_rate_for(self, acoustic_model: str) -> int:
        if acoustic_model == ACOUSTIC_MODEL_PERCH_V2:
            return 32_000
        return self.settings.target_sample_rate

    def load(self) -> None:
        if self.backend != "not_loaded":
            return
        default_model = self.acoustic_model_key()
        self._ensure_acoustic_backend(default_model, self.startup_warnings)
        self.backend = ",".join(self.loaded_acoustic_models) or "unavailable"
        if self.settings.enable_geo_filter:
            self._ensure_geo_model(self.startup_warnings)
        if not self.loaded_acoustic_models:
            self.startup_warnings.append("No acoustic backend is available. Install birdnetlib or birdnet.")

    def analyze(
        self,
        samples: np.ndarray,
        sample_rate: int,
        duration_sec: float,
        meta: RecognitionMeta,
    ) -> tuple[list[Detection], list[SpeciesSummary], list[str]]:
        acoustic_model = self.acoustic_model_key(meta)
        warnings = list(self.startup_warnings)
        self._ensure_acoustic_backend(acoustic_model, warnings)

        window_sec = self.window_sec_for(acoustic_model)
        windows = build_smart_windows(
            duration_sec=duration_sec,
            window_sec=window_sec,
            overlap_sec=meta.overlap_sec,
        )

        if acoustic_model == ACOUSTIC_MODEL_PERCH_V2:
            detections = self._analyze_with_official_acoustic_model(
                acoustic_model, samples, sample_rate, windows, meta, warnings
            )
        elif self.birdnetlib_analyzer is not None and self.settings.birdnet_v24_engine == "birdnetlib":
            detections = self._analyze_with_birdnetlib_backend(samples, sample_rate, windows, meta, warnings)
        elif ACOUSTIC_MODEL_BIRDNET_V2_4 in self.official_acoustic_models:
            detections = self._analyze_with_official_acoustic_model(
                ACOUSTIC_MODEL_BIRDNET_V2_4, samples, sample_rate, windows, meta, warnings
            )
        elif self.settings.birdnet_backend in {"auto", "cli"} and self._cli_available():
            detections = self._analyze_with_cli_backend(samples, sample_rate, windows, meta, warnings)
        else:
            detections = []
            warnings.append(f"No loaded backend can analyze {acoustic_model}.")

        detections = self._apply_geo_filter(detections, meta, warnings)
        detections = self._sort_and_trim(detections, meta.top_k)
        return detections, summarize_detections(detections), warnings

    def _ensure_acoustic_backend(self, acoustic_model: str, warnings: list[str]) -> None:
        if acoustic_model == ACOUSTIC_MODEL_BIRDNET_V2_4:
            if self.settings.birdnet_v24_engine == "birdnetlib":
                self._ensure_birdnetlib(warnings)
                if self.birdnetlib_analyzer is not None:
                    return
            self._ensure_official_acoustic_model(ACOUSTIC_MODEL_BIRDNET_V2_4, warnings)
            return

        if acoustic_model == ACOUSTIC_MODEL_PERCH_V2:
            self._ensure_official_acoustic_model(ACOUSTIC_MODEL_PERCH_V2, warnings)

    def _ensure_birdnetlib(self, warnings: list[str]) -> None:
        if self.birdnetlib_analyzer is not None:
            return
        if self.settings.birdnet_backend not in {"auto", "birdnetlib"}:
            return
        try:
            from app.bundled_models import load_birdnet_analyzer
            self.birdnetlib_analyzer = load_birdnet_analyzer()
        except Exception as exc:
            warnings.append(f"birdnetlib backend unavailable: {exc}")

    def _ensure_official_acoustic_model(self, acoustic_model: str, warnings: list[str]) -> None:
        if acoustic_model in self.official_acoustic_models:
            return
        try:
            import birdnet

            if acoustic_model == ACOUSTIC_MODEL_PERCH_V2:
                from app.perch_loader import load_perch_v2
                self.official_acoustic_models[acoustic_model] = load_perch_v2(self.settings.perch_device)
            else:
                from app.bundled_models import model_root
                if model_root() is not None:
                    raise RuntimeError("Bundled BirdNET requires BIRDNET_V24_ENGINE=birdnetlib; downloads are disabled")
                self.official_acoustic_models[acoustic_model] = birdnet.load("acoustic", "2.4", "tf")
        except Exception as exc:
            warnings.append(f"Official birdnet backend unavailable for {acoustic_model}: {exc}")

    def _ensure_geo_model(self, warnings: list[str]) -> Any | None:
        if self.geo_model is not None:
            return self.geo_model
        if self.geo_model_load_attempted or not self.settings.enable_geo_filter:
            return None
        self.geo_model_load_attempted = True
        try:
            self.geo_model = load_geo_model_v3(self.settings)
            return self.geo_model
        except Exception as exc:
            warnings.append(f"BirdNET GeoModel v3 filtering unavailable: {exc}")
            return None

    def _geo_species_filter(self, meta: RecognitionMeta, warnings: list[str]) -> GeoFilterResult | None:
        if meta.lat is None or meta.lon is None:
            return None
        geo_model = self._ensure_geo_model(warnings)
        if geo_model is None:
            return None
        try:
            return geo_model.species_filter(
                meta.lat,
                meta.lon,
                meta.week,
                self.settings.birdnet_geo_min_confidence,
            )
        except Exception as exc:
            warnings.append(f"BirdNET GeoModel v3 filtering skipped: {exc}")
            return None

    def _apply_geo_filter(
        self,
        detections: list[Detection],
        meta: RecognitionMeta,
        warnings: list[str],
    ) -> list[Detection]:
        if not detections or not self.settings.enable_geo_filter or meta.lat is None or meta.lon is None:
            return detections

        geo_filter = self._geo_species_filter(meta, warnings)
        if geo_filter is None:
            return detections

        allowed_scientific = geo_filter.allowed_scientific_names
        if not allowed_scientific:
            warnings.append("BirdNET GeoModel v3 returned no species for the provided location/week.")
            return []

        filtered: list[Detection] = []
        for detection in detections:
            if detection.scientific_name not in allowed_scientific:
                continue
            if not detection.common_name:
                detection = detection.model_copy(
                    update={"common_name": geo_filter.common_names.get(detection.scientific_name, "")}
                )
            filtered.append(detection)
        return filtered

    def _analyze_with_birdnetlib_backend(
        self,
        samples: np.ndarray,
        sample_rate: int,
        windows: list[AudioWindow],
        meta: RecognitionMeta,
        warnings: list[str],
    ) -> list[Detection]:
        if self.birdnetlib_analyzer is None:
            return []

        try:
            from birdnetlib import Recording
        except Exception as exc:
            warnings.append(f"birdnetlib Recording import failed: {exc}")
            return []

        detections: list[Detection] = []
        for window in windows:
            chunk = slice_and_pad(
                samples,
                sample_rate,
                start_sec=window.start_sec,
                end_sec=window.end_sec,
                padded_duration_sec=self.window_sec_for(ACOUSTIC_MODEL_BIRDNET_V2_4),
            )
            chunk_path = write_wav_temp(chunk, sample_rate, self.settings.temp_dir)
            try:
                # Geographic filtering is handled uniformly by BirdNET GeoModel v3
                # after acoustic inference, so birdnetlib's v2.4 meta model is not used here.
                recording_kwargs: dict[str, Any] = {"min_conf": meta.min_confidence}

                signature = inspect.signature(Recording)
                supported_kwargs = {
                    key: value
                    for key, value in recording_kwargs.items()
                    if key in signature.parameters and value is not None
                }
                recording = Recording(self.birdnetlib_analyzer, str(chunk_path), **supported_kwargs)
                recording.analyze()
                detections.extend(self._birdnetlib_detections_to_response(recording.detections, window, meta))
            except Exception as exc:
                warnings.append(f"birdnetlib prediction failed for {window.start_sec:.2f}-{window.end_sec:.2f}s: {exc}")
            finally:
                chunk_path.unlink(missing_ok=True)
        return detections

    def _analyze_with_official_acoustic_model(
        self,
        acoustic_model: str,
        samples: np.ndarray,
        sample_rate: int,
        windows: list[AudioWindow],
        meta: RecognitionMeta,
        warnings: list[str],
    ) -> list[Detection]:
        model = self.official_acoustic_models.get(acoustic_model)
        if model is None:
            return []

        detections: list[Detection] = []
        window_sec = self.window_sec_for(acoustic_model)
        for window in windows:
            chunk = slice_and_pad(
                samples,
                sample_rate,
                start_sec=window.start_sec,
                end_sec=window.end_sec,
                padded_duration_sec=window_sec,
            )
            try:
                is_perch = acoustic_model == ACOUSTIC_MODEL_PERCH_V2
                # The official birdnet/Perch backend can keep native resources open briefly
                # after prediction. Serialize access so overlapping requests do not exhaust
                # the process file-descriptor limit on smaller dev machines.
                with self.official_model_lock:
                    raw_predictions = model.predict_arrays(
                        (chunk, sample_rate),
                        top_k=None if is_perch else meta.top_k,
                        overlap_duration_s=0.0,
                        default_confidence_threshold=None if is_perch else meta.min_confidence,
                        apply_sigmoid=False if is_perch else True,
                        sigmoid_sensitivity=None if is_perch else 1.0,
                        device=self.settings.perch_device if is_perch else "CPU",
                    )
                detections.extend(
                    self._official_predictions_to_detections(
                        raw_predictions,
                        window=window,
                        min_confidence=meta.min_confidence,
                        top_k=meta.top_k,
                        score_transform="softmax" if is_perch else None,
                    )
                )
            except Exception as exc:
                warnings.append(
                    f"{self.model_label_for(acoustic_model)} prediction failed for "
                    f"{window.start_sec:.2f}-{window.end_sec:.2f}s: {exc}"
                )
            finally:
                gc.collect()
        return detections

    def _birdnetlib_detections_to_response(
        self,
        raw_detections: list[dict[str, Any]],
        window: AudioWindow,
        meta: RecognitionMeta,
    ) -> list[Detection]:
        detections: list[Detection] = []
        for item in raw_detections:
            confidence = _safe_float(item.get("confidence"))
            if confidence is None or confidence < meta.min_confidence:
                continue

            raw_start = _safe_float(item.get("start_time")) or 0.0
            raw_end = _safe_float(item.get("end_time")) or self.window_sec_for(ACOUSTIC_MODEL_BIRDNET_V2_4)
            start_sec = min(window.end_sec, window.start_sec + raw_start)
            end_sec = min(window.end_sec, window.start_sec + raw_end)
            if end_sec <= start_sec:
                end_sec = window.end_sec

            scientific_name = str(item.get("scientific_name") or "")
            common_name = str(item.get("common_name") or "")
            if not scientific_name:
                scientific_name, common_name = split_species_name(str(item.get("label") or ""))
            detections.append(
                Detection(
                    start_sec=round(start_sec, 3),
                    end_sec=round(end_sec, 3),
                    scientific_name=scientific_name,
                    common_name=common_name,
                    confidence=round(confidence, 6),
                )
            )
        detections.sort(key=lambda item: item.confidence, reverse=True)
        return detections[: meta.top_k]

    def _official_predictions_to_detections(
        self,
        raw_predictions: Any,
        window: AudioWindow,
        min_confidence: float,
        top_k: int,
        score_transform: str | None = None,
    ) -> list[Detection]:
        rows = list(_iter_prediction_rows(raw_predictions))
        if score_transform == "softmax":
            rows = _rows_with_softmax_confidence(rows)

        candidates: list[Detection] = []
        for row in rows:
            species_name = str(row.get("species_name") or row.get("species") or row.get("label") or "")
            if not species_name:
                continue
            confidence = _score_from_row(row)
            if confidence is None or confidence < min_confidence:
                continue
            scientific_name, common_name = split_species_name(species_name)

            raw_start = _safe_float(row.get("start_time")) or 0.0
            raw_end = _safe_float(row.get("end_time")) or (window.end_sec - window.start_sec)
            start_sec = min(window.end_sec, window.start_sec + raw_start)
            end_sec = min(window.end_sec, window.start_sec + raw_end)
            if end_sec <= start_sec:
                end_sec = window.end_sec

            candidates.append(
                Detection(
                    start_sec=round(start_sec, 3),
                    end_sec=round(end_sec, 3),
                    scientific_name=scientific_name,
                    common_name=common_name,
                    confidence=round(confidence, 6),
                )
            )
        candidates.sort(key=lambda item: item.confidence, reverse=True)
        return candidates[:top_k]

    def _analyze_with_cli_backend(
        self,
        samples: np.ndarray,
        sample_rate: int,
        windows: list[AudioWindow],
        meta: RecognitionMeta,
        warnings: list[str],
    ) -> list[Detection]:
        detections: list[Detection] = []
        for window in windows:
            chunk = slice_and_pad(
                samples,
                sample_rate,
                start_sec=window.start_sec,
                end_sec=window.end_sec,
                padded_duration_sec=self.window_sec_for(ACOUSTIC_MODEL_BIRDNET_V2_4),
            )
            chunk_path = write_wav_temp(chunk, sample_rate, self.settings.temp_dir)
            with tempfile.TemporaryDirectory(dir=self.settings.temp_dir) as out_dir:
                try:
                    command = [
                        sys.executable,
                        "-m",
                        "birdnet_analyzer.analyze",
                        str(chunk_path),
                        "-o",
                        out_dir,
                        "--rtype",
                        "csv",
                        "--min_conf",
                        str(meta.min_confidence),
                        "--top_n",
                        str(meta.top_k),
                        "--overlap",
                        "0.0",
                    ]
                    subprocess.run(command, check=True, capture_output=True, text=True, timeout=120)
                    detections.extend(self._parse_cli_output(Path(out_dir), window, meta))
                except Exception as exc:
                    warnings.append(f"birdnet-analyzer CLI failed for {window.start_sec:.2f}-{window.end_sec:.2f}s: {exc}")
                finally:
                    chunk_path.unlink(missing_ok=True)
        return detections

    def _parse_cli_output(self, out_dir: Path, window: AudioWindow, meta: RecognitionMeta) -> list[Detection]:
        csv_files = sorted(out_dir.rglob("*.csv"))
        detections: list[Detection] = []
        for csv_file in csv_files:
            with csv_file.open("r", encoding="utf-8", newline="") as handle:
                reader = csv.DictReader(handle)
                for row in reader:
                    species_name = row.get("species_name") or row.get("Species") or row.get("Common Name") or ""
                    confidence = _score_from_row(row)
                    if not species_name or confidence is None or confidence < meta.min_confidence:
                        continue
                    scientific_name, common_name = split_species_name(species_name)
                    detections.append(
                        Detection(
                            start_sec=round(window.start_sec, 3),
                            end_sec=round(window.end_sec, 3),
                            scientific_name=scientific_name,
                            common_name=common_name,
                            confidence=round(confidence, 6),
                        )
                    )
        detections.sort(key=lambda item: item.confidence, reverse=True)
        return detections[: meta.top_k]

    def _sort_and_trim(self, detections: list[Detection], top_k: int) -> list[Detection]:
        by_window: dict[tuple[float, float], list[Detection]] = {}
        for detection in detections:
            by_window.setdefault((detection.start_sec, detection.end_sec), []).append(detection)

        trimmed: list[Detection] = []
        for window_detections in by_window.values():
            window_detections.sort(key=lambda item: item.confidence, reverse=True)
            trimmed.extend(window_detections[:top_k])

        trimmed.sort(key=lambda item: (item.start_sec, -item.confidence, item.scientific_name, item.common_name))
        return trimmed

    def _cli_available(self) -> bool:
        return importlib.util.find_spec("birdnet_analyzer.analyze") is not None or shutil.which("birdnet-analyzer") is not None


def split_species_name(species_name: str) -> tuple[str, str]:
    cleaned = species_name.strip()
    if "_" in cleaned:
        scientific_name, common_name = cleaned.split("_", 1)
        return scientific_name.strip(), common_name.strip()
    return cleaned, ""


def summarize_detections(detections: list[Detection]) -> list[SpeciesSummary]:
    grouped: dict[tuple[str, str], list[Detection]] = {}
    for detection in detections:
        key = (detection.scientific_name, detection.common_name)
        grouped.setdefault(key, []).append(detection)

    summaries: list[SpeciesSummary] = []
    for (scientific_name, common_name), items in grouped.items():
        confidences = [item.confidence for item in items]
        summaries.append(
            SpeciesSummary(
                scientific_name=scientific_name,
                common_name=common_name,
                max_confidence=round(max(confidences), 6),
                mean_confidence=round(sum(confidences) / len(confidences), 6),
                num_detections=len(items),
                first_start_sec=min(item.start_sec for item in items),
                last_end_sec=max(item.end_sec for item in items),
            )
        )
    summaries.sort(key=lambda item: (-item.max_confidence, item.scientific_name, item.common_name))
    return summaries


def _rows_with_softmax_confidence(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    groups: dict[tuple[Any, float | None, float | None], list[dict[str, Any]]] = {}
    for row in rows:
        key = (
            row.get("input"),
            _safe_float(row.get("start_time")),
            _safe_float(row.get("end_time")),
        )
        groups.setdefault(key, []).append(row)

    transformed: list[dict[str, Any]] = []
    for group_rows in groups.values():
        logits = np.array([_score_from_row(row) for row in group_rows], dtype=np.float64)
        valid = np.isfinite(logits)
        if not valid.any():
            continue

        probs = np.zeros_like(logits, dtype=np.float64)
        valid_logits = logits[valid]
        valid_logits = valid_logits - np.max(valid_logits)
        exp_logits = np.exp(valid_logits)
        probs[valid] = exp_logits / np.sum(exp_logits)

        for row, prob in zip(group_rows, probs):
            if not np.isfinite(prob):
                continue
            updated = dict(row)
            updated["confidence"] = float(prob)
            transformed.append(updated)
    return transformed


def _iter_prediction_rows(raw_predictions: Any) -> Iterable[dict[str, Any]]:
    if raw_predictions is None:
        return []
    if hasattr(raw_predictions, "to_structured_array"):
        structured = raw_predictions.to_structured_array()
        rows: list[dict[str, Any]] = []
        for record in structured:
            row: dict[str, Any] = {}
            for name in structured.dtype.names or ():
                value = record[name]
                row[name] = value.item() if hasattr(value, "item") else value
            rows.append(row)
        return rows
    if hasattr(raw_predictions, "to_dict"):
        records = raw_predictions.to_dict("records")
        return records if isinstance(records, list) else []
    if isinstance(raw_predictions, dict):
        rows = []
        for key, value in raw_predictions.items():
            if isinstance(value, dict):
                for species_name, confidence in value.items():
                    rows.append({"interval": key, "species_name": species_name, "confidence": confidence})
            else:
                rows.append({"species_name": key, "confidence": value})
        return rows
    if isinstance(raw_predictions, list):
        rows = []
        for item in raw_predictions:
            if isinstance(item, dict):
                rows.append(item)
            elif isinstance(item, (list, tuple)) and len(item) >= 2:
                rows.append({"species_name": item[0], "confidence": item[1]})
        return rows
    return []


def _safe_float(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if math.isnan(number) or math.isinf(number):
        return None
    return number


def _score_from_row(row: dict[str, Any]) -> float | None:
    for key in ("confidence", "score", "probability", "Confidence"):
        if key in row:
            score = _safe_float(row.get(key))
            if score is not None:
                return score
    return None

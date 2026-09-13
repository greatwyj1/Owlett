from __future__ import annotations

from pydantic import BaseModel, Field, field_validator


class RecognitionMeta(BaseModel):
    request_id: str
    acoustic_model: str | None = None
    trip_id: str | None = None
    selection_start_trip_audio_ms: int | None = None
    selection_end_trip_audio_ms: int | None = None
    lat: float | None = None
    lon: float | None = None
    week: int | None = None
    min_confidence: float = Field(default=0.05, ge=0.0, le=1.0)
    overlap_sec: float = Field(default=1.5, ge=0.0)
    top_k: int = Field(default=10, ge=1, le=100)

    @field_validator("week")
    @classmethod
    def validate_week(cls, value: int | None) -> int | None:
        if value is not None and not 1 <= value <= 48:
            raise ValueError("week must be in [1, 48]")
        return value

    @field_validator("lat")
    @classmethod
    def validate_lat(cls, value: float | None) -> float | None:
        if value is not None and not -90 <= value <= 90:
            raise ValueError("lat must be in [-90, 90]")
        return value

    @field_validator("lon")
    @classmethod
    def validate_lon(cls, value: float | None) -> float | None:
        if value is not None and not -180 <= value <= 180:
            raise ValueError("lon must be in [-180, 180]")
        return value

    @field_validator("acoustic_model")
    @classmethod
    def validate_acoustic_model(cls, value: str | None) -> str | None:
        if value is None:
            return value
        normalized = value.strip().lower()
        aliases = {
            "birdnet": "birdnet_v2_4",
            "birdnet_v2.4": "birdnet_v2_4",
            "birdnet_v24": "birdnet_v2_4",
            "perch": "perch_v2",
            "perch_2.0": "perch_v2",
            "perch_v20": "perch_v2",
        }
        normalized = aliases.get(normalized, normalized)
        if normalized not in {"birdnet_v2_4", "perch_v2"}:
            raise ValueError("acoustic_model must be 'birdnet_v2_4' or 'perch_v2'")
        return normalized


class Detection(BaseModel):
    start_sec: float
    end_sec: float
    scientific_name: str
    common_name: str
    confidence: float


class SpeciesSummary(BaseModel):
    scientific_name: str
    common_name: str
    max_confidence: float
    mean_confidence: float
    num_detections: int
    first_start_sec: float
    last_end_sec: float


class AnalysisParams(BaseModel):
    acoustic_model: str
    window_sec: float
    overlap_sec: float
    min_confidence: float
    top_k: int
    smart_chunking: bool


class RecognitionResponse(BaseModel):
    request_id: str
    duration_sec: float
    model: str
    analysis_params: AnalysisParams
    detections: list[Detection]
    summary: list[SpeciesSummary]
    warnings: list[str]

from __future__ import annotations

import json
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.audio_utils import load_audio_mono, save_upload_to_temp
from app.birdnet_service import BirdNETService
from app.config import settings
from app.schemas import AnalysisParams, RecognitionMeta, RecognitionResponse


birdnet_service = BirdNETService(settings)


@asynccontextmanager
async def lifespan(app: FastAPI):
    birdnet_service.load()
    yield


app = FastAPI(title="Precise Recognition Service", version="1.0.0", lifespan=lifespan)


@app.exception_handler(HTTPException)
async def http_exception_handler(_: Request, exc: HTTPException) -> JSONResponse:
    return JSONResponse(status_code=exc.status_code, content={"error": exc.detail})


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    return JSONResponse(status_code=422, content={"error": "request validation failed", "details": exc.errors()})


@app.exception_handler(Exception)
async def unhandled_exception_handler(_: Request, exc: Exception) -> JSONResponse:
    return JSONResponse(status_code=500, content={"error": "internal server error", "details": str(exc)})


def require_token(authorization: str | None = Header(default=None)) -> None:
    if not settings.api_token:
        return
    expected = f"Bearer {settings.api_token}"
    if authorization != expected:
        raise HTTPException(status_code=401, detail="missing or invalid bearer token")


def parse_meta(meta: str) -> RecognitionMeta:
    try:
        return RecognitionMeta.model_validate_json(meta)
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=400, detail=f"meta must be a JSON string: {exc}") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=f"invalid meta: {exc}") from exc


@app.get("/health")
async def health() -> dict[str, object]:
    return {
        "status": "ok",
        "model": birdnet_service.model_label,
        "backend": birdnet_service.backend,
        "loaded_acoustic_models": birdnet_service.loaded_acoustic_models,
        "warnings": birdnet_service.startup_warnings,
    }


@app.post("/api/v1/precise-recognition", response_model=RecognitionResponse)
@app.post("/api/v1/precise-recognition/", response_model=RecognitionResponse, include_in_schema=False)
async def precise_recognition(
    audio: UploadFile = File(...),
    meta: str = Form(...),
    _: None = Depends(require_token),
) -> RecognitionResponse:
    parsed_meta = parse_meta(meta)
    upload_path = None
    try:
        upload_path, _ = await save_upload_to_temp(audio, settings.max_upload_bytes, settings.temp_dir)
        acoustic_model = birdnet_service.acoustic_model_key(parsed_meta)
        audio_data = load_audio_mono(upload_path, birdnet_service.sample_rate_for(acoustic_model))
        if audio_data.duration_sec > settings.max_audio_duration_sec:
            raise HTTPException(
                status_code=400,
                detail=f"audio duration {audio_data.duration_sec:.3f}s exceeds {settings.max_audio_duration_sec:.3f}s",
            )

        detections, summary, warnings = birdnet_service.analyze(
            audio_data.samples,
            audio_data.sample_rate,
            audio_data.duration_sec,
            parsed_meta,
        )
        return RecognitionResponse(
            request_id=parsed_meta.request_id,
            duration_sec=round(audio_data.duration_sec, 3),
            model=birdnet_service.model_label_for(acoustic_model),
            analysis_params=AnalysisParams(
                acoustic_model=acoustic_model,
                window_sec=birdnet_service.window_sec_for(acoustic_model),
                overlap_sec=parsed_meta.overlap_sec,
                min_confidence=parsed_meta.min_confidence,
                top_k=parsed_meta.top_k,
                smart_chunking=True,
            ),
            detections=detections,
            summary=summary,
            warnings=warnings,
        )
    finally:
        await audio.close()
        if upload_path is not None:
            upload_path.unlink(missing_ok=True)

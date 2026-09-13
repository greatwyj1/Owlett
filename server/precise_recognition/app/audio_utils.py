from __future__ import annotations

import tempfile
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from fastapi import HTTPException, UploadFile


@dataclass(frozen=True)
class AudioData:
    samples: np.ndarray
    sample_rate: int
    duration_sec: float


async def save_upload_to_temp(
    upload: UploadFile,
    max_bytes: int,
    temp_dir: Path | None = None,
) -> tuple[Path, int]:
    suffix = Path(upload.filename or "clip.wav").suffix or ".wav"
    with tempfile.NamedTemporaryFile(
        prefix="precise_upload_",
        suffix=suffix,
        dir=temp_dir,
        delete=False,
    ) as handle:
        temp_path = Path(handle.name)
        total = 0
        while True:
            chunk = await upload.read(1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > max_bytes:
                handle.close()
                temp_path.unlink(missing_ok=True)
                raise HTTPException(status_code=400, detail=f"audio file exceeds {max_bytes} bytes")
            handle.write(chunk)

    if total == 0:
        temp_path.unlink(missing_ok=True)
        raise HTTPException(status_code=400, detail="audio file is empty")

    return temp_path, total


def load_audio_mono(path: Path, target_sample_rate: int) -> AudioData:
    try:
        import soundfile as sf

        samples, sample_rate = sf.read(path, dtype="float32", always_2d=False)
    except Exception:
        import librosa

        samples, sample_rate = librosa.load(path, sr=None, mono=False, dtype=np.float32)
        if samples.ndim == 2:
            samples = samples.T

    samples = np.asarray(samples, dtype=np.float32)
    if samples.ndim == 2:
        samples = np.mean(samples, axis=1, dtype=np.float32)
    elif samples.ndim != 1:
        raise HTTPException(status_code=400, detail="unsupported audio channel layout")

    if samples.size == 0:
        raise HTTPException(status_code=400, detail="audio contains no samples")

    if sample_rate != target_sample_rate:
        import librosa

        samples = librosa.resample(samples, orig_sr=sample_rate, target_sr=target_sample_rate).astype(np.float32)
        sample_rate = target_sample_rate

    samples = np.nan_to_num(samples, nan=0.0, posinf=0.0, neginf=0.0).astype(np.float32)
    duration_sec = float(samples.shape[0] / sample_rate)
    return AudioData(samples=samples, sample_rate=sample_rate, duration_sec=duration_sec)


def load_audio_mono_48k(path: Path, target_sample_rate: int) -> AudioData:
    return load_audio_mono(path, target_sample_rate)


def slice_and_pad(samples: np.ndarray, sample_rate: int, start_sec: float, end_sec: float, padded_duration_sec: float) -> np.ndarray:
    start_idx = max(0, int(round(start_sec * sample_rate)))
    end_idx = min(samples.shape[0], int(round(end_sec * sample_rate)))
    target_len = int(round(padded_duration_sec * sample_rate))
    window = samples[start_idx:end_idx].astype(np.float32, copy=True)
    if window.shape[0] < target_len:
        window = np.pad(window, (0, target_len - window.shape[0]), mode="constant")
    elif window.shape[0] > target_len:
        window = window[:target_len]
    return window


def write_wav_temp(samples: np.ndarray, sample_rate: int, temp_dir: Path | None = None) -> Path:
    import soundfile as sf

    with tempfile.NamedTemporaryFile(
        prefix="precise_chunk_",
        suffix=".wav",
        dir=temp_dir,
        delete=False,
    ) as handle:
        path = Path(handle.name)
    sf.write(path, samples, sample_rate, subtype="PCM_16")
    return path

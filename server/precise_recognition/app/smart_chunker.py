from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class AudioWindow:
    start_sec: float
    end_sec: float
    padded_end_sec: float


def build_smart_windows(
    duration_sec: float,
    window_sec: float = 3.0,
    overlap_sec: float = 1.5,
    duplicate_start_tolerance_sec: float = 0.1,
) -> list[AudioWindow]:
    if duration_sec <= 0:
        raise ValueError("duration_sec must be positive")
    if window_sec <= 0:
        raise ValueError("window_sec must be positive")
    if overlap_sec < 0:
        raise ValueError("overlap_sec must be >= 0")
    if overlap_sec >= window_sec:
        raise ValueError("overlap_sec must be smaller than window_sec")

    if duration_sec <= window_sec:
        return [AudioWindow(start_sec=0.0, end_sec=duration_sec, padded_end_sec=window_sec)]

    hop_sec = window_sec - overlap_sec
    starts: list[float] = []
    current = 0.0
    epsilon = 1e-9
    while current + window_sec <= duration_sec + epsilon:
        starts.append(round(current, 6))
        current += hop_sec

    tail_start = round(duration_sec - window_sec, 6)
    if not starts or all(abs(tail_start - start) >= duplicate_start_tolerance_sec for start in starts):
        starts.append(tail_start)

    deduped: list[float] = []
    for start in sorted(starts):
        if deduped and abs(start - deduped[-1]) < duplicate_start_tolerance_sec:
            continue
        deduped.append(start)

    return [
        AudioWindow(
            start_sec=start,
            end_sec=min(start + window_sec, duration_sec),
            padded_end_sec=start + window_sec,
        )
        for start in deduped
    ]


def _assert_close(actual: list[tuple[float, float]], expected: list[tuple[float, float]]) -> None:
    rounded = [(round(start, 2), round(end, 2)) for start, end in actual]
    if rounded != expected:
        raise AssertionError(f"expected {expected}, got {rounded}")


def run_self_test() -> None:
    short = build_smart_windows(2.0)
    _assert_close([(w.start_sec, w.end_sec) for w in short], [(0.0, 2.0)])

    exact = build_smart_windows(6.0, overlap_sec=1.5)
    _assert_close([(w.start_sec, w.end_sec) for w in exact], [(0.0, 3.0), (1.5, 4.5), (3.0, 6.0)])

    tail = build_smart_windows(5.2, overlap_sec=1.5)
    _assert_close([(w.start_sec, w.end_sec) for w in tail], [(0.0, 3.0), (1.5, 4.5), (2.2, 5.2)])


if __name__ == "__main__":
    run_self_test()

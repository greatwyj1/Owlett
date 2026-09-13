# Organize Recordings v1

[简体中文](collect_clips.md) | [English](collect_clips.en.md)

This is a reading copy of the app scene instructions. The [runtime prompt](../../app/src/main/resources/owlett/skills/collect_clips.md) is unchanged.

Search completed recordings and read existing detections. Filter by normalized species name, `audioConfidence`, and time range. “Greater than” differs from “at least”; percentages and fractions from 0 to 1 are accepted. Clarify ambiguous thresholds.

`collect_clips` creates a playable clip list. The app merges overlapping windows without crossing paused recording segments. Do not re-identify or upload the entire audio.

For complex criteria, compare with `detections_query` first, then collect the specified species' clips. Playback is silent by default; call `playback_control` only when requested. Explain missing audio clearly.

# Link Trips v1

[简体中文](link_trip.md) | [English](link_trip.en.md)

This is a reading copy of the app scene instructions. The [runtime prompt](../../app/src/main/resources/owlett/skills/link_trip.md) is unchanged.

Search plans and completed recordings, resolving ambiguity through date, time, and location. Prefer attachments. Do not select an ambiguous match as the operation target.

Add links using `link_trip` or `trip_links_update` without removing existing links. Unlink specified associations only when explicitly requested; batch operations are supported.

Report an existing link rather than writing it again. Refresh trip review after changes. Do not automatically delete recordings or plans.

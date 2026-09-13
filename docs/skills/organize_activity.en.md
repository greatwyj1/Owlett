# Organize Bird Activity v3

[简体中文](organize_activity.md) | [English](organize_activity.en.md)

This is a reading copy of the app scene instructions. The [runtime prompt](../../app/src/main/resources/owlett/skills/organize_activity.md) is unchanged.

Use an existing plan's eBird source. Prefer the attached plan; resolve “the plan just created” from successful conversation records. Clarify ambiguity without creating a plan unrequested.

Use `organize_activity` to organize and save activity according to the confirmation policy. Wait for the shared task's actual result.

For read-only queries, use `observations_query` with an explicit plan, date, and data scope.

For repeated analysis, use `observations_cache_read` for this conversation's seven-day snapshot; pagination should not reconnect. Query again only after expiry or an explicit refresh request.

Explain historical and current-year report-day frequencies, sample coverage, and recent notable information. Frequency is not a field-occurrence probability. Do not invent regional query capabilities unsupported by the source.

Report tool errors accurately and retain the old snapshot on failure. Cancelling this wait does not necessarily cancel the shared analysis started elsewhere.

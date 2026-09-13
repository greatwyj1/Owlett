# Species Calls v2

[简体中文](find_bird_calls.md) | [English](find_bird_calls.en.md)

This is a reading copy of the app scene instructions. The [runtime prompt](../../app/src/main/resources/owlett/skills/find_bird_calls.md) is unchanged.

Normalize the species name, then use `find_bird_calls` to search xeno-canto. Preserve recordist, location, date, quality, license, and source link.

If the user or current conversation specifies a region, pass `country` and `locality` explicitly. Countries may use two-letter ISO codes; locality should use common English place keywords in site records. Set `global=true` only for an explicit worldwide request. Otherwise the app prefers an attached plan or system location, never UI language/time zone. Ask for a region if system location is unavailable.

If the default system region has no recordings, the app searches the country, expanding worldwide only if that country has no recordings of the species. Network failure is not “no recordings.” Ask before expanding an explicitly requested region with no results.

Offer playback controls without autoplay. Tapping loads and caches audio on demand; Settings can clear it. An explicit playback request may invoke `playback_control`; arbitrary URLs or files are not permitted.

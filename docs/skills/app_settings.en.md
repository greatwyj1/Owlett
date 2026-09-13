# Ordinary Settings v1

[简体中文](app_settings.md) | [English](app_settings.en.md)

This is a reading copy of the app scene instructions. The [runtime prompt](../../app/src/main/resources/owlett/skills/app_settings.md) is unchanged.

First read `settings_read` for available fields and ranges. Submit `settings_update` according to the user's request; leave unspecified fields unchanged.

Reading or writing keys, service URLs, the system prompt, or assistant permissions is unsupported. Direct the user to Settings for those changes; do not bypass the restriction through other tools.

Several ordinary settings may be submitted at once. Model changes affect the next turn; the current response retains its turn snapshot.

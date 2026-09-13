# Contributing

[简体中文](CONTRIBUTING.md) | [English](CONTRIBUTING.en.md)

Bug fixes, documentation improvements, tests, and feature discussions are welcome. Contributions are distributed under the project's existing license; make sure you have the right to contribute the code or assets.

1. Describe the use case before adding a feature. Bug reports should include version, device, reproduction steps, and expected behavior.
2. Use a separate branch, avoid unrelated refactoring, and do not commit keys, private recordings, chats, models, or permission-restricted field guides.
3. Run `./gradlew :app:verifyDeliveryMetadata :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.
4. Explain verification scope, compatibility, and device checks, update documentation, and submit a pull request.

Follow [SECURITY.en.md](SECURITY.en.md) for security issues. Do not publish sensitive service responses or private data. Keep the Chinese and English documentation counterparts synchronized; see the [documentation index](docs/INDEX.en.md).

# Owlett Development Workflow

[简体中文](AGENTS.zh-CN.md) | [English](AGENTS.md)

- Preserve unrelated changes and private data. Never upload or publish without an explicit request.
- After each completed modification round, increment the patch version and versionCode once in version.properties. Discussions, checks and repeated builds do not increment versions.
- Update CHANGELOG.md in Chinese for that version: date, changes, fixes, verification, pending device checks and versioned artifact location. Never invent version numbers for historical APKs.
- Keep PROJECT_STATUS.md focused on current status and links to detailed records.
- Before delivery run verifyDeliveryMetadata, tests, builds and applicable lint. Document failures or untested device behavior honestly.
- Archive APKs with version and build time, never overwrite versioned artifacts. Maintain a separate latest alias. Verify package ID and signing certificate before describing an APK as an update.
- Public repository title: Owlett - an AI agent for birding. Never include private testing adapters, resources without redistribution clearance, credentials, or development history.

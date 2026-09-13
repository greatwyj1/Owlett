# Changelog

[简体中文](CHANGELOG.md) | [English](CHANGELOG.en.md)

## 1.0.6 · 2026-09-13

- Version code: 10006.
- Concurrent release preparation added a standalone backend-model ZIP builder, verification, and startup entry points. Complete bundles prefer local BirdNET 2.4, Perch v2 CPU, and GeoModel resources, failing on missing files instead of silently downloading them.
- Added separate app-resource staging to include models/guides in installers without placing them in public source. Databases use consistent backups; WAL files, caches, and private data are excluded.
- Release preparation includes GitHub Releases instructions, notes, and SHA-256 checks. The old repository and installers remain; nothing is uploaded.
- Verification and resource-permission status for that work are covered by its delivery report; physical-device checks remain pending. Complete models do not make online services offline-capable; Python packages still require installation.
- Those candidates are archived under ../release-candidates-1.0.6-20260913/ and require the report's permission/device checks before publication.
- Added complete Chinese/English editions of user, development, publication, privacy, security, third-party, and backend documentation, with a bilingual index and language switches. Nine runtime scene prompts remain unchanged; separate bilingual reading copies are provided.
- Fixed the missing observation-results heading in the user guide and the publishing guide's description of a historical ZIP as the current delivery. Retained original license texts, historical versions, and unpublished download entries.
- Verification: 27 bilingual guide pairs and 307 local links/anchors passed checks; executable code blocks match. Hashes confirm 17 license, attribution, and runtime-prompt files are unchanged. Metadata checks, 124 JVM tests, 10 release-tool tests, Debug/Release builds, Android test compilation, and lint passed (0 errors, 27 warnings, 2 informational issues). Release is an unsigned verification artifact.
- Pending: no device tests, real API calls, model downloads, or inference in this round. Only documentation and version metadata change; app UI language and runtime skills are unchanged.
- Delivery: docs/INDEX.md and docs/INDEX.en.md index all bilingual guides. Build artifacts are separately archived under ../verification-1.0.6-20260913/, not official releases. Nothing uploaded; old ZIPs unchanged.

## 1.0.5 · 2026-09-12

- Version code: 10005.
- Replaced README's source-build section with installation and API setup for general users, covering four services, key-request links, app steps, and connection troubleshooting. Moved source/developer instructions to docs/RUN_FROM_SOURCE.md.
- Expanded computer-backend model preparation, startup, phone connection, and restart steps. APK and backend ZIP download entries remain empty at the user's request because they are unpublished.
- Clarified that immediate local recognition applies only to official model-inclusive APKs; manual plans and local replay need no API.
- Verification: metadata, 124 JVM tests, 8 release-tool tests, Debug/Release builds, Android test compilation, and lint passed (0 errors, 25 warnings, 2 informational issues). Local links in three guides and backend Python syntax passed. Release was an unsigned verification build.
- Pending: devices, real service keys, backend downloads/inference, and configuration screenshots.
- Delivery: README.md, docs/RUN_FROM_SOURCE.md, server/precise_recognition/DEPLOYMENT.md. Verification artifacts under ../verification-1.0.5-20260912/ are not formal releases. Original folder name and old source ZIP unchanged.

## 1.0.4 · 2026-09-12

- Version code: 10004.
- Rewrote README from actual source, adding birding scenarios, assistant examples, source builds, resources, documentation navigation, and license links.
- Clarified that source excludes models and guide assets, backend recognition is self-hosted, and debug builds are not formal releases.
- Verification: metadata, 124 JVM tests, 8 release-tool tests, Debug/Release builds, Android device-test compilation, and lint passed (0 errors, 24 warnings). README's 15 local links were valid. Release was unsigned; device tests were compiled, not run.
- Pending: phone recording, model recognition, and real services were not tested in this documentation/metadata round.
- Delivery: README.md in this directory. Verification artifacts are in ../verification-1.0.4-20260912/, with version, time, and hash in filenames and a separate latest debug alias. No APK published or source ZIP regenerated. The retained directory name is historical, not the current source version.

## 1.0.3 · 2026-09-11

- Version code: 10003. Public application ID: io.github.greatwyj1.owlett.
- Synchronized general tools/scenes, settings confirmation fixes, three themes, species thumbnails, separate sharing, call progress/cache, local-region priority, and seven-day conversation query reuse.
- Missing optional field guides return empty information instead of failing while attempting to install a missing database.
- Organized GitHub README, resources, contribution, security, and publishing guides under the title Owlett - an AI agent for birding.
- Verification: 124 JVM tests, 8 release-tool tests, Debug/Release builds, Android test compilation, metadata, and lint passed (0 errors, 25 warnings in the final isolated-source check, including dependency notices). An isolated source copy without models/guides was built. Syntax checks passed for 11 backend Python files. Release was verified unsigned and not delivered as an official installer.
- Pending: missing-resource behavior, in-place installation, guide scrolling, spectrogram/sharing, location/offline replay, real DeepSeek/eBird/xeno-canto calls, and backend model requests. No device was connected; earlier device/service outcomes were not carried forward.
- Source delivery: Owlett-1.0.3-20260911.zip, with separate verification/scope reports. Debug APKs in the batch's verification directory include version, build time, and digest; they are test packages, not officially signed releases.
- No GitHub upload. Publish only a history-free source snapshot, not the development workspace or its history. Models, private data, and guide resources with unverified redistribution rights are excluded.

# Run from Source

[简体中文](RUN_FROM_SOURCE.md) | [English](RUN_FROM_SOURCE.en.md)

This page is for developers who want to modify or build the Android client. General users should follow [installation and API configuration](../README.en.md#installation-and-api-configuration) to install a Release APK. Run all commands below from the project root.

## 1. Development environment

- JDK 17.
- Android SDK Platform 35 and Android SDK Build Tools.
- Android Studio or a command-line environment able to run Gradle.
- An Android 8.0+ phone or emulator. A phone is preferable for field-recording tests.

Open this directory as an Android Studio project and let the IDE configure the SDK. For command-line builds, set its absolute path in root-level `local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

The project includes Gradle Wrapper; a separate Gradle installation is unnecessary. The first build needs internet access to download dependencies.

## 2. Recognition resources

**This source snapshot includes neither models nor field-guide resources. It builds without them, but without a model it can record rather than perform live recognition.**

Before enabling live recognition, place a compatible, matching BirdNET TFLite model and label file here:

```text
app/src/main/assets/
├── birdnet_model.tflite
└── labels.txt
```

See [resources](RESOURCES.en.md) for model sources, optional prior models, and field-guide paths. Files listed in [resources/manifest.json](../resources/manifest.json) are not necessarily included in the source.

If you have a lawfully obtained bundle that exactly matches the manifest, verify and restore it with:

```bash
python3 tools/release/prepare_resources.py --bundle /absolute/path/to/resources.zip
```

This command does not download resources or accept arbitrary model ZIP files. Without guide resources, full local guide content, related name lookup, and images are limited.

## 3. Build and install

From the project root:

```bash
./gradlew :app:assembleDebug
```

On Windows use `gradlew.bat`. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. With a USB-debugging-enabled device connected, install directly:

```bash
./gradlew :app:installDebug
```

The default application ID is `io.github.greatwyj1.owlett`. Self-built debug APKs are for development. Installing over an existing app requires matching application ID and signing certificate.

## 4. Configuration and use

After installation, follow [installation and API configuration](../README.en.md#installation-and-api-configuration). Local recognition in a self-built APK depends on whether matching model resources were added before building.

## Development and verification

The client uses Kotlin, Jetpack Compose, TensorFlow Lite, and Media3.

| Directory | Contents |
| --- | --- |
| `app/` | Android recording, recognition, plans, assistant, and settings. |
| `chatui/` | Chat UI components and upstream licensing. |
| `app/src/main/resources/owlett/skills/` | Instructions for nine assistant scenes. |
| `server/precise_recognition/` | Optional recognition service. |
| `resources/` | Client-resource and backend-model manifests. |
| `tools/release/` | Resource checks, source packaging, and APK verification. |

Before submitting changes:

```bash
./gradlew :app:verifyDeliveryMetadata :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 -m unittest discover -s tools/release -p 'test_*.py'
```

Passing tests does not establish that phone recording, real models, or external services have been verified. See [project status](../PROJECT_STATUS.en.md) and the [changelog](../CHANGELOG.en.md) for results and pending checks. Read [contributing](../CONTRIBUTING.en.md) and [security reporting](../SECURITY.en.md).

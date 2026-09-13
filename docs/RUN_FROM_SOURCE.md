# 从源码运行

[简体中文](RUN_FROM_SOURCE.md) | [English](RUN_FROM_SOURCE.en.md)

本页面向希望修改或自行构建 Android 客户端的开发者。一般用户请按 [安装与配置 API](../README.md#安装与配置-api) 安装 Release APK。以下命令均在项目根目录执行。

## 1. 准备开发环境

- JDK 17。
- Android SDK Platform 35，以及 Android SDK 构建工具。
- Android Studio，或可运行 Gradle 的命令行环境。
- Android 8.0+ 手机或模拟器；测试现场录音建议使用手机。

打开本目录作为 Android Studio 项目，让 IDE 配置 SDK；使用命令行时，在根目录的 `local.properties` 中填写本机 SDK 的绝对路径：

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

项目自带 Gradle Wrapper，无需单独安装 Gradle。首次构建需要联网下载依赖。

## 2. 按需准备识别资源

**这份源码不附带模型和图鉴。可以直接构建，但没有模型时只能录音，不能进行本地实时识别。**

启用实时识别前，把适配此客户端、彼此匹配的 BirdNET TFLite 模型与标签放到：

```text
app/src/main/assets/
├── birdnet_model.tflite
└── labels.txt
```

模型来源、可选先验模型与图鉴路径见 [资源说明](RESOURCES.md)。资源清单见 [resources/manifest.json](../resources/manifest.json)；清单列出文件并不表示这些文件已随源码提供。

如果你已有合法取得、与清单完整匹配的资源包，可用下列命令校验并恢复：

```bash
python3 tools/release/prepare_resources.py --bundle /absolute/path/to/resources.zip
```

该命令不会下载资源，不接受任意模型 ZIP。没有图鉴资源时，完整本地图鉴、相关名称检索和图片展示会受限。

## 3. 构建与安装

在项目根目录运行：

```bash
./gradlew :app:assembleDebug
```

Windows 使用 `gradlew.bat`。生成的调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。连接已启用 USB 调试的设备后，也可以直接安装：

```bash
./gradlew :app:installDebug
```

默认应用编号为 `io.github.greatwyj1.owlett`。自行构建的 Debug APK 用于开发测试；覆盖已有安装需要包名和签名一致。


## 4. 配置与使用

构建安装后，按 [安装与配置 API](../README.md#安装与配置-api) 设置所需服务。自行构建的 APK 是否支持本地识别，取决于构建前是否放入匹配的模型资源。

## 开发与验证

客户端使用 Kotlin、Jetpack Compose、TensorFlow Lite 和 Media3。主要目录如下：

| 目录 | 内容 |
| --- | --- |
| `app/` | Android 客户端：录音、识别、计划、助手与设置。 |
| `chatui/` | 聊天界面组件及其上游许可说明。 |
| `app/src/main/resources/owlett/skills/` | 九个助手场景的说明。 |
| `server/precise_recognition/` | 可选精准识别服务。 |
| `resources/` | 客户端资源及后端模型清单。 |
| `tools/release/` | 资源校验、源码打包与安装包检查工具。 |

提交修改前运行：

```bash
./gradlew :app:verifyDeliveryMetadata :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 -m unittest discover -s tools/release -p 'test_*.py'
```

测试通过不代表手机录音、真实模型和外部服务已经验收。当前检查结果及待验收项目见 [项目状态](../PROJECT_STATUS.md) 和 [更新记录](../CHANGELOG.md)。参与开发见 [贡献指南](../CONTRIBUTING.md)，安全问题见 [SECURITY.md](../SECURITY.md)。


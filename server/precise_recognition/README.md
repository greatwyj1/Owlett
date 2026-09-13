# 精准识别服务

[简体中文](README.md) | [English](README.en.md)

这是 Android App 精准识别功能的 FastAPI 后端。它接收短音频，按所选声学模型标准化，使用 BirdNET V2.4 或 Google Perch 2.0 分析，返回 JSON 识别结果与各鸟种汇总。

## 后端选择

首选后端是常与 BirdNET-Analyzer 配合使用的 Python 库 `birdnetlib`：

```bash
pip install birdnetlib
```

服务在 FastAPI 启动时创建一次 `birdnetlib.analyzer.Analyzer`，后续请求复用。返回字段 `scientific_name`、`common_name`、`start_time`、`end_time`、`confidence` 可直接映射到 API 响应。地理筛选在声学推理后由 BirdNET GeoModel v3.0.2 完成，不依赖 birdnetlib 内置的 v2.4 先验模型。

服务也通过官方 `birdnet` 包（`birdnet.load_perch_v2`）支持 Google Perch 2.0。Perch 音频标准化为单声道 32 kHz，以 5 秒窗口分析。后处理参考 BirdNET-Go：请求全部原始 logits，对完整物种向量计算 softmax，再应用 `min_confidence` 和 `top_k`。

提供 `lat`、`lon` 以及可选 `week` 时，两类检测结果都会经过 BirdNET GeoModel v3.0.2 筛选。v3 模型公开于 `birdnet-team/geomodel`；此服务加载 BirdNET-Go 使用的兼容 ONNX 文件（`tphakala/BirdNET-Geomodel`），也支持通过环境变量指定本地文件。Perch 标签主要为学名，通用名在可用时从 v3 地理模型物种列表补充。

若 `birdnetlib` 不可用，代码回退到官方 `birdnet` 包：

```python
import birdnet
model = birdnet.load("acoustic", "2.4", "tf")
```

最后可回退到已安装的 `birdnet-analyzer` 命令行程序。该路径适合本地实验，但每个片段都启动单独进程，速度更慢，也不会复用进程内模型。所有后端均不可用时，API 仍可启动，但返回空 `detections` 和警告。

## 创建环境

```bash
conda create -n birdnet python=3.11 -y
conda activate birdnet
```

已有 `birdnet` 环境时：

```bash
conda activate birdnet
```

## 安装依赖

在项目根目录执行：

```bash
cd server/precise_recognition
pip install -r requirements.txt
```

macOS 或 Linux 上若 `soundfile` 无法读取音频，请用系统包管理器安装 `libsndfile`。

GeoModel v3 筛选需要 `onnxruntime`；自动下载模型还需要 `huggingface-hub`，二者均列在 `requirements.txt` 中。

## 启动

以下命令从项目根目录进入后端目录，适用于开发调试；一般用户的令牌配置与局域网启动步骤见 [部署指南](DEPLOYMENT.md)。

```bash
cd server/precise_recognition
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

健康检查：

```bash
curl http://127.0.0.1:8000/health
```

## 测试上传

未设置令牌时的请求示例；设置 `API_TOKEN` 后，需额外加入下方“鉴权”一节的请求头。

```bash
curl -X POST http://127.0.0.1:8000/api/v1/precise-recognition \
  -F 'audio=@/path/to/clip.wav;type=audio/wav' \
  -F 'meta={
    "request_id":"uuid",
    "trip_id":"trip_20260519_001",
    "selection_start_trip_audio_ms":123000,
    "selection_end_trip_audio_ms":135000,
    "lat":39.99,
    "lon":116.31,
    "week":18,
    "acoustic_model":"perch_v2",
    "min_confidence":0.05,
    "overlap_sec":1.5,
    "top_k":10
  }'
```

## 识别本地音频文件

辅助脚本可标准化 MP3/WAV，并直接调用同一套服务代码：

```bash
conda activate birdnet
cd server/precise_recognition
python scripts/recognize_audio_file.py /path/to/clip.mp3 \
  --acoustic-model perch_v2 \
  --min-confidence 0.03 \
  --top-k 10 \
  --trim-to-max \
  --normalized-wav /private/tmp/precise_input.wav \
  --output-json /private/tmp/precise_result.json
```

必须显式使用 `--trim-to-max` 才会截短，因为后端拒绝超过 15 秒的音频。

Android 模拟器可访问：

```text
http://10.0.2.2:8000/api/v1/precise-recognition
```

真实 Android 设备应使用电脑的局域网 IP，或部署到可达的服务器。

## 请求格式

`POST /api/v1/precise-recognition`

`multipart/form-data` 字段：

- `audio`：上传音频，建议 WAV。
- `meta`：JSON 字符串。

初版限制上传文件 20 MB、解码后时长 15 秒。BirdNET V2.4 音频在服务器转换为单声道 48 kHz `float32`；Perch 2.0 转换为单声道 32 kHz `float32`。

`meta.acoustic_model` 可省略：

- `birdnet_v2_4`：默认，BirdNET V2.4 声学模型。
- `perch_v2`：Google Perch 2.0；请求提供位置元数据时可结合 BirdNET GeoModel v3 筛选。

## 智能分窗

调用所选后端前，服务会生成分析窗口：

- BirdNET V2.4：`window_sec = 3.0`。
- Perch 2.0：`window_sec = 5.0`。
- `overlap_sec` 默认 `1.5`。
- `hop_sec = window_sec - overlap_sec`。

短于或等于窗口的片段会补齐为一个窗口分析，但报告原始结束时间。较长片段使用重叠窗口，并补充一个尾部对齐窗口。起点相差 0.1 秒以内的重复窗口会被移除。

运行分窗自检：

```bash
python -m app.smart_chunker
```

## 鉴权

初版鉴权可选。设置 `API_TOKEN` 后，请求必须包含：

```text
Authorization: Bearer <token>
```

开发示例：

```bash
export API_TOKEN=dev-secret
```

## 配置

环境变量：

- `API_TOKEN`：可选 Bearer 令牌。
- `MAX_UPLOAD_BYTES`：默认 `20971520`。
- `MAX_AUDIO_DURATION_SEC`：默认 `15`。
- `TARGET_SAMPLE_RATE`：默认 `48000`。
- `DEFAULT_MIN_CONFIDENCE`：默认 `0.05`。
- `DEFAULT_OVERLAP_SEC`：默认 `1.5`。
- `DEFAULT_TOP_K`：默认 `10`。
- `ACOUSTIC_MODEL`：默认 `birdnet_v2_4`，设为 `perch_v2` 可使用 Perch 2.0 作为默认模型。
- `BIRDNET_BACKEND`：`auto`、`birdnetlib`、`python` 或 `cli`；默认 `auto`。
- `BIRDNET_V24_ENGINE`：`birdnetlib` 或 `python`；默认 `birdnetlib`。
- `BIRDNET_GEO_MIN_CONFIDENCE`：默认 `0.03`，GeoModel v3 的最低出现分数。
- `BIRDNET_GEOMODEL_V3_MODEL_PATH`：可选本地 `BirdNET+_Geomodel_V3.0.2_Global_12K_FP16.onnx` 路径。
- `BIRDNET_GEOMODEL_V3_LABELS_PATH`：可选本地 `geomodel_v3.0.2_labels.txt` 路径。
- `BIRDNET_GEOMODEL_V3_AUTO_DOWNLOAD`：默认 `true`，通过 `huggingface-hub` 下载缺失的 v3 文件。
- `BIRDNET_GEOMODEL_V3_REPO`：默认 `tphakala/BirdNET-Geomodel`。
- `PERCH_DEVICE`：默认 `CPU`。
- `BIRDNET_ENABLE_GEO_FILTER`：默认 `true`。
- `PRECISE_RECOGNITION_TEMP_DIR`：可选临时目录。

上传与分窗临时文件默认保存在系统临时目录，并在每次请求结束后删除。不要提交模型、上传音频或临时文件。

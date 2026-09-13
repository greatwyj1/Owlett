# 在电脑上配置后端精准识别

> 1.0.6增加独立“内置模型ZIP”：使用该包时请直接按包内README操作，不执行下面源码版的模型下载步骤。它已含BirdNET 2.4、Perch v2 CPU与GeoModel，但Python及依赖仍需安装。下面内容保留给完整源码用户。

[简体中文](DEPLOYMENT.md) | [English](DEPLOYMENT.en.md)

当手机本地模型未能识别某段鸟鸣时，可以把选定片段交给电脑上的 Perch v2 或 BirdNET 2.4 再分析。服务运行在你自己的电脑上；本项目不提供公共识别服务器。

返回 [安装与配置 API](../../README.md#安装与配置-api)。后端独立 Release 源码包尚未发布，下载入口预留在 README。以下步骤也适用于当前完整源码中的 `server/precise_recognition/`。

## 1. 准备电脑与环境

- 安装 Python 3.11。可从 [Python 官方下载页](https://www.python.org/downloads/) 获取。
- 电脑需要联网下载依赖和模型，并预留足够磁盘空间；首次模型准备可能较慢。
- 不要求独立显卡，以下流程使用 CPU。
- 手机与电脑连接同一可信局域网。使用时电脑须保持开机，不进入休眠。

以下终端命令面向 macOS / Linux。仓库的 `requirements.lock.txt` 按 Python 3.11 / macOS arm64 环境锁定；Linux 使用 `requirements.txt` 安装平台对应依赖。Windows 原生环境和 WSL 的依赖、网络连通性未在本轮验收，不能直接视为已支持的一键安装流程。

解压后打开终端，进入解压目录中的 `server/precise_recognition`。从这里开始执行后续命令：

```bash
python3.11 -m venv .venv
source .venv/bin/activate
```

macOS Apple Silicon：

```bash
python -m pip install -r requirements.lock.txt
python -m pip check
```

Linux 或其他未锁定平台：

```bash
python -m pip install -r requirements.txt
python -m pip check
```

如果 `soundfile` 提示找不到 `libsndfile`，需先通过系统的软件包管理器安装该库，再重试。安装失败时先处理错误，不继续启动服务。

## 2. 准备并检查模型

在同一个终端、同一个目录中执行：

```bash
python ../../tools/release/prepare_backend_models.py
```

脚本准备并校验固定版本的 Perch CPU、BirdNET 2.4 和 GeoModel 资源；它会访问上游模型站点，不上传你的录音。需保留代码包中的 `tools/release/prepare_backend_models.py` 和 `resources/backend-models.json`，不要只复制后端的 `app/` 文件夹。

模型分别遵守其上游许可。下载提示登录或需要接受模型条款时，按提示在对应上游平台完成；下载失败、校验不匹配都不等于模型已可用。

检查你准备使用的模型：

```bash
# 使用不同于手机本地 BirdNET 的模型复核
python scripts/smoke_test.py --model perch_v2

# 如果也希望在 App 中选择 BirdNET 2.4
python scripts/smoke_test.py --model birdnet_v2_4
```

脚本使用合成静音检查模型加载、令牌校验和完整音频请求。看到 `"status": "passed"` 才表示该项检查通过；这不是对真实鸟鸣准确率的验收。

模型准备脚本会准备整套清单。若只想使用部分模型或安排离线部署，需要按模型清单和后端源码自行配置，不能把缺失资源的检查错误忽略掉。

## 3. 设置访问令牌并启动

访问令牌是你为这台电脑的识别服务设置的密码，不需要向第三方申请 API Key。

先在电脑网络设置中找到当前 Wi-Fi / 有线连接的局域网 IPv4 地址，例如 `192.168.1.20`。以下命令中的地址必须替换成实际地址。

在刚才已激活 Python 环境的终端运行：

```bash
# 生成自己的令牌，并显示一次；复制到手机设置中妥善保存
export API_TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
python -c 'import os; print(os.environ["API_TOKEN"])'

export ACOUSTIC_MODEL=perch_v2
export BIRDNET_ENABLE_GEO_FILTER=false
uvicorn app.main:app --host 192.168.1.20 --port 8000
```

如只检查了 BirdNET 2.4，改用 `export ACOUSTIC_MODEL=birdnet_v2_4`。App 请求中选择的声学模型也必须已准备好。

终端保持打开。若电脑询问防火墙权限，仅允许可信局域网中的手机访问；不要为此关闭整个防火墙。这里的 HTTP 仅用于可信局域网，音频和令牌没有传输加密；跨公网使用需另行配置 HTTPS、访问控制和限流。

`.env.example` 是配置参考，程序不会自动读取 `.env` 文件。关闭终端后，上述环境变量不会自动恢复。

## 4. 在手机中连接

1. 确认手机与电脑连接同一网络，避免开启设备隔离的访客 Wi-Fi。
2. 在手机浏览器访问 `http://192.168.1.20:8000/health`（替换 IP）。能看到 JSON 表示网络可达。
3. 检查响应的 `loaded_acoustic_models` 和 `warnings`。只有 `status=ok` 不代表模型已加载；所需模型还应通过上一节的检查。
4. 打开 **Owlett → 设置 → 精准识别**，填写：

| 字段 | 内容 |
| --- | --- |
| 服务地址 | `http://192.168.1.20:8000/api/v1/precise-recognition`，使用电脑实际 IP，保留完整路径。 |
| 访问令牌 | 电脑终端生成的 `API_TOKEN`。 |
| 声学模型 | 已检查通过的 Perch v2 或 BirdNET 2.4。 |

该设置页修改会自动保存。手机上的 `127.0.0.1` 指手机本身，不能用它访问电脑；Android 模拟器的专用地址也不适用于真实手机。

## 5. 提交一次识别

1. 暂停录音，或打开一个已保存的录音行程。
2. 在频谱中选取不超过 **15 秒** 的音频，点击“精准识别”。
3. 核对发送目标并提交，等待电脑完成分析。首次加载模型可能更慢。
4. 查看候选鸟种、置信度和警告，并与手机本地识别及实际观察比较。

时间选区提交对应音频；频谱选区提交带通滤波后的音频。请求不包含整段行程，启用定位时可附带坐标。服务默认限制上传文件不超过 20 MB，并在请求结束后删除临时音频。

## 6. 下次使用与可选定位筛选

用 `Ctrl+C` 停止服务。下次使用时重新进入 `server/precise_recognition`，激活环境，并设置**与手机保存值一致**的令牌和配置后启动：

```bash
source .venv/bin/activate
export API_TOKEN='替换为上次保存的令牌'
export ACOUSTIC_MODEL=perch_v2
export BIRDNET_ENABLE_GEO_FILTER=false
uvicorn app.main:app --host 192.168.1.20 --port 8000
```

如果重新生成令牌，手机也需同步修改；电脑 IP 变化时，启动地址和手机服务地址都要更新。

以上先关闭地理筛选以核对声学识别。需要基于坐标和季节筛选候选鸟种时，先执行：

```bash
python scripts/smoke_test.py --model perch_v2 --geo
```

检查通过后，把 `BIRDNET_ENABLE_GEO_FILTER` 设为 `true` 并重启服务，在 App 中开启定位辅助识别并授予定位权限。具体变量见 [后端技术说明](README.md)。

## 常见问题

| 现象 | 检查方式 |
| --- | --- |
| 手机打不开 `/health` | 确认服务运行、IP 和端口正确、设备处于同一网络、防火墙允许访问；检查路由器设备隔离。 |
| `401` | 手机令牌与电脑 `API_TOKEN` 不一致。 |
| `400` | 检查片段是否超过 15 秒、文件是否过大或无法读取。 |
| `422` | 请求参数格式不匹配，核对客户端与后端版本。 |
| 服务响应正常但没有结果 | 查看 `warnings` 和模型检查输出，区分模型加载失败与实际未命中。 |
| 重启后连接失败 | 重新激活环境、设置令牌，核对电脑 IP 是否变化。 |

本轮只核对部署文档、源码和语法，没有执行依赖安装、模型下载或真实手机到电脑的识别请求。详细验证范围见 [更新记录](../../CHANGELOG.md)。

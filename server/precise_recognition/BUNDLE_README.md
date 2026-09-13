# Owlett 精准识别后端 · 内置模型包

[简体中文](BUNDLE_README.md) | [English](BUNDLE_README.en.md)

本包包括后端代码、BirdNET 2.4声学/先验模型、Perch v2 CPU模型、GeoModel v3模型、标签和校验清单。无需再下载这些模型。当前不提供公共服务器，用户需自行运行本服务。

**不是免安装软件或通用离线系统镜像。** 仍需Python 3.11及相应运行库；首次安装Python依赖需要联网。本包不含Windows/Linux/macOS各平台的Python安装程序、驱动或依赖安装包。CPU版不需要GPU。

## 安装与启动

解压后在本目录打开终端：

```bash
python3.11 -m venv .venv
```

macOS/Linux激活：`source .venv/bin/activate`。Windows PowerShell激活：`.venv\Scripts\Activate.ps1`；Windows创建环境可用 `py -3.11 -m venv .venv`。

```bash
python -m pip install -r requirements.txt
python scripts/verify_bundle.py
python scripts/smoke_test.py --model birdnet_v2_4 --geo --offline
python scripts/smoke_test.py --model perch_v2 --geo --offline
python scripts/start_bundle.py
```

启动前由脚本私下询问访问Token，输入内容不会显示。请自行设置长随机Token并记录在密码管理器，然后在App设置中填写相同值。程序默认监听本机127.0.0.1。手机在可信局域网访问时使用 `python scripts/start_bundle.py --host 0.0.0.0`，并设置防火墙仅允许所需设备；此地址不是手机填写的地址。

手机填写：`http://电脑局域网IP:8000/api/v1/precise-recognition`。跨公网访问必须配置HTTPS、访问控制及限流，不能直接暴露这个开发服务。重启后重新激活环境、运行启动命令并输入同一Token。

## 资源与限制

- 默认读取与app目录并列的models目录；移动时请移动整个包。文件缺失会报错，不静默补下载。
- Python运行库来自固定版本依赖，跨平台安装仍可能需要平台兼容调整。验证平台与未完成项以交付检查报告为准。
- 模型只用于本地推理，GeoModel是候选过滤先验，不是目击概率。静音测试验证加载及接口，不证明真实鸟鸣识别准确度。
- 自研代码适用LICENSE；第三方模型适用各自许可证，见THIRD_PARTY_NOTICES.md与models/manifest.json。GeoModel转换版本再分发许可未核清时本包仍是本地候选，不能视作已批准公开的资源包。
- 包不含API Key、私人录音、上传文件、账号Cookie或聊天记录。SHA256.json记录各文件校验值。

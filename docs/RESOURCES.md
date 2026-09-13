# 模型与可选资料

[简体中文](RESOURCES.md) | [English](RESOURCES.en.md)

源码发布与资源分发分开管理。资源清单只用于核对来源、版本和哈希，不等于所有列出的文件均可再分发。

| assets 下路径 | 用途 | 缺少时 |
| --- | --- | --- |
| birdnet_model.tflite、labels.txt | 本地声学模型与匹配标签 | 可录音，不能实时识别 |
| BirdNET_GLOBAL_6K_V2.4_MData_Model_*.tflite | 可选先验模型 | 不使用该先验 |
| taxonomy/birdnet_taxonomy.db | 分类与名称资料 | 完整本地图鉴及名称检索不可用 |
| ibirding_cn/bird_species.db、ibirding_cn/assets/ | 可选图鉴文字和图片 | 不显示完整图鉴 |

模型来源：https://github.com/birdnet-team/BirdNET-Analyzer 。模型与标签必须对应，许可与 App 自研代码分开，保留上游署名要求。

图鉴使用者须自行取得合法资源。目前没有公开图鉴包下载地址，私人使用授权不自动允许公开再分发。不要从未经授权的镜像取得所谓完整资源包。

resources/manifest.json 是已有资源版本的校验清单；prepare_resources.py --bundle 只恢复完整匹配包，不会自动下载或采集网页。后端模型见 resources/backend-models.json 与后端部署文档。

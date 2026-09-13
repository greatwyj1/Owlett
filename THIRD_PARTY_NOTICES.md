# 第三方声明

[简体中文](THIRD_PARTY_NOTICES.md) | [English](THIRD_PARTY_NOTICES.en.md)

## lovechat / chatui

- 项目：[chinalwb/lovechat](https://github.com/chinalwb/lovechat)。
- 内置提交：`9f22974ab96eef94632289fa1c880f84b0722b94`。
- 著作权：2026 Wenbin Liu。
- 许可：MIT。

可复用 Compose `chatui` 模块内置于 `chatui/`，本地适配了 Owlett 消息元数据、计划附件标签、技能附加信息、重试操作、脱敏可展开错误详情、中文文案、紧凑消息样式和嵌入式窗口边距处理。上游 MIT 许可证全文保留在 `chatui/LICENSE`。

## openai-kotlin

- 项目：[aallam/openai-kotlin](https://github.com/aallam/openai-kotlin)。
- 版本：4.1.0。
- 著作权：2021 Mouaad Aallam。
- 许可：MIT。

Owlett 使用该依赖作为 DeepSeek 的 OpenAI 兼容流式客户端。仓库不内置其源码；许可保留在 `licenses/openai-kotlin-MIT.md`。

应用通过 SDK 的 HTTP 配置添加本地 OkHttp 拦截器，提供 DeepSeek 思考模式扩展并规范化非 JSON HTTP 错误响应。流式传输和工具调用解码仍由 openai-kotlin 处理；没有替换大模型框架，也没有复制供应商传输实现。兼容性参考：[DeepSeek 集成说明](https://api-docs.deepseek.com/quick_start/agent_integrations/oh_my_pi/)。

## AndroidX Media3

- 项目：[androidx/media](https://github.com/androidx/media)。
- 版本：1.5.1。
- 许可：Apache License 2.0。

Owlett 使用 Media3 ExoPlayer，每次播放一条用户选择的录音。SimpleCache/CacheDataSource 将已播放字节存入私有 256 MB LRU 缓存，用户可在设置中清空。不进行批量下载或自动播放，保留来源和录音许可。实现参考：[Media3 缓存](https://developer.android.com/media/media3/exoplayer/network-stacks#caching-media)。

## 外部数据服务

- [eBird API](https://documenter.getpostman.com/view/664302/S1ENwy59)。
- [xeno-canto API v3](https://xeno-canto.org/explore/api)。

eBird 提供地区、鸟点、观察和 notable 报告数据。xeno-canto 提供远程播放的野生动物录音和声谱图。录音卡保留其返回的录音者、声音类型、质量、日期、地点、许可及来源链接。用户自行提供 API 密钥。

成功获取的 eBird 参考目录和每日观察缓存七天。离线使用过期目录时会明确标注；不抓取 eBird 媒体。应用鸟种小图使用已有 taxonomy/iBirding 资源，不新增远程图片采集。中文翻译保留学名、模型名和来源署名。

成功的聊天观察查询也保存七天会话快照，并保留来源与采集时间；重复分析可离线复用。xeno-canto 优先使用上下文指定或系统取得的地区，并说明范围扩大。地区筛选使用服务的国家/地点搜索字段；系统位置通过 Android 获取，不根据界面语言推测。

## Owlett 1.0.0 补充

自研 App 与后端采用 PolyForm Noncommercial 1.0.0，见 LICENSE 与 NOTICE。该声明不覆盖第三方代码、模型和媒体。

- Inferno：Nathaniel J. Smith、Stefan van der Walt，BIDS/colormap，CC0；源 https://github.com/BIDS/colormap/blob/master/colormaps.py；本地转为 256 级 ARGB，全文 licenses/CC0-1.0.txt。
- BirdNET 2.4 声学与先验模型：BirdNET 团队；CC BY-NC-SA 4.0。代码与模型许可不可混为一谈。全文 licenses/CC-BY-NC-SA-4.0.txt。
- Perch v2 CPU：Google，Kaggle TensorFlow2/perch_v2_cpu/1，Apache 2.0。GeoModel 固定 Hugging Face 转换仓库修订 892c1958a00d53d5073217ca4cbfb5c32499d4c7；仍需核对转换资源自身授权。来源、版本与 SHA-256 见 resources/backend-models.json。
- TensorFlow Lite 2.16.1、AndroidX/Compose、Kotlin/kotlinx、Ktor、OkHttp/Okio、Gson：Apache 2.0；原生库内含的其他组件仍保留其自身许可，公开前需完成原生依赖许可复核。标准全文 licenses/Apache-2.0.txt。
- SLF4J 2.0.16：QOS.ch Sarl，MIT；全文 licenses/slf4j-MIT.txt。
- 图鉴：iBirding / 中国鸟类野外手册及对应作者；分类、名称与图片也来自 BirdNET taxonomy 和中文鸟名资料。维护者说明已获授权，当前未取得可核验的授权范围材料，不能据此认定允许把全部图片放入公开 GitHub。授权凭据不纳入发布包。

应用内“设置 → 关于 → 第三方许可与数据来源”显示作者、版本、来源和现有许可全文，构建时附带全部运行时依赖版本清单。图片详情与鸣声卡继续显示逐项来源。后端依赖通过固定版本安装，第三方模型不归本项目重新授权。
资源准备脚本仅恢复指定、经过校验且使用者有权取得的资源，不自动抓取未经许可的图鉴网页。
## 开发期界面截图工具

- Paparazzi 1.3.5，Square / Cash App，Apache-2.0：[项目与许可](https://github.com/cashapp/paparazzi/tree/1.3.5)。仅在 `-PvisualChecks=true` 的JVM界面检查中使用，不进入APK；当前Android/Kotlin/Compose版本保持不变。
- 新Owlett底栏线稿为项目自制矢量图；其它底栏图标来自已列明的Material Icons。预览中的图版沿用本地图鉴授权，不因用于测试而获得额外公开许可。

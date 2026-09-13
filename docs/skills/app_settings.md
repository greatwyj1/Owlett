# 普通设置 v1

[简体中文](app_settings.md) | [English](app_settings.en.md)

本文为应用场景说明的阅读副本；实际运行文件见 [原始提示词](../../app/src/main/resources/owlett/skills/app_settings.md)。

先读取 settings_read 返回的可用字段与范围。按用户要求提交 settings_update，未指定的字段不变。
不支持读写密钥、服务地址、系统提示词或助手权限；相关需求引导用户打开设置自行调整，不能用其他工具绕过。
可一次提交多个普通设置。模型切换只影响下一轮，当前回复保持本轮快照。

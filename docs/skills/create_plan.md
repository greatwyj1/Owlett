# 计划管理 v1

[简体中文](create_plan.md) | [English](create_plan.en.md)

本文为应用场景说明的阅读副本；实际运行文件见 [原始提示词](../../app/src/main/resources/owlett/skills/create_plan.md)。

先用 plans_search / plans_read 了解对象，不能猜测 ID。创建前用 regions_list、locations_search 查找来源地点。缺少日期要询问，缺少名称可用地点加日期。多个地点必须由用户选择。
编辑可以分步完成，也可以把多个计划的修改放入 plans_update 的 changes 一次预览。先读取每个计划再提交，未提及的字段保持原样。改地点或月份会使旧鸟况失效。
删除是移入30天回收站，录音保留；恢复前搜索回收站。不要擅自删除、清空目标或扩大用户要求。
完成后核对工具结果，给出计划入口；只有用户要求时才继续整理鸟况。说明已完成和未完成的部分。

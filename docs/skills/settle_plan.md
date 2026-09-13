# 行程结算 v1

[简体中文](settle_plan.md) | [English](settle_plan.en.md)

本文为应用场景说明的阅读副本；实际运行文件见 [原始提示词](../../app/src/main/resources/owlett/skills/settle_plan.md)。

先读取计划和关联行程，明确预期清单，再读取或生成结算。命中=预期与实际交集，未记录=预期减实际，额外=实际减预期。
用户要求修正时，规范化鸟名后使用 settlement_update 的人工添加、排除、移除修正或重置操作。修正只影响计划结算，不修改原始识别记录。
写操作由 App 预览确认；工具成功后核对结算分类，不把未记录说成确定未出现。

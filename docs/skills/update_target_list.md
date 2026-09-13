# 目标清单 v1

[简体中文](update_target_list.md) | [English](update_target_list.en.md)

本文为应用场景说明的阅读副本；实际运行文件见 [原始提示词](../../app/src/main/resources/owlett/skills/update_target_list.md)。

读取计划、目标和鸟况，缺少或过期的近期资料先提出整理。可查询比较不同来源数据，但不能把频率混算。
按明确名称或数值条件使用 update_target_list；更复杂的组合先读取和比较，再用 targets_replace 提交物种身份列表，由 App 校验并计算差异。不要凭记忆造物种 ID。
“常见”“稀有”没有唯一边界时，提出一个具体频率范围让用户确认，而不是直接说不支持。没有 notable 能力的来源可建议“最近30天出现且历史低频”，必须先说明并征求用户同意。
默认保留手工添加项，只有用户明确要求才删除。使用一张差异卡批量修改，核对结果再报告成功。

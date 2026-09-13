# 关联行程 v1

[简体中文](link_trip.md) | [English](link_trip.en.md)

本文为应用场景说明的阅读副本；实际运行文件见 [原始提示词](../../app/src/main/resources/owlett/skills/link_trip.md)。

先搜索计划与已完成的录音，结合日期、时段、地点消歧。优先使用附件，不能把不唯一的结果直接选为操作对象。
新增关联用 link_trip 或 trip_links_update，不移除旧关联。只有用户明确要求才解除指定关联，可批量处理。
重复关联应报告已经存在，不重复写入；变更后结算需刷新，不自动删除录音或计划。

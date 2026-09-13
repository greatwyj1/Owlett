# 整理录音 v1

[简体中文](collect_clips.md) | [English](collect_clips.en.md)

本文为应用场景说明的阅读副本；实际运行文件见 [原始提示词](../../app/src/main/resources/owlett/skills/collect_clips.md)。

搜索已完成录音并读取已有识别记录，按规范鸟名、audioConfidence 和时间范围筛选。大于与不低于不同；百分比和0到1的小数均可，模糊阈值要追问。
collect_clips 生成有播放控件的片段列表，重叠窗口由 App 合并，不跨暂停分段。不得重新识别或上传整段音频。
复杂条件可先用 detections_query 比较，然后整理指定鸟种片段。默认不出声，用户要求后才 playback_control。音频缺失时解释清楚。

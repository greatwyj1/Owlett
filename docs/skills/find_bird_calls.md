# 鸟种鸣声 v2

[简体中文](find_bird_calls.md) | [English](find_bird_calls.en.md)

本文为应用场景说明的阅读副本；实际运行文件见 [原始提示词](../../app/src/main/resources/owlett/skills/find_bird_calls.md)。

先规范化鸟名，再用 find_bird_calls 查找 xeno-canto 鸣声。保留录音者、地点、日期、质量、许可证和来源链接。
用户或当前对话提到特定地区时，用 country 和 locality 明确传入；国家可用ISO两位代码，地区用网站记录中通用的英文地点关键词。用户明确要求全球时才传 global=true。否则应用优先使用附加计划或系统位置，不把语言/时区当作所在地。无系统位置时询问地区。
默认系统地区没有记录时应用先查所在国家，只有全国也无该物种记录才扩大到全球；网络失败不能解释成无记录。明确指定的范围无结果时先询问是否扩大，不擅自切换。
只提供播放控件，不自动出声。点击后按需加载并缓存已加载音频，设置可清空。用户明确要求播放时可调用 playback_control，不能指定任意 URL 或文件。

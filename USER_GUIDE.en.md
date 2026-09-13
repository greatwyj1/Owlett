# Owlett User Guide

[简体中文](USER_GUIDE.md) | [English](USER_GUIDE.en.md)

Owlett helps you record bird sounds, replay identification clips, prepare birding plans, and discuss birds with an AI assistant. Real-time recognition runs on the phone; online features require your own service configuration. Both identification and AI advice can be wrong, so check them against field observations.

## 1. Installation

The official app is named Owlett, with application ID `io.github.greatwyj1.owlett`. Install the official APK when available; allow the file manager to install apps if Android asks. See [installation and API configuration](README.en.md#installation-and-api-configuration) for current download availability.

The recording, plan, and assistant pages show short introductory tutorials on first use. You can skip them and revisit them in Settings → Help (设置 → 使用帮助). Opening a page does not immediately request microphone access; starting a recording does.

## 2. Migrating from the old test app

The old and official apps are separate applications and can coexist. Do not uninstall the old app.

1. Update the old test app using its migration APK. First verify that the installed app and migration APK use the same signature. Stop if there is a signature mismatch; uninstalling is not the solution.
2. In the old app, stop recording and wait for recognition, assistant tasks, and bird-activity analysis to finish. Open Settings → Storage → Export data (设置 → 存储管理 → 导出数据) and choose a local destination.
3. Install the official app. Before creating plans, recordings, or chats, open the same settings location, tap Import data (导入数据), and select the ZIP.
4. Wait for verification and tap Reopen app (重新打开应用). Check plans, links, recordings, and chats, then re-enter service keys.
5. Keep the old app and backup until migration is verified. The backup has no password and includes private audio, locations, and chats; do not upload it publicly.

Migration only supports an empty destination app, not merging two existing datasets. Reserve at least twice the uncompressed data size plus 64 MB of free space. Temporary files, models, field guides, network caches, and service keys are excluded. Unfinished or pending skills need to be retried; importing will not execute them automatically.

## 3. Recording and review

Tap Start on Recording (录音). Real-time detections are saved with the local recording trip. Pause and resume as needed; after finishing, find it under Plans and Trips → Trips (计划与行程 → 行程).

After pausing, the spectrogram defaults to Pan (滑动): drag to browse the timeline. Switch to Time selection (时间选区) or Spectrogram selection (频谱选区) at the top right. Tap to set the red playback start; drag a time window to play that selection. A frequency selection applies a bandpass filter, retaining sound near the chosen frequencies.

The review and recording pages use the same controls. Removing audio can preserve detection records. Deleting a whole trip also removes its links to plans.

## 4. Precise recognition: self-hosting required

**No public recognition backend is provided. Configure your own service to use this feature.** See the [deployment guide](server/precise_recognition/DEPLOYMENT.en.md). You can keep using local recognition without a server.

In Settings → Precise recognition (设置 → 精准识别), enter your server URL and token and choose BirdNET 2.4 or Perch v2. Submission shows the destination and sends selected audio, timing, and recognition parameters; coordinates are included when location is enabled. A physical phone must use the computer's LAN address, not the phone's `127.0.0.1`. Use HTTPS for remote access. A frequency selection sends filtered audio, not the whole trip.

## 5. Reading observation results

Results show each report's observation time or interval, species count, and Original report (原报告) link under the full hotspot name. Use More records (更多记录), pagination, or the assistant's cached results for longer lists. Times refer to report intervals, not necessarily the exact moment a bird appeared. Unknown counts show Not specified (未注明).

Reports may concern the same individuals, so their counts cannot simply be added into a total. These queries tell you where and when records exist; filtered reports cannot establish local occurrence frequency or rarity.

## 6. Owlett assistant

Save your key in Settings → DeepSeek, test connectivity, and select a model. The test only retrieves the model list, without generating an answer. Messages, recent conversation, and selected attachment summaries go to DeepSeek. Do not send passwords or keys as messages.

Create and rename conversations, stop responses, or retry. The plus button attaches at most one plan or completed trip per message. Type `/` to select a skill, or ask directly:

- `/plan`: create plans; `/activity`: organize observations; `/targets`: change targets.
- `/bird`: species information; `/calls`: find and play xeno-canto recordings.
- `/link`: associate a completed recording trip with a plan, e.g. “Link the September 6 morning recording to that day's Peking University plan.” Ambiguous matches require a choice.
- `/clips`: organize clips from saved detections, e.g. “Find Common Cuckoo clips in this trip with confidence greater than 30%.” This uses audio confidence only, without re-identification or audio upload.

“Greater than 30%” excludes exactly 30%; “at least 30%” includes it. Avoid ambiguous thresholds such as “confidence greater than 3.” Clip lists support play, pause, seeking, previous/next, continuous playback, and opening the original spectrogram. They do not autoplay; starting recording stops playback.

Settings → Assistant behavior (设置 → 助手行为) lets you edit the initial prompt, up to 10,000 characters, save it, or restore the default. Automatic skills (自动使用技能) controls model-initiated skill selection. Operation confirmation (操作确认方式) controls whether writes ask for approval. These are independent.

Confirmation is the default. Fully automatic mode (全部自动) only enables existing write skills and still asks for missing information. It does not allow trip deletion, arbitrary file access, or key changes. Settings apply to new requests; retries use the original prompt and confirmation snapshot. Old pending cards do not execute automatically.

## 7. Calls and sharing

Call lookup is optional and needs your API v3 key in Settings → xeno-canto. By default, searches start in the region derived from system location, then the country. They expand globally only if the country has no recordings of the species, with the reason shown. A region in the conversation or attached plan takes priority. If an explicitly requested region has no results, the assistant asks before expanding. Without a location, specify a country/region or enable location in both Android and Owlett settings.

Playback shows loading progress and caches loaded portions on demand. A fully played recording can be replayed from cache; unbuffered portions still require internet. The cache is limited to 256 MB and evicts older content. Settings → xeno-canto → Clear bird-call audio cache (清空鸟鸣音频缓存) clears it and stops current playback without deleting your recording trips.

## 8. Troubleshooting

| Problem | What to do |
| --- | --- |
| Invalid key, 401/403 | Check that the key belongs to this service; do not use an eBird key for DeepSeek. |
| 429 / too many requests | Wait and check quotas. Repeated taps can trigger more rate limiting. |
| Timeout | Check network, URL, port, and firewall. Physical phones cannot use emulator-specific addresses. |
| Hotspot not found | Try another region or shorter keywords. This is different from a network failure. |
| No reports / insufficient coverage | The date or location may lack data. Old analysis is retained; failure does not mean “no birds.” |
| Model not loaded | Verify matching resources. Prepare resources before building from source. |
| Missing audio | Original recordings may have been cleared. A chat clip reference is not an audio backup. |
| Cannot record | Allow microphone access and check whether another app is using it. |
| Installation signature mismatch | Keep the old app. Obtain the correctly signed update before migration. |
| Insufficient import space | Free at least twice the uncompressed size; also allow the extra 64 MB described above. |
| Interrupted import | Restart to roll back incomplete import, check the empty state, and select the original package again. |
| Inaccurate AI answer | Check cited dates and tool results. Specify the species, date, and threshold clearly. |

## 9. Data and licenses

Recordings, plans, and chats are stored privately on the phone. Migration excludes configured service keys. Secrets you type in chat are not settings credentials and are not automatically detected or removed. Copies received by external apps are subject to those apps' retention, even if you delete the local original. See the [privacy notice](PRIVACY.en.md).

Original code uses PolyForm Noncommercial 1.0.0 for noncommercial modification and distribution with attribution. Components, models, images, and recordings retain their own licenses. See Settings → About → Third-party licenses and data sources (设置 → 关于 → 第三方许可与数据来源).

## Assistant scenes and the recycle bin

Type `/` to select scenes, including `/settle` for trip review and `/settings` for ordinary settings. The assistant can combine steps, such as moving two plans to next week and linking today's recording. Ambiguous objects require a choice.

Writes preview by default. Automatic execution does not grant access to keys, service URLs, or recording deletion. If a sequence fails midway, completed changes remain; check each result. Retry receipts prevent duplicate operations. With automatic skills disabled, manually selected scenes only expose their relevant tools.

Open the recycle bin at the top right of the plan list. Deleted plans and links can be restored within 30 days. Expiry removes plans without deleting recordings. Interrupted work after an upgrade or process stop requires retry and does not resume writes by itself.

## Controls added in 1.0.1

- **Colors:** Settings → Appearance → Color theme (设置 → 外观 → 主题配色): Fresh Green (清爽绿), Feather Taupe (羽色·灰褐), or Feather Gold (羽色·暖金). Light/dark mode remains System, Light, or Dark.
- **Share selection:** Pause or review audio, select a time or frequency region, and tap the share icon to the right of precise recognition. It is disabled without valid audio/selection or during filtering/export. Long-press sharing remains available.
- **Quick precise recognition:** Available in More recording actions (更多录音操作) at the bottom.
- **Bird thumbnails:** Local field-guide images appear beside detections, targets, and review items. Tapping opens details without changing checkboxes. Missing images use a placeholder.
- **Check version:** Settings → About Owlett (设置 → 关于 Owlett) shows version and code. Changelog (更新记录) includes changes and pending checks. Same-signature internal updates can install over the old app; do not uninstall first.

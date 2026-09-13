# Owlett Privacy Notice

[简体中文](PRIVACY.md) | [English](PRIVACY.en.md)

Policy updated: 2026-09-11; originally applicable to 1.0.3. Subsequent documentation translations do not change data handling.

## Local data

Recordings, plans, identification results, chats, and skill cards are stored in the app's private storage. Real-time BirdNET recognition runs on the phone. No app account is created, and whole recording trips are not automatically uploaded. The source author does not provide a public precise-recognition backend. Android automatic backup is disabled so recordings, chats, and credentials are not copied to an unselected cloud backup.

## Network data

- **Precise recognition:** User-submitted audio selections, times, and model parameters go to the configured backend, with coordinates when location is enabled. HTTPS is preferred.
- **DeepSeek:** The current message, recent complete conversation, and plan/trip attachment summaries are sent. Tool output is summarized as needed, excluding local paths, audio files, and service credentials. The provider may retain content under its policy.
- **eBird:** Region, hotspot, observation date, query conditions, and your key are sent. Reference directories and historical reports used in plan analysis are cached for seven days. Chat queries have separate seven-day conversation snapshots.
- **xeno-canto:** Species, country/region filters, and your key are sent. Audio loads when you tap Play and is stored in a private cache of up to 256 MB. Settings can clear it. No automatic playback or export occurs. Original recording licenses still apply to cached audio.
- **Call-search region:** When enabled and permitted, the app reads recent system location and may request a current fix. System geocoding may receive coordinates to determine country/region. If geocoding fails, the current mobile-network country may be used; SIM origin and UI language are not used as location guesses. Coordinates are not sent to xeno-canto or DeepSeek for call searches; the tool returns only the region used.
- **External bird images:** Details may load images from their source sites, exposing the requesting IP. Each image retains its own license.
- **Sharing:** WAV/MP4 files are generated locally. You choose the receiving app in the system share sheet. Media can contain dates, locations, or private sounds.

## Settings and permissions

DeepSeek and xeno-canto keys are stored with Android Keystore AES-GCM encryption. eBird keys and backend tokens currently reside in private app settings and are excluded from migration and system backup; use a trusted device.

Changing the prompt does not expand tool permissions. Fully automatic mode allows only existing plan management, activity analysis, target changes, trip linking, review corrections, and ordinary settings operations. It does not permit recording deletion, arbitrary file access, or service-key changes.

## Migration and deletion

Migration ZIP files are unencrypted and include private recordings and chats. Do not publish them on GitHub, in public groups, or with APKs. Configured credentials are excluded, but sensitive text entered manually in chat is not automatically removed. Do not type keys into chat.

Deleting a trip does not remove shared copies from other apps. Deleting a conversation removes its local messages, skill cards, and query snapshots, but not shared public-data caches. Editing or deleting a plan does not rewrite historical attachment snapshots.

Audio caches and query snapshots are excluded from system backup and migration. Android may clear caches when storage is low. Query snapshots retain their source, conditions, and capture time for up to seven days, without keys or raw web pages. Migration files are saved through the system file picker to your chosen location and survive app uninstallation; manage them yourself.

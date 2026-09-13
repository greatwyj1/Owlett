# Third-Party Notices

[简体中文](THIRD_PARTY_NOTICES.md) | [English](THIRD_PARTY_NOTICES.en.md)

## lovechat / chatui

- Project: https://github.com/chinalwb/lovechat
- Vendored revision: `9f22974ab96eef94632289fa1c880f84b0722b94`
- Copyright: 2026 Wenbin Liu
- License: MIT

The reusable Compose `chatui` module is vendored under `chatui/` and locally adapted for Owlett message metadata, Plan attachment labels, skill extras, retry actions, sanitized expandable error details, Chinese strings, compact message styling, and embedded window-inset handling. The complete upstream MIT license is preserved at `chatui/LICENSE`.

## openai-kotlin

- Project: https://github.com/aallam/openai-kotlin
- Version: 4.1.0
- Copyright: 2021 Mouaad Aallam
- License: MIT

Owlett uses this dependency as the OpenAI-compatible streaming client for DeepSeek. Its source is not vendored into this repository. The license is preserved at `licenses/openai-kotlin-MIT.md`.

The app adds a local OkHttp interceptor through the SDK's HTTP configuration to supply DeepSeek's thinking-mode extension and normalize non-JSON HTTP error responses. Streaming and tool-call decoding still use openai-kotlin; no replacement LLM framework or copied vendor transport is introduced. Compatibility reference: https://api-docs.deepseek.com/quick_start/agent_integrations/oh_my_pi/

## AndroidX Media3

- Project: https://github.com/androidx/media
- Version: 1.5.1
- License: Apache License 2.0

Owlett uses Media3 ExoPlayer to play one user-selected recording at a time. Its SimpleCache/CacheDataSource stores played audio bytes in a private 256 MB LRU cache; users can clear it in Settings. No bulk download or automatic playback occurs. Source attribution and recording licenses are retained. Implementation reference: https://developer.android.com/media/media3/exoplayer/network-stacks#caching-media

## External Data Services

- eBird API: https://documenter.getpostman.com/view/664302/S1ENwy59
- xeno-canto API v3: https://xeno-canto.org/explore/api

eBird supplies region, hotspot, observation, and notable-report data. xeno-canto supplies remotely streamed wildlife recordings and sonograms. Recording cards retain the recordist, sound type, quality, date, location, license, and source link returned by xeno-canto. Users provide their own API keys.

Successful eBird reference directories and daily observations are cached for seven days. Expired reference data is explicitly marked when used offline; eBird media is not scraped. Bird thumbnails in the redesigned app use the existing bundled taxonomy/iBirding assets; no new remote photo collection is introduced. Chinese translations preserve scientific names, model names, and source attribution.

Successful chat observation queries also have conversation-scoped snapshots for seven days, with their source and capture time. Repeated analysis can reuse these without network access. xeno-canto searches prefer a context-specified or system-derived region, and report any expansion of scope. Region filters use the service's country/location search fields; system location is resolved through Android, not inferred from the UI language.
## Owlett 1.0.0 additions

Original app and backend code uses PolyForm Noncommercial 1.0.0; see LICENSE and NOTICE. This does not cover third-party code, models, or media.

- **Inferno:** Nathaniel J. Smith and Stefan van der Walt, BIDS/colormap, CC0. [Source](https://github.com/BIDS/colormap/blob/master/colormaps.py). Converted locally to 256-level ARGB; full text in `licenses/CC0-1.0.txt`.
- **BirdNET 2.4 acoustic and prior models:** BirdNET team, CC BY-NC-SA 4.0. Code and model licenses are distinct; full text in `licenses/CC-BY-NC-SA-4.0.txt`.
- **Perch v2 CPU:** Google, Kaggle TensorFlow2/perch_v2_cpu/1, Apache 2.0. GeoModel pins Hugging Face conversion revision `892c1958a00d53d5073217ca4cbfb5c32499d4c7`; rights for converted resources still require review. Sources, versions, and SHA-256 are in `resources/backend-models.json`.
- **TensorFlow Lite 2.16.1, AndroidX/Compose, Kotlin/kotlinx, Ktor, OkHttp/Okio, Gson:** Apache 2.0. Other components within native libraries retain their own licenses; native dependency licensing must be reviewed before publication. Standard text: `licenses/Apache-2.0.txt`.
- **SLF4J 2.0.16:** QOS.ch Sarl, MIT; text in `licenses/slf4j-MIT.txt`.
- **Field guide:** iBirding / A Field Guide to the Birds of China and their authors. Taxonomy, names, and images also draw on BirdNET taxonomy and Chinese bird-name resources. The maintainer reports permission, but verifiable documentation of its scope has not been obtained; this does not establish permission to publish all images on GitHub. Permission records are excluded from release packages.

Settings → About → Third-party licenses and data sources displays authors, versions, sources, and available license texts. Builds include a list of runtime dependency versions. Image details and recording cards retain individual attribution. Backend dependencies have fixed versions; this project does not relicense third-party models.

Resource preparation restores only specified, verified resources the user may lawfully obtain; it does not scrape unauthorized field-guide websites.

## Development screenshot tools

- **Paparazzi 1.3.5:** Square / Cash App, Apache-2.0; [project and license](https://github.com/cashapp/paparazzi/tree/1.3.5). Used only for JVM visual checks with `-PvisualChecks=true`, not included in APKs. Android/Kotlin/Compose versions remain unchanged.
- The Owlett bottom-bar outline icon is an original project vector. Other bottom-bar icons are from the listed Material Icons. Preview plates use existing local guide permissions; testing does not grant additional publication rights.

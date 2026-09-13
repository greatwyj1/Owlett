# Owlett - an AI agent for birding

[简体中文](README.md) | [English](README.en.md) | [All documentation](docs/INDEX.en.md)

**An Android AI assistant connecting birding plans, field recordings, and post-trip review.**

Owlett helps you review bird activity and set targets before heading out, record and identify bird sounds locally in the field, and replay clips, link trips, and organize observations afterward. Use the recording and planning screens directly, or ask the assistant to work with the app's tools in natural language.

This project provides an Android client and an optional precise-recognition backend. It supports **Android 8.0 and later**. The app interface is primarily Chinese; Chinese labels below help you find the corresponding controls. See [version.properties](version.properties) for the version and the [changelog](CHANGELOG.en.md) for changes.

## What you can do

| Situation | Owlett features |
| --- | --- |
| Prepare for a trip | Create birding plans, query eBird activity, and organize target species. |
| Record in the field | Record, pause, and resume; the intended Release APK includes a model for on-device identification and saved results. |
| Listen more closely afterward | Browse the spectrogram, select a time interval or frequency region, replay it, and share the selection. |
| Review a birding outing | Link recording trips to plans, review targets, and filter clips using existing detections. |
| Look up birds and calls | Look up species information; configure xeno-canto to search and play reference calls. A complete local field guide requires additional resources. |
| Recheck a selection | Send selected audio to your own backend running BirdNET 2.4 or Perch v2. |

Recordings, plans, detections, and chats are stored locally. Real-time recognition uses an on-device model. AI chat, online observations, reference recordings, and backend recognition require network access and their respective configuration.

## Work with the assistant

After configuring DeepSeek in Settings, describe what you want:

> Create a birding plan for Saturday morning at Peking University.
>
> Link this morning's recording trip to this plan.
>
> Find Common Cuckoo clips in this trip with audio confidence greater than 30%.

Type `/` to choose a scene:

| Command | Purpose | Command | Purpose |
| --- | --- | --- | --- |
| `/plan` | Manage plans | `/activity` | Organize bird activity |
| `/targets` | Adjust target species | `/bird` | Look up species |
| `/calls` | Find reference calls | `/link` | Link recording trips |
| `/clips` | Organize audio clips | `/settle` | Review trip results |
| `/settings` | Change ordinary settings | | |

Changes show a preview and request confirmation by default. Ambiguous locations or trips require clarification. Clip organization uses saved detections rather than running recognition again. Responses and tool choices depend on the configured model; check identification results against field observations.

See [assistant tools and scenes](docs/OWLETT_TOOLS.en.md) for implementation details and permissions.

## Installation and API configuration

**The following installation instructions are intended for general users.** For development needs, see [Run from source](docs/RUN_FROM_SOURCE.en.md).

### Install Owlett

**Release APK download: not yet published.**
<!-- Insert the verified download URL for a signed release containing the local recognition model when published. -->

Once the official package is available, download the APK on an Android 8.0+ phone, open it, and follow the installation prompts. If asked, allow the browser or file manager you are using to install apps.

**A Release APK with the bundled model lets you use local bird-sound recognition immediately, without an API key.** Open Recording (录音), start recording, and grant microphone access when prompted. This applies to the intended official package; a self-built debug package without a model cannot perform local recognition.

The AI assistant, bird-activity analysis and AI-assisted planning, online reference-call playback, and backend recognition require additional services. Configure only what you need below. Manually creating local plans and replaying your own recordings do not require these APIs.

| Feature | Required configuration |
| --- | --- |
| AI chat and creating or organizing plans through conversation | DeepSeek |
| Online observations and eBird-based plan analysis | eBird; also DeepSeek when using the assistant |
| Asking the assistant to find and play reference calls | DeepSeek + xeno-canto |
| Rechecking an audio clip with another model on your computer | Self-hosted recognition backend; no DeepSeek required |

An API key is your access credential for a service. Enter each key in the corresponding settings page, not in the chat box.

### 1. DeepSeek: AI assistant and conversational planning

**Use case:** Ask about birds in the Owlett assistant, attach a plan or trip, and request plan creation, target changes, or clip organization. For example: “Create a birding plan for Saturday morning.” For suggestions based on online observations, also configure eBird in the next section.

**Get a key:**

1. Register or sign in at the [DeepSeek platform](https://platform.deepseek.com/).
2. Open [API keys](https://platform.deepseek.com/api_keys), create a key, and copy it for safekeeping. Use an API key from the developer platform.
3. Check that your API account has available credit. API usage is billed under the provider's rules; see the [official API guide](https://api-docs.deepseek.com/zh-cn/) and [models and pricing](https://api-docs.deepseek.com/zh-cn/quick_start/pricing).

**Configure Owlett:**

1. Open Settings → DeepSeek (设置 → DeepSeek), paste the key into the DeepSeek API key field, and tap Save (保存).
2. Tap Test (测试), confirm connectivity, and select an available model in Model (模型). The connection test retrieves the model list; it does not generate an AI answer.
3. Return to the assistant and ask a simple question. Enable automatic skills (自动使用技能) if you want the assistant to choose tools, or type `/` to select a scene manually.

If the test fails, check the key and network. If the test succeeds but chat fails, check the account credit, selected model, and error shown.

### 2. eBird: online observations and plan analysis

**Use case:** In Plans and Trips (计划与行程), query observations by region or hotspot, analyze bird activity, and organize targets before an outing. The assistant can also query observations. eBird supplies data; DeepSeek handles conversation and tool use. They require separate keys.

**Get a key:**

1. Open the [eBird API key request page](https://ebird.org/api/keygen) and register or sign in with your Cornell Lab / eBird account.
2. Follow the page instructions and copy your personal key. If the direct page is unavailable, find the API request entry on the [eBird data download page](https://ebird.org/data/download).
3. You need API access, not a download of the full observation dataset. See [eBird's official data guide](https://support.ebird.org/en/support/solutions/articles/48000838205-download-ebird-data) for the request entry and intended use.

**Configure Owlett:**

1. Open Settings → eBird (设置 → eBird), enter the key in eBird key (eBird 密钥), and tap Save (保存).
2. Tap Test connection (测试连接) and check for Connected to eBird (已连接 eBird).
3. Return to Plans and Trips, select a region, hotspot, and date, then query or analyze activity. Configure DeepSeek first if you want the assistant to do this.

An empty result may mean that the location or date lacks observations, rather than a configuration failure. Check the location, date, and source shown with the results.

### 3. xeno-canto: reference bird calls

**Use case:** To hear a species' typical call and compare it with a field sound, type `/calls` or ask the assistant to find Common Blackbird recordings from China. Result cards support playback, pause, seeking, and source links. This service is for online reference recordings; replaying your own audio does not need it.

**Get a key:**

1. Register and sign in at [xeno-canto](https://xeno-canto.org/), completing any account verification requested.
2. Find and copy the API key on your [account page](https://xeno-canto.org/account). Owlett uses API v3. If no key appears, follow the account page and [official API instructions](https://xeno-canto.org/explore/api).

**Configure Owlett:**

1. Open Settings → xeno-canto (设置 → xeno-canto), paste the key, tap Save (保存), then Test (测试).
2. With DeepSeek configured, return to the assistant and specify the species and country or region.
3. Tap Play on a result. Initial playback loads online; loaded portions are cached. Clear this cache in xeno-canto settings if needed.

If there are no results, try another species or region before replacing the key. If the website requires browser verification, use a regular browser to sign in and obtain the key.

### 4. Backend recognition: let another model on your computer listen again

**Use case:** Sometimes the phone's local model cannot identify a bird call. If you run the backend on your computer, Owlett can send the selected audio to that computer for a more precise second assessment with another model. For example, try **Perch v2** when the phone's BirdNET does not identify a sound. The backend also supports **BirdNET 2.4**. This provides another assessment, not a guarantee of higher accuracy every time.

**Backend Release source ZIP download: not yet published.**
<!-- Insert the verified standalone backend ZIP URL when published. Preserve tools/release and resources paths referenced by the deployment guide. -->

No DeepSeek, eBird, or xeno-canto key is needed for this feature. You need a computer running Python 3.11 and a service token you set yourself. The computer must stay on, keep the service running, and be reachable by the phone. You can save recordings in the field and submit them after returning to the same network.

**Prepare the computer:**

1. Download and extract the backend package. Follow the [backend setup guide](server/precise_recognition/DEPLOYMENT.en.md) to create a Python 3.11 environment and install dependencies.
2. Download and verify the models, then run the check for your chosen model. Start with Perch v2 to obtain an assessment from a different model than the phone's; BirdNET 2.4 is also available.
3. Set your own `API_TOKEN` and start the service on the computer's LAN address and port `8000`. Keep the terminal running and connect the phone to the same trusted Wi-Fi. The guide includes commands, platform differences, and restart instructions.

**Configure Owlett:**

Open Settings → Precise recognition (设置 → 精准识别). Changes on this page are saved automatically.

| Field | Value |
| --- | --- |
| Server URL (服务地址) | The full endpoint, e.g. `http://192.168.1.20:8000/api/v1/precise-recognition`; replace the sample IP with your computer's address. |
| Access token (访问令牌) | Exactly the same value as the computer's `API_TOKEN`. |
| Acoustic model (声学模型) | Perch v2 or BirdNET 2.4, whichever passed its check on the computer. |

Pause recording or open a saved trip, select **no more than 15 seconds** in the spectrogram, tap Precise recognition (精准识别), review the destination, and submit. A frequency selection sends bandpass-filtered audio. The full recording trip is not uploaded.

If connection fails, check the computer's IP, running service, and firewall. The phone cannot reach the computer through `127.0.0.1`. For `401`, check the token. Public-internet access requires additional deployment measures such as HTTPS; see the [backend setup guide](server/precise_recognition/DEPLOYMENT.en.md).

## First use

1. Open Recording (录音), start, and grant microphone access. A Release APK with the model also shows real-time detections.
2. Pause and select a time interval or frequency region to replay. After finishing, find the saved trip in Plans and Trips (计划与行程).
3. Create a birding plan, add target species as needed, and link completed recording trips.
4. Once DeepSeek is configured, attach a plan or trip in the assistant and ask it to organize results. Each message supports one attachment.

For detailed operation, sharing, import/export, and troubleshooting, see the [user guide](USER_GUIDE.en.md).

## Development and contributions

See [Run from source](docs/RUN_FROM_SOURCE.en.md) for the environment, project structure, and checks. Read the [contribution guide](CONTRIBUTING.en.md) and [project status](PROJECT_STATUS.en.md).

## Data and licensing

Real-time on-device recognition does not upload recordings. Online features send chat and necessary tool summaries to the configured AI service and query their corresponding data providers. Explicit backend recognition sends the selected audio and parameters to your configured server. See the [privacy notice](PRIVACY.en.md) for storage and data flows.

Original project code uses [PolyForm Noncommercial 1.0.0](LICENSE). Use and distribution must follow its terms, including noncommercial purposes and retention of [NOTICE](NOTICE). Third-party components, models, field-guide images, and reference recordings retain their own licenses; see [third-party notices](THIRD_PARTY_NOTICES.en.md).

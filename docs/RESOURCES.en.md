# Models and Optional Resources

[简体中文](RESOURCES.md) | [English](RESOURCES.en.md)

Source releases and resource distribution are managed separately. A manifest verifies source, version, and hashes; inclusion in the manifest does not establish redistribution permission.

| Path under assets | Purpose | If missing |
| --- | --- | --- |
| `birdnet_model.tflite`, `labels.txt` | Local acoustic model and matching labels | Recording works, but live recognition does not. |
| `BirdNET_GLOBAL_6K_V2.4_MData_Model_*.tflite` | Optional prior model | That prior is not used. |
| `taxonomy/birdnet_taxonomy.db` | Taxonomy and names | Full local field-guide and name search are unavailable. |
| `ibirding_cn/bird_species.db`, `ibirding_cn/assets/` | Optional field-guide text and images | The full guide is not displayed. |

Models come from [BirdNET-Analyzer](https://github.com/birdnet-team/BirdNET-Analyzer). Models and labels must match. Their licenses are separate from original app code; retain upstream attribution.

Obtain field-guide resources lawfully yourself. No public field-guide bundle is available. Permission for private use does not automatically allow public redistribution; do not obtain purported complete bundles from unauthorized mirrors.

[resources/manifest.json](../resources/manifest.json) records an existing resource snapshot. `prepare_resources.py --bundle` restores only a fully matching bundle, without downloading resources or scraping websites. Backend models are described in [resources/backend-models.json](../resources/backend-models.json) and the [deployment guide](../server/precise_recognition/DEPLOYMENT.en.md).

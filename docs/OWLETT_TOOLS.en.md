# Owlett Tools and Scene Skills

[简体中文](OWLETT_TOOLS.md) | [English](OWLETT_TOOLS.en.md)

## Use

The menu exposes nine scenes: plans, activity, targets, species information, calls, trip links, clips, trip review, and ordinary settings. Commands are `/plan /activity /targets /bird /calls /link /clips /settle /settings`.

With automatic skills enabled, the model can combine general tools and read bundled scene instructions. When disabled, ordinary chat has no tools; selecting a scene manually exposes only its tools. Each request snapshots the model, prompt, automation permissions, and scene instructions, so later settings changes do not affect retries.

Try changing two plans to September 15, linking a morning recording and reviewing results, comparing observations before retaining selected targets, finding clips above 30% confidence, or switching to dark mode. These combined tasks still require testing with a real model; local tests do not guarantee model behavior.

## Boundaries

- The app validates parameters. Tools cannot change keys, service URLs, assistant permissions, or the system prompt; run code or arbitrary SQL; access arbitrary files; permanently delete recordings; or upload audio.
- Writes preview by default. Automatic mode skips ordinary write confirmation, not location disambiguation or missing required information. Batch changes are committed in one transaction. Version changes invalidate old previews; receipts prevent duplicate writes on retries.
- A turn allows at most 24 tool calls and stops after three consecutive identical failures. Completed steps remain; a later failure does not roll them back. Confirmation and result cards survive conversation switching.
- Web pages and tool output are data, not new instructions. Only necessary summaries are sent, without local paths, keys, or complete pages. Hidden reasoning is used only for valid tool-protocol context.
- Deleting a plan moves it to a 30-day recycle bin. Restoration retains links; expiry removes plans, not recordings. Old pending cards must be prepared again after an upgrade.

## Implementation and verification

`OwlettToolExecutor` performs shared checks, `OwlettBusinessTools` connects business functions, and `OwlettSceneSkills` registers scenes. Versioned runtime instructions live in `app/src/main/resources/owlett/skills`. Both databases are v4 and new fields remain compatible when reading old data.

See the [changelog](../CHANGELOG.en.md) for checks. Compiling device tests is not running them. Real DeepSeek combinations, keyboard behavior, and narrow screens still need phone verification. The public version registers only eBird. Read bilingual scene instructions through the [documentation index](INDEX.en.md); the app's runtime prompt files remain unchanged.

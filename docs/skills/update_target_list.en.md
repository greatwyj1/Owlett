# Target List v1

[简体中文](update_target_list.md) | [English](update_target_list.en.md)

This is a reading copy of the app scene instructions. The [runtime prompt](../../app/src/main/resources/owlett/skills/update_target_list.md) is unchanged.

Read the plan, targets, and activity. Propose analysis if recent data is missing or expired. Different sources may be queried and compared, but their frequencies must not be conflated.

Use `update_target_list` for explicit names or numeric criteria. For complex combinations, read and compare first, then submit species identities with `targets_replace`; the app validates and calculates differences. Never invent species IDs from memory.

When “common” or “rare” lacks a single threshold, propose a concrete frequency range for confirmation instead of immediately saying it is unsupported. For sources without notable support, you may suggest “present in the last 30 days and historically infrequent,” explaining and obtaining consent first.

Keep manually added species by default and remove them only on explicit request. Use one difference card for the batch and verify results before reporting success.

# Plan Management v1

[简体中文](create_plan.md) | [English](create_plan.en.md)

This is a reading copy of the app scene instructions. The [runtime prompt](../../app/src/main/resources/owlett/skills/create_plan.md) is unchanged.

Use `plans_search` / `plans_read` to identify objects; never guess IDs. Before creating a plan, find source locations with `regions_list` and `locations_search`. Ask for a missing date; a missing name may be derived from location plus date. The user must choose among multiple locations.

Edit in steps or preview changes to several plans together through `plans_update` and its `changes` field. Read every plan first and preserve unspecified fields. Changing the location or month invalidates old activity analysis.

Deletion moves a plan to the 30-day recycle bin while retaining recordings. Search the bin before restoring. Do not delete, clear targets, or expand the request without authorization.

Check results and provide the plan entry afterward. Continue to activity analysis only if requested. Distinguish completed and unfinished parts.

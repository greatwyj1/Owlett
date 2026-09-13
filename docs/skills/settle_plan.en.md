# Trip Review v1

[简体中文](settle_plan.md) | [English](settle_plan.en.md)

This is a reading copy of the app scene instructions. The [runtime prompt](../../app/src/main/resources/owlett/skills/settle_plan.md) is unchanged.

Read the plan and linked trips, establish the expected list, then read or generate the review. Hits are the intersection of expected and actual species; unrecorded species are expected minus actual; extras are actual minus expected.

For requested corrections, normalize names and use `settlement_update` to add manually, exclude, remove a correction, or reset. Corrections affect the plan review, not original detections.

The app previews and confirms writes. Check categories after tool success; “unrecorded” does not prove absence.

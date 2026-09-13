package com.example.birdingsoundmvp.owlett

import com.example.birdingsoundmvp.i18n.AppText

fun localizedSkillTitle(card: OwlettSkillCardPayload): String = when {
    card.kind == "targets_preview" && card.title.startsWith("Update ") && card.title.endsWith("?") ->
        AppText.format("Update {0}?", card.title.removePrefix("Update ").removeSuffix("?"))
    card.kind == "bird_calls" && card.title.endsWith(" calls") ->
        AppText.format("{0} calls", card.title.removeSuffix(" calls"))
    else -> AppText.get(card.title)
}

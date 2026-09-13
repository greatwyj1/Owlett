package com.example.birdingsoundmvp.location

import java.time.LocalDate

object BirdNetWeekCalculator {
    fun currentBirdNetWeek(date: LocalDate = LocalDate.now()): Int {
        val month = date.monthValue
        val weekInMonth = ((date.dayOfMonth - 1) / 7) + 1
        return (((month - 1) * 4) + weekInMonth).coerceIn(1, 48)
    }
}

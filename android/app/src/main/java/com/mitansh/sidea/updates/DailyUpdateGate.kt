package com.mitansh.sidea.updates

import java.time.LocalDate

/** Claim an automatic attempt, not just a success. Manual checks bypass this gate. */
internal class DailyUpdateGate(private val readDay: () -> String?, private val writeDay: (String) -> Boolean) {
    fun claim(day: LocalDate): Boolean = synchronized(lock) {
        val date = day.toString()
        if (readDay() == date) false else writeDay(date)
    }
    private companion object { val lock = Any() }
}

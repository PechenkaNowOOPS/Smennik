package ru.smennik.app.domain

/** Presentation-safe duration, including night and full-day shifts. */
data class ShiftTimeRange(val startMinutes: Int, val endMinutes: Int) {
    init {
        require(startMinutes in 0..1439)
        require(endMinutes in 0..1439)
    }
    val durationMinutes: Int
        get() = if (endMinutes == startMinutes) 1440
            else (endMinutes - startMinutes + 1440) % 1440
    val crossesMidnight: Boolean get() = endMinutes <= startMinutes

    fun estimatedKopecks(hourlyRateKopecks: Long): Long {
        require(hourlyRateKopecks in 0..10_000_000L)
        return durationMinutes.toLong()*hourlyRateKopecks/60L
    }
}

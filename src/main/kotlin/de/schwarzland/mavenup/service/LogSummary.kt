package de.schwarzland.mavenup.service

private const val MAX_LOG_ITEMS = 10

internal fun summarizeForDebugLog(values: List<String>): String {
    val displayedValues = values.take(MAX_LOG_ITEMS).joinToString(", ")
    val omittedCount = values.size - MAX_LOG_ITEMS
    return if (omittedCount > 0) {
        "$displayedValues, ... (+$omittedCount more)"
    } else {
        displayedValues
    }
}

package com.actionanand.localtell.app.ui

import java.util.Locale

private val locationGuideWhitespace = Regex("\\s+")

private fun normalizeLocationGuideText(text: String): String =
    text.trim().lowercase(Locale.ROOT).replace(locationGuideWhitespace, " ")

internal fun matchesLocationGuideQuery(
    query: String,
    question: String,
    answer: String,
    keywords: String,
): Boolean {
    val normalizedQuery = normalizeLocationGuideText(query)
    if (normalizedQuery.isEmpty()) return true
    val searchableText = normalizeLocationGuideText("$question $answer $keywords")
    return normalizedQuery.split(" ").all { word -> word in searchableText }
}
package com.jules.loader.util

import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DateUtils {

    private val DATE_FORMATS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss"
    )

    private val formatters = object : ThreadLocal<MutableMap<String, SimpleDateFormat>>() {
        override fun initialValue() = HashMap<String, SimpleDateFormat>()
    }

    private val displayFormatters = object : ThreadLocal<MutableMap<String, SimpleDateFormat>>() {
        override fun initialValue() = HashMap<String, SimpleDateFormat>()
    }

    private fun getCachedFormatter(
        cache: ThreadLocal<MutableMap<String, SimpleDateFormat>>,
        key: String,
        creator: () -> SimpleDateFormat
    ): SimpleDateFormat {
        val map = checkNotNull(cache.get()) { "ThreadLocal map should not be null" }
        return map.getOrPut(key, creator)
    }

    private fun getFormatter(pattern: String): SimpleDateFormat {
        return getCachedFormatter(formatters, pattern) {
            SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
    }

    private fun getDisplayFormatter(pattern: String): SimpleDateFormat {
        val locale = Locale.getDefault()
        val key = "$pattern:${locale.toLanguageTag()}"
        return getCachedFormatter(displayFormatters, key) {
            SimpleDateFormat(pattern, locale)
        }
    }

    fun parseDate(dateString: String?): Date? {
        if (dateString == null) return null
        for (format in DATE_FORMATS) {
            try {
                return getFormatter(format).parse(dateString)
            } catch (e: java.text.ParseException) {
                // Try next format
            }
        }
        return null
    }

    fun formatChatTimestamp(dateString: String?): String? {
        val date = parseDate(dateString) ?: return null
        val formatter = getDisplayFormatter("d MMM - HH:mm")
        return formatter.format(date)
    }

    fun formatDate(dateString: String?): String? {
        val date = parseDate(dateString) ?: return null
        val dayFormatter = getDisplayFormatter("d")
        val monthFormatter = getDisplayFormatter("MMM")

        val day = dayFormatter.format(date).toInt()
        val month = monthFormatter.format(date)
        val suffix = getDayOfMonthSuffix(day)
        return "$day$suffix $month"
    }

    fun formatDateShort(dateString: String?): String? {
        val date = parseDate(dateString) ?: return null
        val locale = Locale.getDefault()
        val bestPattern = DateFormat.getBestDateTimePattern(locale, "MMdd")
        return getDisplayFormatter(bestPattern).format(date)
    }

    private fun getDayOfMonthSuffix(n: Int): String {
        if (n in 11..13) return "th"
        return when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
    }
}

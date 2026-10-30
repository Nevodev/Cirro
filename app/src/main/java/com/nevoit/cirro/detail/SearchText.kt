package com.nevoit.cirro.detail

import java.text.Normalizer
import java.util.Locale

internal fun normalizeSearchText(text: String): String {
    val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    return buildString {
        var latinBase = false
        var offset = 0
        while (offset < decomposed.length) {
            val codePoint = decomposed.codePointAt(offset)
            offset += Character.charCount(codePoint)
            val type = Character.getType(codePoint)
            val mark = type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
            if (mark) {
                if (!latinBase) appendCodePoint(codePoint)
            } else {
                latinBase = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
                if (codePoint == 0 || Character.isLetterOrDigit(codePoint)) {
                    appendCodePoint(codePoint)
                }
            }
        }
    }
}

internal fun searchTerms(query: String): List<String> =
    query.trim().split(Regex("\\s+")).map(::normalizeSearchText).filter(String::isNotEmpty)

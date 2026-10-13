package com.nevoit.cirro.data

import android.icu.text.Transliterator
import java.text.Collator
import java.util.Locale

internal fun <T> List<T>.sortedByLibraryName(name: (T) -> String): List<T> {
    if (size < 2) return this
    val transliterator = Transliterator.getInstance("Han-Latin; NFD; [:Nonspacing Mark:] Remove; NFC")
    val collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }
    return map { item -> item to collator.getCollationKey(transliterator.transliterate(name(item).trim())) }
        .sortedWith { first, second -> first.second.compareTo(second.second) }
        .map { it.first }
}

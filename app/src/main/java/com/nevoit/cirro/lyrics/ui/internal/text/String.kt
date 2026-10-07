package com.nevoit.cirro.lyrics.ui.internal.text

import android.os.Build

// 上游是 KMP 的 expect（commonMain 声明，androidMain 用 Character.UnicodeBlock 实现，
// jvmMain 另有一份）。Cirro 只有 Android，直接留 Android 实现。

private val cjkBlocks: Set<Character.UnicodeBlock> by lazy {
    mutableSetOf(
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D,
            Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
            Character.UnicodeBlock.HIRAGANA,
            Character.UnicodeBlock.KATAKANA,
            Character.UnicodeBlock.HANGUL_SYLLABLES,
            Character.UnicodeBlock.HANGUL_JAMO,
            Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO,
        )
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E)
                add(Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F)
                add(Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_G)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                add(Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_H)
            }
        }
}

private val arabicBlocks: Set<Character.UnicodeBlock> by lazy {
    mutableSetOf(
            Character.UnicodeBlock.ARABIC,
            Character.UnicodeBlock.ARABIC_SUPPLEMENT,
            Character.UnicodeBlock.ARABIC_EXTENDED_A,
            Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A,
            Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B,
        )
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                add(Character.UnicodeBlock.ARABIC_EXTENDED_B)
            }
        }
}

private val devanagariBlocks: Set<Character.UnicodeBlock> by lazy {
    setOf(Character.UnicodeBlock.DEVANAGARI, Character.UnicodeBlock.DEVANAGARI_EXTENDED)
}

internal fun Char.isCjk(): Boolean {
    return try {
        Character.UnicodeBlock.of(this) in cjkBlocks
    } catch (e: Exception) {
        false
    }
}

internal fun Char.isJapanese(): Boolean {
    return this.code in 0x3040..0x309F || this.code in 0x30A0..0x30FF || this.code in 0xFF66..0xFF9F
}

internal fun Char.isKorean(): Boolean {
    return this.code in 0xAC00..0xD7AF || this.code in 0x1100..0x11FF
}

internal fun Char.isArabic(): Boolean {
    return try {
        Character.UnicodeBlock.of(this) in arabicBlocks
    } catch (e: Exception) {
        false
    }
}

internal fun Char.isDevanagari(): Boolean {
    return try {
        Character.UnicodeBlock.of(this) in devanagariBlocks
    } catch (e: Exception) {
        false
    }
}

internal fun String.isPureCjk(): Boolean {
    val cleanedStr = this.filter { it != ' ' && it != ',' && it != '\n' && it != '\r' }
    if (cleanedStr.isEmpty()) {
        return false
    }
    return cleanedStr.all { it.isCjk() }
}

internal fun String.containsJapanese(): Boolean = any { it.isJapanese() }

internal fun String.containsKorean(): Boolean = any { it.isKorean() }

internal fun String.isRtl(): Boolean {
    for (value in this) {
        when (Character.getDirectionality(value)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
        }
    }
    return false
}

internal fun Char.isProfilePunctuation(): Boolean =
    when (category) {
        CharCategory.CONNECTOR_PUNCTUATION,
        CharCategory.DASH_PUNCTUATION,
        CharCategory.START_PUNCTUATION,
        CharCategory.END_PUNCTUATION,
        CharCategory.INITIAL_QUOTE_PUNCTUATION,
        CharCategory.FINAL_QUOTE_PUNCTUATION,
        CharCategory.OTHER_PUNCTUATION -> true
        else -> this == '～'
    }

internal fun String.isPunctuation(): Boolean =
    isNotEmpty() && all { it.isWhitespace() || it.isProfilePunctuation() }

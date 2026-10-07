package com.nevoit.cirro.lyrics.ui.internal.diagnostics

// 上游是 KMP（commonMain 声明 expect，androidMain / jvmMain 各给一份 actual，jvm 那份是空实现）。
// Cirro 只有 Android，这里直接把 android 实现并进来，没有 jvm 分支可留。

internal fun lyricsTraceEnabled(): Boolean = android.os.Trace.isEnabled()

internal fun beginLyricsTrace(name: String) {
    android.os.Trace.beginSection(name)
}

internal fun endLyricsTrace() {
    android.os.Trace.endSection()
}

internal inline fun <T> traceLyrics(name: String, block: () -> T): T {
    if (!lyricsTraceEnabled()) return block()
    beginLyricsTrace(name)
    try {
        return block()
    } finally {
        endLyricsTrace()
    }
}

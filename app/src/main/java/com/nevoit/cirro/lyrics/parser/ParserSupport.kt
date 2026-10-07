package com.nevoit.cirro.lyrics.parser

import com.nevoit.cirro.lyrics.model.LyricLine
import com.nevoit.cirro.lyrics.model.Lyrics

/**
 * 解析器共用的小工具。
 *
 * 这里只放「跨来源都得做」的事，具体文法留在各自的解析器里。
 */
internal object ParserSupport {

    /**
     * 保证每行都有合法结束时间。
     *
     * 规则：解析器算出的结束时间**不大于起点**（TTML 缺 `end` 属性、LRC 是末行）时，
     * 就用下一行的起点；末行封顶到 [Lyrics.MAX_TIMESTAMP_MS]。
     *
     * 🔴 结束时间必须在**解析阶段**推导完，模型里不允许出现 `-1` / `0` 这类哨兵 ——
     * 渲染层拿到什么就是什么。已经显式给了合法结束时间的行不动。
     */
    fun resolveEndTimes(lines: List<LyricLine>): List<LyricLine> =
        lines.mapIndexed { index, line ->
            if (line.endMs > line.startMs) {
                line
            } else {
                val nextStart = lines.getOrNull(index + 1)?.startMs
                line.copy(
                    endMs = nextStart?.takeIf { it > line.startMs } ?: Lyrics.MAX_TIMESTAMP_MS
                )
            }
        }

    /**
     * 判断一行文本是不是被括号整体包住的背景人声。
     *
     * LRC 家族没有 `ttm:role` 这种标记，业界通行做法就是看整行括号 —— 与 AMLL 一致，
     * 半角 `()` 与全角 `（）` 都算。
     */
    fun isBracketedBackground(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 2) return false
        val first = trimmed.first()
        val last = trimmed.last()
        return (first == '(' && last == ')') || (first == '（' && last == '）')
    }
}

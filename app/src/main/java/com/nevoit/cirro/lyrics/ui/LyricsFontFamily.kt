package com.nevoit.cirro.lyrics.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.nevoit.cirro.R

/**
 * 歌词正文字体：Inter，包里带两个字重 —— `Inter-Medium`(500) + `Inter-Bold`(700)。
 *
 * 只给歌词渲染层用 —— 不是全站字体，别挂到 `com.nevoit.material` 的 Typography 上。
 * 消费方是 [com.nevoit.cirro.lyrics.ui.composable.lyrics.KaraokeLyricsView] 的两条默认
 * `TextStyle`（phonetic 由 normal 派生，自动跟着走）。
 *
 * 三条必须知道的：
 * 1. **没有中日韩字形**（只有拉丁 / 希腊 / 西里尔 / 标点 / 符号，2937 字形），中日韩歌词走系统字体
 *    回退那一档，拉丁和数字才是 Inter。这是预期行为，跟 Pixel 上一样。
 * 2. 字重是**诚实映射**（500 / 700 就是文件里的 `usWeightClass`）。歌词主行与翻译行请求的是
 *    `FontWeight.W800` → 落到 Bold(700)，且**不会**触发「假粗体」合成 —— 合成的判据是命中字重
 *    < 600，700 不满足。所以样式里的 W800 保持原样，别再手动改。
 * 3. 音译/拼音行请求 `FontWeight.Normal`(400)，而家族里没有 400 —— 按 Compose 的 CSS 字重匹配规则
 *    （目标在 400~500 之间时，按升序先试 ≥ 目标字重直到 500）会命中 **Medium(500)**，比 Regular 略粗。
 *    拿不到更细的字重，这是可接受的近似。
 *
 * 加载策略取默认的 `FontLoadingStrategy.Blocking`：字体要在**第一次排版**时就位，否则会先按回退
 * 字体量一遍、字体到了再重排 —— 歌词这套「行高不变式」经不起中途换行数。
 */
val LyricsFontFamily: FontFamily =
    FontFamily(
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_bold, FontWeight.Bold),
    )

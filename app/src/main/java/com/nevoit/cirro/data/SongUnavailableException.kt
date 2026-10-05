package com.nevoit.cirro.data

import java.io.IOException

/**
 * 这首歌**确定**播不了：无版权、下架、VIP 未开通。
 *
 * 要跟"请求本身失败"分开（后者是普通 IOException / IllegalStateException，重试有意义）。
 * 播放器侧靠它决定要不要重试——见 `playback/PlaybackService.kt` 里的错误策略。
 */
class SongUnavailableException(message: String) : IOException(message)

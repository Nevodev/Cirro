package com.nevoit.cirro.data.model

/**
 * 登录态。[Unknown] 是"还没查出来"，冷启动时先渲染成未知，免得先闪一下"未登录"再跳成已登录。
 */
sealed interface Session {

    /** 还没查出来（冷启动、或刚恢复会话时）。 */
    data object Unknown : Session

    /** 确定没有登录态。 */
    data object LoggedOut : Session

    /** 确定没有登录态，但磁盘上还有一份没验证过的 cookie（比如冷启动断网）。别劝人登录，下次启动再验。 */
    data object Offline : Session

    /** cookie 有效。[profile] 可能还是空的：资料没拉回来，但登录态是确定的。 */
    data class LoggedIn(val profile: UserProfile) : Session
}

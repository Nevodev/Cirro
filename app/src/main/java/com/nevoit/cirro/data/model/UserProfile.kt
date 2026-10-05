package com.nevoit.cirro.data.model

import kotlinx.serialization.Serializable

/** 从 `login_status` 的 `profile` 里挑出来的那部分，字段名与网易云一致。 */
@Serializable
data class UserProfile(
    val userId: Long = 0L,
    val nickname: String = "",
    val avatarUrl: String? = null,
    val signature: String? = null,
)

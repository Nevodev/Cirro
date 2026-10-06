package com.nevoit.cirro.data.model

import kotlinx.serialization.Serializable

/**
 * 移动端首页（`/api/homepage/block/page`）的一个区块。
 *
 * 上游一次下发好几块，每块的资源形状还不一样（歌单 / 歌曲组），原生层已经统一成
 * [code] 加两个列表，客户端按 [code] 挑自己认识的那几块，不认识的就忽略——
 * 服务端加减区块不用改这里。
 *
 * [columns] 是**按列**给的，不是一维列表：曲风推荐在界面上就是一竖列三首，
 * 拍平了再按三首一组切，等于依赖"服务端每组都给满三首"这个没写进契约的前提。
 */
@Serializable
data class HomeBlock(
    val code: String = "",
    val title: String = "",
    val showType: String = "",
    val playlists: List<Playlist> = emptyList(),
    val columns: List<List<Song>> = emptyList(),
) {
    /** 曲风推荐按列展开成一维，供整块入队播放。 */
    val songs: List<Song> get() = columns.flatten()
}

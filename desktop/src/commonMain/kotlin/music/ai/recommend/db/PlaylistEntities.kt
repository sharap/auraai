package music.ai.recommend.db

data class PlaylistEntity(
    val id: Long = 0,
    val name: String
)

data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: Long,
    val order: Int
)

data class FavoriteEntity(
    val songId: Long
)

package music.ai.recommend.db

data class QueueEntity(
    val songId: Long,
    val position: Int
)

data class SettingEntity(
    val key: String,
    val value: String
)

data class EqPresetEntity(
    val name: String,
    val levels: List<Float>,
    val isCustom: Boolean
)

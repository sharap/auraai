package music.ai.recommend.db

class Converters {
    fun fromString(value: String): List<Float> {
        return value.split(",").mapNotNull { it.toFloatOrNull() }
    }

    fun fromList(list: List<Float>): String {
        return list.joinToString(",")
    }
}

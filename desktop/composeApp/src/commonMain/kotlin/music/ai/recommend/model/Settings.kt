package music.ai.recommend.model

import androidx.compose.runtime.Immutable

@Immutable
data class EqBand(val index: Int, val freq: String, val level: Float)

@Immutable
data class EqPreset(val name: String, val levels: List<Float>, val isCustom: Boolean = false)

data class AppSettings(
    val musicFolderPath: String = "",
    val isDarkTheme: Boolean = true,
    val backgroundImageUri: String? = null,
    val backgroundAlpha: Float = 0.3f,
    val eqLevels: List<Float> = List(10) { 0f },
    val customEqPresets: List<EqPreset> = emptyList(),
    /** Multiplier on the eps smart-album clustering picks for itself; 1 is automatic. */
    val smartAlbumsEpsScale: Float = 1f,
    val volumePercent: Int = 100,
    /** The folder listening history is exchanged through; empty when sync is not set up. */
    val syncFolderPath: String = "",
    /**
     * The phrase the sync folder is sealed under, or empty for a folder in the clear.
     *
     * Kept in this machine's own settings, which is a file no wider than its owner. See
     * `sync/Sealing`: what it protects is the folder, and the folder is often somewhere else.
     */
    val syncPhrase: String = "",
    /**
     * Whether this machine carries sync itself over the network instead of through a folder.
     *
     * One or the other, not both: the two transports carry the same records and each keeps its own
     * note of how far every file was read, so running both would mean two of everything that
     * remembers, for no gain.
     */
    val syncDirect: Boolean = false,
    /**
     * The other device's address, for a network that does not carry broadcasts. Host, or host:port.
     */
    val syncPeerAddress: String = ""
)

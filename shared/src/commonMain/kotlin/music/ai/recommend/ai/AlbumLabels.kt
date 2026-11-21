package music.ai.recommend.ai

/**
 * One kind of music the library might be grouped by: what to ask the text model, and what to call
 * the result.
 *
 * Two fields and two different jobs. [prompt] is an input to CLAP's text tower and is never shown
 * to anybody — it is English because the model is, and rewording it changes which songs land in the
 * album. [nameKey] names a string in the `:strings` module, which both interfaces read, and that is
 * what a person sees.
 */
data class AlbumLabel(val prompt: String, val nameKey: String)

/**
 * The vocabulary, in one place, in an order that matters.
 *
 * The position in this list is what a built album stores — `StoredAlbum.label` is an index — so two
 * devices that disagree about the order would read each other's caches as different music. Until
 * the two code bases were merged this list existed twice, once per port, and the orders happened to
 * match; they were checked by hand, which is to say they matched because nobody had got it wrong
 * yet. Both ports now read this one.
 *
 * The names used to differ in a way nobody could call a bug: the phone resolved them through
 * Android resources and said «Транс», the desktop held English literals and said "Trance". Now
 * there is one set of strings, translated once, and the only way for the two to differ is for them
 * to be running different versions.
 */
object AlbumLabels {
    val all = listOf(
        AlbumLabel("trance music with euphoric synth leads", "smart_label_trance"),
        AlbumLabel("house music with a four-on-the-floor beat", "smart_label_house"),
        AlbumLabel("big room electronic dance music drops", "smart_label_edm"),
        AlbumLabel("drum and bass with fast breakbeats", "smart_label_dnb"),
        AlbumLabel("catchy pop song with female vocals", "smart_label_pop"),
        AlbumLabel("upbeat dance pop remix", "smart_label_dance_pop"),
        AlbumLabel("russian pop ballad with male vocals", "smart_label_estrada"),
        AlbumLabel("russian chanson with guitar and male vocals", "smart_label_chanson"),
        AlbumLabel("rock band with electric guitars and drums", "smart_label_rock"),
        AlbumLabel("heavy metal with distorted guitars", "smart_label_metal"),
        AlbumLabel("instrumental post-rock with swelling guitars", "smart_label_post_rock"),
        AlbumLabel("indie folk with acoustic guitar and soft vocals", "smart_label_indie_folk"),
        AlbumLabel("medieval folk music with flutes and fiddles", "smart_label_folk"),
        AlbumLabel("celtic harp music", "smart_label_harp"),
        AlbumLabel("nordic folk with ethereal female vocals", "smart_label_nordic"),
        AlbumLabel("traditional folk dance music", "smart_label_folk_dance"),
        AlbumLabel("classical orchestra symphony", "smart_label_classical"),
        AlbumLabel("solo piano classical piece", "smart_label_piano"),
        AlbumLabel("romantic piano and strings", "smart_label_romantic"),
        AlbumLabel("opera singing with orchestra", "smart_label_opera"),
        AlbumLabel("sacred choir singing a cappella", "smart_label_choir"),
        AlbumLabel("epic cinematic orchestral trailer music", "smart_label_epic"),
        AlbumLabel("film soundtrack score", "smart_label_soundtrack"),
        AlbumLabel("calm ambient soundscape", "smart_label_ambient"),
        AlbumLabel("relaxing new age music", "smart_label_new_age"),
        AlbumLabel("spanish flamenco guitar", "smart_label_flamenco"),
        AlbumLabel("latin reggaeton party music", "smart_label_latin"),
        AlbumLabel("jazz with saxophone", "smart_label_jazz"),
        AlbumLabel("blues guitar", "smart_label_blues"),
        AlbumLabel("hip hop beat with rap vocals", "smart_label_hip_hop"),
        AlbumLabel("duduk and wind instruments playing a sad melody", "smart_label_winds"),
        AlbumLabel("birds singing in nature", "smart_label_nature"),
        AlbumLabel("dark gothic music", "smart_label_gothic"),
        AlbumLabel("eighties synth pop", "smart_label_synth_pop"),
        AlbumLabel("lo-fi chill beats", "smart_label_lofi"),
    )

    /** Changes whenever the vocabulary does, invalidating cached label embeddings and names. */
    val fingerprint: Int = all.joinToString("|") { it.prompt }.hashCode()
}

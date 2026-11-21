package music.ai.recommend.platform

import android.content.ContentUris
import android.provider.MediaStore
import music.ai.recommend.model.Folder
import music.ai.recommend.model.Song
import java.io.File

actual class MusicScanner actual constructor() {
    actual fun scanMusic(): List<Folder> {
        val context = ContextProvider.context ?: return emptyList()
        val songList = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val query = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )

        query?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "Unknown"
                val artist = cursor.getString(artistColumn) ?: "Unknown"
                val album = cursor.getString(albumColumn) ?: "Unknown"
                val albumId = cursor.getLong(albumIdColumn)
                val duration = cursor.getLong(durationColumn)
                val path = cursor.getString(dataColumn) ?: ""
                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                ).toString()
                
                val folderName = File(path).parentFile?.name ?: "Unknown"

                songList.add(
                    Song(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        albumId = albumId,
                        duration = duration,
                        uri = uri,
                        path = path,
                        folderName = folderName
                    )
                )
            }
        }

        return songList.groupBy { it.folderName }
            .map { (name, songs) -> Folder(name, songs) }
    }

    actual fun scanCustomPath(path: String): List<Folder> {
        // Android doesn't support custom paths easily without permissions
        return emptyList()
    }
}

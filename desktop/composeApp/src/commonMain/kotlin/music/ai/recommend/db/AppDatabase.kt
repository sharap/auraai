package music.ai.recommend.db

interface AppDatabase {
    fun musicDao(): MusicDao

    /**
     * Where this app keeps its own files.
     *
     * Asked of the database because the database is what decides it, and sync needs a place of its
     * own beside it for the device identifier and the state it keeps between runs — neither of
     * which is a setting, and neither of which belongs in the folder being synced.
     */
    fun dataDirectory(): String
}

expect fun getAppDatabase(): AppDatabase

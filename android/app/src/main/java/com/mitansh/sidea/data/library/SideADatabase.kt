package com.mitansh.sidea.data.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [HistoryEntry::class, FavoriteEntry::class, Playlist::class, PlaylistTrackEntry::class,
        ListeningStatEntry::class, ListeningPlayEntry::class, ImportProvenanceEntry::class],
    version = 5,
    exportSchema = true,
)
abstract class SideADatabase : RoomDatabase() {

    abstract fun historyDao(): HistoryDao

    abstract fun favoritesDao(): FavoritesDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun listeningStatsDao(): ListeningStatsDao

    abstract fun importProvenanceDao(): ImportProvenanceDao

    companion object {
        private const val NAME = "side-a.db"

        @Volatile
        private var instance: SideADatabase? = null

        fun get(context: Context): SideADatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SideADatabase::class.java,
                NAME,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
        }

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS playlists (id TEXT NOT NULL, name TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS playlist_entries (entryId TEXT NOT NULL, playlistId TEXT NOT NULL, position INTEGER NOT NULL, trackId TEXT NOT NULL, source TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, durationMs INTEGER NOT NULL, artworkUrl TEXT, PRIMARY KEY(entryId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_entries_playlistId ON playlist_entries (playlistId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_playlist_entries_playlistId_position ON playlist_entries (playlistId, position)")
            }
        }

        /**
         * Adds the durable listening statistics table.
         *
         * The table is created empty: existing history rows are not backfilled, because the
         * play counts they carry are the only record of past listening and copying them would
         * silently overstate how often a track was actually heard. Counts start accumulating from
         * the first play after the update.
         *
         * The statement must match Room's generated schema exactly, including the secondary
         * indexes, because Room validates the migrated table against it on open. Room's generated
         * index names are part of that contract.
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `listening_stats` (`uid` TEXT NOT NULL, `artistKey` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `artworkUrl` TEXT, `playCount` INTEGER NOT NULL, `completedCount` INTEGER NOT NULL, `skipCount` INTEGER NOT NULL, `firstPlayedAtEpochMs` INTEGER NOT NULL, `lastPlayedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`uid`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_listening_stats_playCount` ON `listening_stats` (`playCount`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_listening_stats_artistKey` ON `listening_stats` (`artistKey`)")
            }
        }

        /**
         * Adds the table that keeps the source identity of imported songs.
         *
         * The table is created empty: nothing was imported before this version, so there is
         * nothing to backfill.
         */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `import_provenance` (`entryId` TEXT NOT NULL, `playlistId` TEXT NOT NULL, `source` TEXT NOT NULL, `sourceTrackId` TEXT NOT NULL, `sourcePosition` INTEGER NOT NULL, `chosenTrackId` TEXT NOT NULL, `sourceTitle` TEXT NOT NULL, `sourceArtist` TEXT NOT NULL, `importedAtEpochMs` INTEGER NOT NULL, `sourceSavedAt` TEXT, PRIMARY KEY(`entryId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_import_provenance_playlistId` ON `import_provenance` (`playlistId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_import_provenance_sourceTrackId` ON `import_provenance` (`sourceTrackId`)")
            }
        }

        /** Existing library rows are preserved. Past session timestamps are not invented. */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `listening_plays` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uid` TEXT NOT NULL, `playedAtEpochMs` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_listening_plays_uid_playedAtEpochMs` ON `listening_plays` (`uid`, `playedAtEpochMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_listening_plays_playedAtEpochMs` ON `listening_plays` (`playedAtEpochMs`)")
            }
        }
    }
}

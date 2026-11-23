package dev.mytv.data.local.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Base locale unique. À chaque changement de schéma : incrémenter `version` et ajouter une
 * migration (les schémas exportés dans data/schemas permettent les AutoMigration).
 */
@Database(
    entities = [
        PortalEntity::class,
        LiveCategoryEntity::class,
        LiveChannelEntity::class,
        LiveFavoriteEntity::class,
        EpgProgramEntity::class,
        VodCategoryEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        ResumeEntity::class,
        ChannelStatEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), // v2 : tables Live TV (ajout pur, aucune donnée touchée)
        AutoMigration(from = 2, to = 3), // v3 : tables VOD + positions de lecture (ajout pur)
        AutoMigration(from = 3, to = 4), // v4 : chaînes les plus regardées (ajout pur)
    ],
)
abstract class MyTvDatabase : RoomDatabase() {
    abstract fun portalDao(): PortalDao
    abstract fun liveDao(): LiveDao
    abstract fun epgDao(): EpgDao
    abstract fun vodDao(): VodDao
    abstract fun backupDao(): BackupDao
}

package com.docscanner.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [DocumentEntity::class, PageEntity::class, OutboxEntity::class, SyncShadowEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
    abstract fun pages(): PageDao
    abstract fun outbox(): OutboxDao
    abstract fun sync(): SyncDao

    companion object {
        /** v2: sync_shadow, the merge base for cloud backup. Existing data is untouched. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_shadow` (`entity` TEXT NOT NULL, `entity_id` TEXT NOT NULL, " +
                        "`payload` BLOB NOT NULL, `remote_rev` INTEGER NOT NULL, PRIMARY KEY(`entity`, `entity_id`))",
                )
            }
        }

        /** SQLCipher-encrypted database. The whole file is encrypted with the Keystore-protected passphrase. */
        fun create(context: Context, passphrase: ByteArray): AppDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, AppDatabase::class.java, "scanner.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}

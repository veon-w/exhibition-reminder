package com.exporeminder.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ExhibitionEntity::class,
        FollowEntity::class,
        NotificationLogEntity::class,
        SuggestedLinkEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exhibitionDao(): ExhibitionDao
    abstract fun followDao(): FollowDao
    abstract fun notificationLogDao(): NotificationLogDao
    abstract fun suggestedLinkDao(): SuggestedLinkDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v1→v2:加開賣時間兩欄(nullable,無 default,與 entity 宣告一致)。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exhibition ADD COLUMN saleStartAt TEXT")
                db.execSQL("ALTER TABLE exhibition ADD COLUMN saleStartText TEXT")
            }
        }

        /** v2→v3:加每日開閉館時間兩欄。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exhibition ADD COLUMN openTime TEXT")
                db.execSQL("ALTER TABLE exhibition ADD COLUMN closeTime TEXT")
            }
        }

        /** v3→v4:場地座標(距離排序)+ 追蹤靜音旗標。 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exhibition ADD COLUMN lat REAL")
                db.execSQL("ALTER TABLE exhibition ADD COLUMN lng REAL")
                db.execSQL("ALTER TABLE follow ADD COLUMN muted INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "expo.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }
    }
}

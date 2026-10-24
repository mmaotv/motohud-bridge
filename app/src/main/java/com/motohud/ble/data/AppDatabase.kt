package com.motohud.ble.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SearchHistoryEntity::class, SavedPlaceEntity::class, NaviHistoryEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun naviHistoryDao(): NaviHistoryDao

    companion object {
        /** v1 -> v2：新增 saved_place 表（搜索历史不丢） */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS saved_place (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "latitude REAL NOT NULL, " +
                        "longitude REAL NOT NULL, " +
                        "type TEXT NOT NULL DEFAULT 'other', " +
                        "remark TEXT NOT NULL DEFAULT '', " +
                        "createdAt INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }

        /** v2 -> v3：新增 navi_history 表（导航历史，其他数据不动） */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS navi_history (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "destName TEXT NOT NULL, " +
                        "destLat REAL NOT NULL, " +
                        "destLng REAL NOT NULL, " +
                        "startName TEXT NOT NULL, " +
                        "startLat REAL NOT NULL, " +
                        "startLng REAL NOT NULL, " +
                        "startedAt INTEGER NOT NULL, " +
                        "finishedAt INTEGER NOT NULL DEFAULT 0, " +
                        "durationMs INTEGER NOT NULL DEFAULT 0, " +
                        "distanceM REAL NOT NULL DEFAULT 0, " +
                        "castToDevice INTEGER NOT NULL DEFAULT 0, " +
                        "deviceName TEXT, " +
                        "deviceType TEXT, " +
                        "battery REAL, " +
                        "waterTemp INTEGER, " +
                        "oil INTEGER, " +
                        "mileage INTEGER)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_navi_history_startedAt " +
                        "ON navi_history (startedAt)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_navi_history_finishedAt " +
                        "ON navi_history (finishedAt)"
                )
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "motohud.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}

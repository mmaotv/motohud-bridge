package com.motohud.ble.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SearchHistoryDao {

    @Insert
    suspend fun insert(entity: SearchHistoryEntity): Long

    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SearchHistoryEntity>

    @Query("SELECT COUNT(*) FROM search_history WHERE keyword = :keyword AND scope = :scope")
    suspend fun countOf(keyword: String, scope: String): Int

    @Query("DELETE FROM search_history WHERE keyword = :keyword AND scope = :scope")
    suspend fun delete(keyword: String, scope: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

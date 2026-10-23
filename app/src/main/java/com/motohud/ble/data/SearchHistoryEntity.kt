package com.motohud.ble.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 搜索历史（复刻原 App greenDAO 表 SEARCH_KEY 的设计，
 * 见 decompiled/.../database/greenDao/db/SearchKeyDao.java:33）
 */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val scope: String,   // 搜索范围：fault / command / glossary
    val timestamp: Long = System.currentTimeMillis()
)

package com.motohud.ble.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 常用地址（导航目的地快捷方式）。
 * 由用户手动添加，对应原 App POI 搜索选点后的个人化补充：
 * 一键点击 → 自动开始投屏导航（BLE 转发 REM/NEXT/NAVI 或 NA/RE/NE）。
 */
@Entity(tableName = "saved_place")
data class SavedPlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 地址名称，如"家"、"公司" */
    val name: String,
    /** 纬度（WGS84/GCJ02 均可，仅用于导航与外部地图跳转） */
    val latitude: Double,
    /** 经度 */
    val longitude: Double,
    /** 预设类型：home / work / other（对应 ic_place_home/work/other） */
    @ColumnInfo(defaultValue = "other") val type: String = TYPE_OTHER,
    /** 备注（可选） */
    val remark: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_HOME = "home"
        const val TYPE_WORK = "work"
        const val TYPE_OTHER = "other"
    }
}

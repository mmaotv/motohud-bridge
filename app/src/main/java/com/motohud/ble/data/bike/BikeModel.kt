package com.motohud.ble.data.bike

/**
 * 车型数据模型。
 *
 * 车型库只收录公开资料可查的整车参数（排量、功率、扭矩、整备质量、座高、油箱、轮胎等），
 * 仅作为「我的车」页面的展示信息，不参与任何车辆控制逻辑。
 */
enum class BikeBrand(val display: String, val tagline: String) {
    APRILIA("APRILIA", "BE A RACER"),
    VESPA("VESPA", "ITALIAN ICON"),
    PIAGGIO("PIAGGIO", "URBAN MOBILITY")
}

/** 一条整车参数 */
data class BikeSpec(val label: String, val value: String)

/**
 * 一款车型。
 *
 * @param image      assets/bikes 下的图片文件名（替换图片时保持同名即可）
 * @param accent     车型强调色（用于 Hero 光晕与强调线条）
 * @param prefixes   车机蓝牙名的前缀特征，用于连接后自动匹配车型
 */
data class BikeModel(
    val id: String,
    val brand: BikeBrand,
    val name: String,
    val subtitle: String,
    val image: String,
    val accent: Long,
    val highlights: List<String>,
    val specs: List<BikeSpec>,
    val prefixes: List<String> = emptyList()
) {
    val assetPath: String get() = "bikes/$image"

    /** 车机名称是否匹配本车型 */
    fun matchesDevice(deviceName: String?): Boolean {
        if (deviceName.isNullOrBlank()) return false
        return prefixes.any { deviceName.startsWith(it, ignoreCase = true) }
    }
}

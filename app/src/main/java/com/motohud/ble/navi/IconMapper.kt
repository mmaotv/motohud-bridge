package com.motohud.ble.navi

/**
 * 高德路线步骤动作文本 → 高德 IconType 映射。
 *
 * 车机端识别的是原厂同款高德 IconType 数值（与导航 SDK 的 NaviInfo.getIconType 一致），
 * 因此搜索 SDK 返回的 DriveStep.getAction()/getOrientation() 文本必须映射为相同数值，
 * 才能保证车机转向图标与专业导航软件一致。
 *
 * 取值依据 com.amap.api.navi.enums.IconType：
 *   2 左转 / 3 右转 / 4 左前转 / 5 右前转 / 6 左后转 / 7 右后转 /
 *   8 掉头 / 9 直行 / 11 进入环岛 / 12 驶出环岛 / 15 到达目的地 /
 *   65 靠左 / 66 靠右
 */
object IconMapper {

    fun of(action: String?, orientation: String?, instruction: String?): Int {
        val a = action?.trim().orEmpty()
        val o = orientation?.trim().orEmpty()
        val ins = instruction.orEmpty()

        // 1) 优先按结构化 action 精确匹配
        when (a) {
            "左转" -> return IconType.LEFT
            "右转" -> return IconType.RIGHT
            "左前转", "左前方转弯", "稍向左转" -> return IconType.LEFT_FRONT
            "右前转", "右前方转弯", "稍向右转" -> return IconType.RIGHT_FRONT
            "左后转", "左后方转弯" -> return IconType.LEFT_BACK
            "右后转", "右后方转弯" -> return IconType.RIGHT_BACK
            "掉头", "左掉头" -> return IconType.LEFT_TURN_AROUND
            "右掉头" -> return IconType.U_TURN_RIGHT
            "直行", "沿当前道路直行" -> return IconType.STRAIGHT
            "进入环岛", "进入环岛路口" -> return IconType.ENTER_ROUNDABOUT
            "驶出环岛", "驶出环岛路口" -> return IconType.OUT_ROUNDABOUT
            "靠左", "偏左" -> return IconType.MERGE_LEFT
            "靠右", "偏右" -> return IconType.MERGE_RIGHT
            "到达目的地", "到达终点", "到达途径点" -> return IconType.ARRIVED_DESTINATION
            "通过人行横道", "通过公交车站", "经过道路", "进入路口", "出发", "" -> Unit
        }

        // 2) action 缺失时按 orientation / instruction 兜底
        when (o) {
            "左" -> return IconType.LEFT
            "右" -> return IconType.RIGHT
            "北", "南", "东", "西" -> return IconType.STRAIGHT
        }
        return when {
            ins.contains("掉头") -> IconType.LEFT_TURN_AROUND
            ins.contains("左前") -> IconType.LEFT_FRONT
            ins.contains("右前") -> IconType.RIGHT_FRONT
            ins.contains("左后") -> IconType.LEFT_BACK
            ins.contains("右后") -> IconType.RIGHT_BACK
            ins.contains("左转") -> IconType.LEFT
            ins.contains("右转") -> IconType.RIGHT
            ins.contains("环岛") -> IconType.ENTER_ROUNDABOUT
            ins.contains("靠左") -> IconType.MERGE_LEFT
            ins.contains("靠右") -> IconType.MERGE_RIGHT
            ins.contains("到达") -> IconType.ARRIVED_DESTINATION
            else -> IconType.STRAIGHT
        }
    }
}

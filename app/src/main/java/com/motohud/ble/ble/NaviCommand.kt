package com.motohud.ble.ble

import com.motohud.ble.ble.BleConstants.DeviceType

/**
 * 导航 BLE 指令转发器。
 *
 * 复刻原 App BaseNaviActivity.writeData / NormalMotorFragment.java:1795-1827 的码表选择逻辑：
 *  - SR  设备：REM :<全程剩余>::   NEXT :<下一转弯剩余>::   NAVI :<图标>::
 *  - BYQ 设备：NA  :<图标>:       RE  :<全程剩余>:        NE  :<下一转弯剩余>:
 *  - 未知设备：两套都发（原 App 对 byq 大小写兼容的扩展）
 * 到达终点发 FINISH :::，退出导航发 NO（BaseNaviActivity.java:264,368）。
 */
object NaviCommand {

    /** 发送一帧导航信息（onNaviInfoUpdate 等价） */
    fun forwardInfo(
        send: (String) -> Unit,
        deviceName: String?,
        iconType: Int,
        pathRemain: Int,
        stepRemain: Int
    ) {
        when (BleConstants.deviceTypeOf(deviceName)) {
            DeviceType.SR -> {
                send(BleConstants.cmdRem(pathRemain))
                send(BleConstants.cmdNext(stepRemain))
                send(BleConstants.cmdNavi(iconType))
            }

            DeviceType.BYQ -> {
                send(BleConstants.cmdNa(iconType))
                send(BleConstants.cmdRe(pathRemain))
                send(BleConstants.cmdNe(stepRemain))
            }

            DeviceType.UNKNOWN -> {
                send(BleConstants.cmdNa(iconType))
                send(BleConstants.cmdRe(pathRemain))
                send(BleConstants.cmdNe(stepRemain))
                send(BleConstants.cmdRem(pathRemain))
                send(BleConstants.cmdNext(stepRemain))
                send(BleConstants.cmdNavi(iconType))
            }
        }
    }

    /** 到达终点（BaseNaviActivity.java:368 onArriveDestination） */
    fun forwardFinish(send: (String) -> Unit) = send(BleConstants.CMD_FINISH)

    /** 退出导航（BaseNaviActivity.java:264 onDestroy） */
    fun forwardExit(send: (String) -> Unit) = send(BleConstants.CMD_NO)
}

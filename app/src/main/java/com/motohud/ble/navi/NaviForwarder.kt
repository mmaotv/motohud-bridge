package com.motohud.ble.navi

import com.motohud.ble.ble.BleConnectionManager
import com.motohud.ble.ble.NaviCommand

/**
 * BLE 导航指令转发器。
 *
 * 手机导航页（GuidanceActivity）与车机投屏页（HudActivity）共用同一个导航引擎，
 * 为避免重复发送，转发器采用"附着"机制：界面 onStart 时 attach，onStop 时 detach，
 * 仅当至少有一个界面附着时才向车机发送指令（投屏 HUD 在前台时导航页已 stop，天然互斥）。
 */
class NaviForwarder(private val ble: BleConnectionManager) {

    private val attached = LinkedHashSet<String>()

    fun attach(tag: String) {
        attached.add(tag)
    }

    fun detach(tag: String) {
        attached.remove(tag)
    }

    val isForwarding: Boolean
        get() = attached.isNotEmpty()

    /** 每 tick 转发一帧（复刻原 App onNaviInfoUpdate 每次都写） */
    fun forward(state: NaviEngine.NaviState) {
        if (attached.isEmpty()) return
        if (!state.running && !state.arrived) return
        if (ble.state.value != BleConnectionManager.State.CONNECTED) return
        NaviCommand.forwardInfo(
            send = { text -> ble.sendCommand(text) },
            deviceName = ble.connectedDeviceName,
            iconType = state.iconType,
            pathRemain = state.pathRemain,
            stepRemain = state.stepRemain
        )
    }

    /** 到达终点：FINISH ::: */
    fun forwardFinish() {
        if (ble.state.value != BleConnectionManager.State.CONNECTED) return
        NaviCommand.forwardFinish { text -> ble.sendCommand(text) }
    }

    /** 退出导航：NO */
    fun forwardExit() {
        if (ble.state.value != BleConnectionManager.State.CONNECTED) return
        NaviCommand.forwardExit { text -> ble.sendCommand(text) }
    }
}

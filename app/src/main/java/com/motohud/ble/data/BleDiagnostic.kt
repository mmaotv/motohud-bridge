package com.motohud.ble.data

import com.motohud.ble.ble.BleProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 蓝牙连接诊断记录器（单例）。
 *
 * 在 BleConnectionManager / MainViewModel 的关键节点调用，持续记录整条链路：
 *   连接状态 → 服务发现 → CCCD 通知开启 → HELLO 握手 → 收帧(原始+解码) → 发送指令结果
 * 即使诊断页未打开也在累积，方便用户在车机现场把文本复制出来反馈。
 */
object BleDiagnostic {

    private val _state = MutableStateFlow("DISCONNECTED")
    val state = _state.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName = _deviceName.asStateFlow()

    private val _notifyReady = MutableStateFlow(false)
    val notifyReady = _notifyReady.asStateFlow()

    private val _helloSent = MutableStateFlow(false)
    val helloSent = _helloSent.asStateFlow()

    private val _frameCount = MutableStateFlow(0)
    val frameCount = _frameCount.asStateFlow()

    private val _lastRaw = MutableStateFlow("")
    val lastRaw = _lastRaw.asStateFlow()

    private val _lastSummary = MutableStateFlow("")
    val lastSummary = _lastSummary.asStateFlow()

    private val _sent = MutableStateFlow<List<SentLine>>(emptyList())
    val sent = _sent.asStateFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log = _log.asStateFlow()

    data class SentLine(val text: String, val ok: Boolean?)
    data class LogLine(val time: String, val msg: String)

    /** 一次新的连接尝试开始时调用，清空会话级计数 */
    fun onNewConnection(name: String?) {
        _deviceName.value = name
        _state.value = "CONNECTING"
        _notifyReady.value = false
        _helloSent.value = false
        _frameCount.value = 0
        _lastRaw.value = ""
        _lastSummary.value = ""
        log("开始连接: ${name ?: "未知设备"}")
    }

    fun setState(s: String) {
        _state.value = s
        if (s == "DISCONNECTED") _notifyReady.value = false
        log("状态 → $s")
    }

    fun setNotifyReady(v: Boolean) {
        _notifyReady.value = v
        if (v) log("通知通道已开启 (CCCD 写入成功)")
    }

    fun markHelloSent() {
        if (!_helloSent.value) {
            _helloSent.value = true
            log("已发送 HELLO 握手")
        }
    }

    fun onNotify(hex: String) {
        _frameCount.value = _frameCount.value + 1
        _lastRaw.value = hex
        _lastSummary.value = summarize(hex)
        log("收帧 #${_frameCount.value}: $hex")
    }

    fun onSend(text: String) {
        _sent.value = (_sent.value + SentLine(text, null)).takeLast(20)
        log("发送: $text")
    }

    fun onWriteResult(success: Boolean, hex: String) {
        val list = _sent.value.toMutableList()
        for (i in list.indices.reversed()) {
            if (list[i].ok == null) {
                list[i] = list[i].copy(ok = success)
                break
            }
        }
        _sent.value = list
        log("写入结果: ${if (success) "成功" else "失败"} ($hex)")
    }

    fun reset() {
        _state.value = "DISCONNECTED"
        _deviceName.value = null
        _notifyReady.value = false
        _helloSent.value = false
        _frameCount.value = 0
        _lastRaw.value = ""
        _lastSummary.value = ""
        _sent.value = emptyList()
        _log.value = emptyList()
    }

    fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _log.value = (_log.value + LogLine(t, msg)).takeLast(150)
    }

    private fun summarize(hex: String): String {
        val f = BleProtocol.parseFrame(hex) ?: return "无法解析 (${hex.length} 字符)"
        return when (f) {
            is BleProtocol.Frame.CmdA ->
                "CmdA 开关位域 " + f.switches.joinToString("") { if (it == 1) "1" else "0" }
            is BleProtocol.Frame.CmdB ->
                "CmdB 速度=${f.speed} 转速=${f.rotatingSpeed} 油耗=${f.instantaneousFuel}"
            is BleProtocol.Frame.CmdC ->
                "CmdC 水温=${f.waterTemperature} 环境温度=${f.roomTemperature} 油量=${f.oilQuantity}% 电压=${f.batteryVoltage}V"
            is BleProtocol.Frame.CmdD ->
                "CmdD 总里程=${f.totalMileage} 极速=${f.maxSpeed} 均速=${f.averageSpeed}"
            is BleProtocol.Frame.CmdE ->
                "CmdE 故障索引=${f.malfunctionIndex} ${f.malfunctionCode ?: ""}"
            is BleProtocol.Frame.CmdF ->
                "CmdF 故障索引=${f.malfunctionIndex} ${f.malfunctionCode ?: ""}"
            is BleProtocol.Frame.Unknown -> "未知命令 cmd=${f.cmd}"
        }
    }

    fun exportText(): String = buildString {
        append("=== 蓝牙连接诊断 ===\n")
        append("状态: ${_state.value}\n")
        append("设备: ${_deviceName.value ?: "无"}\n")
        append("通知通道: ${if (_notifyReady.value) "已开启" else "未开启"}\n")
        append("HELLO 握手: ${if (_helloSent.value) "已发送" else "未发送"}\n")
        append("收到帧数: ${_frameCount.value}\n")
        append("最近原始帧: ${_lastRaw.value}\n")
        append("最近帧解析: ${_lastSummary.value}\n\n")
        append("--- 最近发送指令 ---\n")
        _sent.value.reversed().forEach { line ->
            val mark = if (line.ok == true) "OK" else if (line.ok == false) "FAIL" else "..."
            append("[$mark] ${line.text}\n")
        }
        append("\n--- 事件日志 ---\n")
        _log.value.forEach { append("[${it.time}] ${it.msg}\n") }
    }
}

package com.motohud.ble.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * BLE 连接管理器 —— 原 App FastBLE 栈（BleDealer + ScanMainActivity + BleManager）的
 * 原生 GATT 等价实现，零第三方依赖。
 *
 * 复刻行为：
 *  - 扫描 10s 超时，名称前缀 BYQ/SR 过滤（ScanMainActivity.java:208-313）
 *  - autoConnect 连接，服务发现后使能 00001234 通知（CCCD 00002902）
 *  - 掉线自动重连：10 次 × 500ms（ScanMainActivity.java:395-398 / BleManager setReConnectCount）
 *  - 写指令经 BleProtocol.encodeCommand() 编码
 *  - 通知数据 → hex → BleProtocol.parseFrame() → [events] SharedFlow
 */
class BleConnectionManager(private val context: Context) {

    sealed interface Event {
        data class ScanUpdate(val devices: List<ScanItem>) : Event
        data class ScanFinished(val devices: List<ScanItem>) : Event
        data class ConnectionState(val state: State) : Event
        data class NotifyData(val hex: String, val frame: BleProtocol.Frame?) : Event
        data class WriteResult(val success: Boolean, val hex: String) : Event
        data class Error(val message: String) : Event
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    data class ScanItem(
        val name: String,
        val address: String,
        val rssi: Int,
        val deviceType: BleConstants.DeviceType
    )

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var reconnectAttempts = 0
    private var scanning = false

    val scanResults = mutableMapOf<String, ScanItem>()

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    val connectedDeviceName: String?
        get() = _connectedName
    private var _connectedName: String? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { d ->
                val name = d.name ?: return
                if (!BleConstants.isTargetDevice(name)) return
                scanResults[d.address] = ScanItem(
                    name = name,
                    address = d.address,
                    rssi = result.rssi,
                    deviceType = BleConstants.deviceTypeOf(name)
                )
                _events.tryEmit(Event.ScanUpdate(scanResults.values.toList()))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _events.tryEmit(Event.Error("扫描失败 code=$errorCode"))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = State.DISCONNECTED
                _events.tryEmit(Event.ConnectionState(State.DISCONNECTED))
                scheduleReconnect()
                return
            }
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    _state.value = State.CONNECTED
                    reconnectAttempts = 0
                    _events.tryEmit(Event.ConnectionState(State.CONNECTED))
                    g.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    _state.value = State.DISCONNECTED
                    notifyCharacteristic = null
                    _events.tryEmit(Event.ConnectionState(State.DISCONNECTED))
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _events.tryEmit(Event.Error("服务发现失败 status=$status"))
                return
            }
            val service = g.getService(UUID.fromString(BleConstants.UUID_SERVICE))
            val ch = service?.getCharacteristic(UUID.fromString(BleConstants.UUID_NOTIFY))
            if (ch == null) {
                _events.tryEmit(Event.Error("未找到服务 ${BleConstants.UUID_SERVICE} / 特征 ${BleConstants.UUID_NOTIFY}"))
                return
            }
            notifyCharacteristic = ch
            enableNotify(g, ch)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = ch.value ?: return
            emitNotify(value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            emitNotify(value)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            _events.tryEmit(Event.WriteResult(status == BluetoothGatt.GATT_SUCCESS, ch.value.toHexString()))
        }

        private fun emitNotify(value: ByteArray) {
            val hex = value.toHexString()
            val frame = BleProtocol.parseFrame(hex)
            Log.d(TAG, "onNotify: $hex")
            _events.tryEmit(Event.NotifyData(hex, frame))
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { String.format("%02X", it) }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (adapter?.isEnabled != true) {
            _events.tryEmit(Event.Error("蓝牙未开启"))
            return
        }
        scanResults.clear()
        scanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        adapter.bluetoothLeScanner?.startScan(null, settings, scanCallback)
        _events.tryEmit(Event.ScanUpdate(emptyList()))
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _events.tryEmit(Event.ScanFinished(scanResults.values.toList()))
    }

    fun isScanning() = scanning

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (adapter?.isEnabled != true) {
            _events.tryEmit(Event.Error("蓝牙未开启"))
            return
        }
        stopScan()
        disconnect()
        _state.value = State.CONNECTING
        _events.tryEmit(Event.ConnectionState(State.CONNECTING))
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        _connectedName = device.name
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, true, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(UUID.fromString(BleConstants.UUID_CCCD))
        if (cccd == null) {
            _events.tryEmit(Event.Error("缺少 CCCD 描述符"))
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(cccd)
    }

    /** 发送文本指令（自动经 0D..0D / 1E 封装） */
    @SuppressLint("MissingPermission")
    fun sendCommand(text: String) {
        val ch = notifyCharacteristic?.let {
            gatt?.getService(UUID.fromString(BleConstants.UUID_SERVICE))
                ?.getCharacteristic(UUID.fromString(BleConstants.UUID_WRITE))
        }
        if (ch == null || _state.value != State.CONNECTED) {
            _events.tryEmit(Event.WriteResult(false, ""))
            return
        }
        val data = BleProtocol.encodeCommand(text)
        ch.value = data
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt?.writeCharacteristic(ch)
        Log.d(TAG, "sendCommand: $text -> ${data.toHexString()}")
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        reconnectAttempts = BleConstants.RECONNECT_COUNT + 1 // 手动断开不触发重连
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        notifyCharacteristic = null
        _connectedName = null
        _state.value = State.DISCONNECTED
        _events.tryEmit(Event.ConnectionState(State.DISCONNECTED))
    }

    /** 掉线自动重连：10 次 × 500ms（ScanMainActivity.java:395-398） */
    private fun scheduleReconnect() {
        val address = gatt?.device?.address ?: return
        if (reconnectAttempts >= BleConstants.RECONNECT_COUNT) return
        reconnectAttempts++
        _state.value = State.RECONNECTING
        _events.tryEmit(Event.ConnectionState(State.RECONNECTING))
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            connect(address)
        }, BleConstants.RECONNECT_DELAY_MS)
    }

    fun release() {
        reconnectAttempts = BleConstants.RECONNECT_COUNT + 1
        stopScan()
        disconnect()
    }

    companion object {
        private const val TAG = "BleConnMgr"
    }
}

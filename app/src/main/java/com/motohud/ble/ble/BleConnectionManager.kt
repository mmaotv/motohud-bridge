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
import com.motohud.ble.data.BleDiagnostic
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
        /** CCCD 写入成功：0x1234 通知已打开，可以开始收发（原厂此时发 HELLO 握手） */
        data object NotifyReady : Event
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
    private var notifyReadyEmitted = false

    /** Android BLE 一次只能挂一个写操作，原厂 FastBle 内部有队列，这里等价实现 */
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeBusy = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** 写看门狗：NO_RESPONSE 回调偶发不触发时仍能推进队列，避免整条链路卡死 */
    private val writeWatchdog = Runnable {
        if (writeBusy) {
            Log.w(TAG, "write watchdog fired, releasing busy")
            writeBusy = false
            drainWriteQueue()
        }
    }

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
                    BleDiagnostic.setState("CONNECTED")
                    _events.tryEmit(Event.ConnectionState(State.CONNECTED))
                    g.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    _state.value = State.DISCONNECTED
                    notifyCharacteristic = null
                    BleDiagnostic.setState("DISCONNECTED")
                    _events.tryEmit(Event.ConnectionState(State.DISCONNECTED))
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                BleDiagnostic.log("服务发现失败 status=$status")
                _events.tryEmit(Event.Error("服务发现失败 status=$status"))
                return
            }
            val service = g.getService(UUID.fromString(BleConstants.UUID_SERVICE))
            val ch = service?.getCharacteristic(UUID.fromString(BleConstants.UUID_NOTIFY))
            if (ch == null) {
                BleDiagnostic.log("未找到服务/特征")
                _events.tryEmit(Event.Error("未找到服务 ${BleConstants.UUID_SERVICE} / 特征 ${BleConstants.UUID_NOTIFY}"))
                return
            }
            BleDiagnostic.log("服务发现成功，准备开启通知")
            notifyCharacteristic = ch
            enableNotify(g, ch)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == UUID.fromString(BleConstants.UUID_CCCD)) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "CCCD written, notify ready")
                    BleDiagnostic.setNotifyReady(true)
                    emitNotifyReady()
                } else {
                    BleDiagnostic.log("打开通知失败 status=$status")
                    _events.tryEmit(Event.Error("打开通知失败 status=$status"))
                }
            }
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
            mainHandler.removeCallbacks(writeWatchdog)
            BleDiagnostic.onWriteResult(status == BluetoothGatt.GATT_SUCCESS, ch.value.toHexString())
            _events.tryEmit(Event.WriteResult(status == BluetoothGatt.GATT_SUCCESS, ch.value.toHexString()))
            writeBusy = false
            drainWriteQueue()
        }

        private fun emitNotify(value: ByteArray) {
            val hex = value.toHexString()
            val frame = BleProtocol.parseFrame(hex)
            Log.d(TAG, "onNotify: $hex")
            BleDiagnostic.onNotify(hex)
            _events.tryEmit(Event.NotifyData(hex, frame))
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { String.format("%02X", it) }

    /** 通知就绪：CCCD 写成功后发出；带兜底定时器防止回调未触发时握手卡住 */
    private fun emitNotifyReady() {
        if (notifyReadyEmitted) return
        notifyReadyEmitted = true
        _events.tryEmit(Event.NotifyReady)
    }

    private fun scheduleNotifyReadyFallback() {
        mainHandler.removeCallbacks(notifyReadyFallback)
        mainHandler.postDelayed(notifyReadyFallback, 1500L)
    }

    private val notifyReadyFallback = Runnable {
        if (!notifyReadyEmitted) {
            Log.w(TAG, "CCCD callback missed, emitting NotifyReady by fallback")
            emitNotifyReady()
        }
    }

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
    fun connect(address: String, fromReconnect: Boolean = false) {
        if (adapter?.isEnabled != true) {
            _events.tryEmit(Event.Error("蓝牙未开启"))
            return
        }
        stopScan()
        val attempts = reconnectAttempts
        disconnect()
        reconnectAttempts = if (fromReconnect) attempts else 0
        _state.value = State.CONNECTING
        _events.tryEmit(Event.ConnectionState(State.CONNECTING))
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        _connectedName = device.name
        BleDiagnostic.onNewConnection(device.name)
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, true, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        notifyReadyEmitted = false
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(UUID.fromString(BleConstants.UUID_CCCD))
        if (cccd == null) {
            _events.tryEmit(Event.Error("缺少 CCCD 描述符"))
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        scheduleNotifyReadyFallback()
        g.writeDescriptor(cccd)
    }

    /** 发送文本指令（自动经 0D..0D / 1E 封装，排队串行写出） */
    @SuppressLint("MissingPermission")
    fun sendCommand(text: String) {
        val ch = notifyCharacteristic?.let {
            gatt?.getService(UUID.fromString(BleConstants.UUID_SERVICE))
                ?.getCharacteristic(UUID.fromString(BleConstants.UUID_WRITE))
        }
        if (ch == null || _state.value != State.CONNECTED) {
            _events.tryEmit(Event.WriteResult(false, ""))
            BleDiagnostic.onWriteResult(false, "")
            return
        }
        val data = BleProtocol.encodeCommand(text)
        BleDiagnostic.onSend(text)
        synchronized(writeQueue) {
            writeQueue.addLast(data)
        }
        drainWriteQueue()
        Log.d(TAG, "sendCommand: $text -> ${data.toHexString()}")
    }

    private fun drainWriteQueue() {
        val ch = notifyCharacteristic?.let {
            gatt?.getService(UUID.fromString(BleConstants.UUID_SERVICE))
                ?.getCharacteristic(UUID.fromString(BleConstants.UUID_WRITE))
        }
        if (ch == null) {
            synchronized(writeQueue) { writeQueue.clear() }
            return
        }
        val data = synchronized(writeQueue) {
            if (writeBusy || writeQueue.isEmpty()) null else {
                writeBusy = true
                writeQueue.removeFirst()
            }
        } ?: return
        // 原厂 0x2345 是 WRITE NO RESPONSE（nRF 实测），按特征属性选类型
        ch.writeType = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        ch.value = data
        mainHandler.removeCallbacks(writeWatchdog)
        mainHandler.postDelayed(writeWatchdog, 2000L)
        @Suppress("DEPRECATION") val ok = gatt?.writeCharacteristic(ch) == true
        if (!ok) {
            mainHandler.removeCallbacks(writeWatchdog)
            writeBusy = false
            _events.tryEmit(Event.WriteResult(false, data.toHexString()))
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        reconnectAttempts = BleConstants.RECONNECT_COUNT + 1 // 手动断开不触发重连
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        notifyCharacteristic = null
        synchronized(writeQueue) { writeQueue.clear(); writeBusy = false }
        mainHandler.removeCallbacks(writeWatchdog)
        mainHandler.removeCallbacks(notifyReadyFallback)
        notifyReadyEmitted = false
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
            connect(address, fromReconnect = true)
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

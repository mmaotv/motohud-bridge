package com.motohud.ble.ble

import android.util.Log
import com.motohud.ble.ble.BleConstants.CMD_A
import com.motohud.ble.ble.BleConstants.CMD_B
import com.motohud.ble.ble.BleConstants.CMD_C
import com.motohud.ble.ble.BleConstants.CMD_D
import com.motohud.ble.ble.BleConstants.CMD_E
import com.motohud.ble.ble.BleConstants.CMD_F
import com.motohud.ble.ble.BleConstants.MALFUNCTION_CODE_E
import com.motohud.ble.ble.BleConstants.MALFUNCTION_CODE_F
import com.motohud.ble.ble.BleConstants.WATER_TEMP_MAP
import java.math.BigInteger

/**
 * BLE 协议编解码。
 *
 * 还原自原 App：
 *  - 下行帧解析: ByteParse.parseCMD()  (decompiled/.../ble/ByteParse.java:22-64)
 *  - 上行指令编码: BleDealer.writeData() (decompiled/.../ble/BleDealer.java:61-76)
 *
 * 与原文的差异（均为修复）：
 *  1. CMD_A 位域解析按 8 位补齐，修复 Integer.toBinaryString() 丢前导零导致索引偏移的 bug
 *     （原 App BleCmdA.java:15 / RidingFragment.java:927-939 靠外部补齐，这里内置）
 *  2. 数值解析统一返回可空类型，无效值（fffe 等）显式表达
 */
object BleProtocol {

    private const val TAG = "BleProtocol"

    /** 解析后的下行帧 */
    sealed class Frame {
        abstract val cmd: Int
        abstract val length: Int
        abstract val datas: Array<String>

        /** CMD 1：开关位域（bit7→bit0） */
        data class CmdA(
            override val datas: Array<String>,
            override val length: Int = datas.size,
            override val cmd: Int = CMD_A
        ) : Frame() {
            /** 8 个开关位（修复前导零：低位补齐到 8 位） */
            val switches: List<Int> = padBits8(datas.firstOrNull() ?: "00").map { it - '0' }
        }

        /** CMD 2：实时数据 */
        data class CmdB(
            override val datas: Array<String>,
            override val length: Int = datas.size,
            override val cmd: Int = CMD_B
        ) : Frame() {
            /** 速度 km/h（datas[0]） */
            val speed: Int? = hexOrNull(datas.getOrNull(0))
            /** 转速 RPM（datas[1..2] 大端） */
            val rotatingSpeed: Int? = concatHexOrNull(datas, 1, 2)
            /** 瞬时油耗（datas[3..4]，fffe=无效） */
            val instantaneousFuel: Double? = run {
                val rawHex = (datas.getOrNull(3) ?: "") + (datas.getOrNull(4) ?: "")
                if (rawHex.equals("fffe", true)) null
                else concatHexOrNull(datas, 3, 4)?.div(10.0)
            }
        }

        /** CMD 3：水温/环境温度/油量/电瓶 */
        data class CmdC(
            override val datas: Array<String>,
            override val length: Int = datas.size,
            override val cmd: Int = CMD_C
        ) : Frame() {
            /** 水温 ℃（datas[0] 离散映射，0 表示 <45） */
            val waterTemperature: Int? = WATER_TEMP_MAP[hexOrNull(datas.getOrNull(0))]
            /** 环境温度（datas[1] 符号位 + datas[2] 数值） */
            val roomTemperature: String? = run {
                val sign = hexOrNull(datas.getOrNull(1))
                val value = hexOrNull(datas.getOrNull(2))
                if (sign == null || value == null) null
                else (if (sign > 0) "-" else "+") + value
            }
            /** 油量 %（datas[3] * 100 / 160） */
            val oilQuantity: Int? = hexOrNull(datas.getOrNull(3))?.let { it * 100 / 160 }
            /** 电瓶电压 V（datas[4] / 10） */
            val batteryVoltage: Float? = hexOrNull(datas.getOrNull(4))?.let { it / 10.0f }
        }

        /** CMD 4：里程统计 */
        data class CmdD(
            override val datas: Array<String>,
            override val length: Int = datas.size,
            override val cmd: Int = CMD_D
        ) : Frame() {
            /** 总里程（datas[0..2] 3 字节 hex） */
            val totalMileage: Int? = concatHexOrNull(datas, 0, 3)
            /** 最大速度（datas[3]） */
            val maxSpeed: Int? = hexOrNull(datas.getOrNull(3))
            /** 平均速度（datas[4]） */
            val averageSpeed: Int? = hexOrNull(datas.getOrNull(4))
        }

        /** CMD 5：ECU 故障码（位域 → 索引） */
        data class CmdE(
            override val datas: Array<String>,
            override val length: Int = datas.size,
            override val cmd: Int = CMD_E
        ) : Frame() {
            /** 置位的故障索引（-1 表示无故障） */
            val malfunctionIndex: Int = faultIndex(datas)
            val malfunctionCode: String?
                get() = MALFUNCTION_CODE_E.getOrNull(malfunctionIndex)
        }

        /** CMD 6：OBD 故障码（位域 → 索引） */
        data class CmdF(
            override val datas: Array<String>,
            override val length: Int = datas.size,
            override val cmd: Int = CMD_F
        ) : Frame() {
            val malfunctionIndex: Int = faultIndex(datas)
            val malfunctionCode: String?
                get() = MALFUNCTION_CODE_F.getOrNull(malfunctionIndex)
        }

        /** 未知命令：保留原始数据 */
        data class Unknown(
            override val cmd: Int,
            override val datas: Array<String>,
            override val length: Int = datas.size
        ) : Frame()
    }

    /**
     * 解析下行帧（ByteParse.parseCMD 等价实现）。
     * 帧格式：[0..4) 命令号 4 位 hex | [4..6) 长度 1 字节 hex | [6..] 数据（每 2 字符 1 字节）
     */
    fun parseFrame(hex: String?): Frame? {
        if (hex.isNullOrEmpty()) return null
        return try {
            if (hex.length < 6) return null
            val cmd = hex.substring(0, 4).toInt(16)
            val length = hex.substring(4, 6).toInt(16)
            val body = hex.substring(6)
            val datas = Array(body.length / 2) { body.substring(it * 2, it * 2 + 2) }
            when (cmd) {
                CMD_A -> Frame.CmdA(datas)
                CMD_B -> Frame.CmdB(datas)
                CMD_C -> Frame.CmdC(datas)
                CMD_D -> Frame.CmdD(datas)
                CMD_E -> Frame.CmdE(datas)
                CMD_F -> Frame.CmdF(datas)
                else -> Frame.Unknown(cmd, datas)
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseFrame failed: $hex", e)
            null
        }
    }

    /**
     * 编码上行文本指令（BleDealer.writeData 等价实现）。
     * 规则：每字符转 2 位 hex；':' (0x3A) 替换为 0x1E；首尾包 0x0D。
     */
    fun encodeCommand(text: String): ByteArray {
        val sb = StringBuilder()
        for (ch in text) {
            if (ch == ':') sb.append("1E") else sb.append(toHexString(ch))
        }
        return hexToBytes("0D" + sb.toString() + "0D")
    }

    /** 通知帧字节 → hex 字符串（BleDealer/BaseNaviActivity onCharacteristicChanged 链路） */
    fun bytesToHex(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val sb = StringBuilder()
        for (b in bytes) {
            val h = Integer.toHexString(b.toInt() and 0xFF)
            if (h.length < 2) sb.append('0')
            sb.append(h)
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun toHexString(ch: Char): String {
        val h = Integer.toHexString(ch.code)
        return if (h.length < 2) "0$h" else h
    }

    // ---------- 内部工具 ----------

    private fun hexOrNull(s: String?): Int? = try {
        s?.toInt(16)
    } catch (_: Exception) {
        null
    }

    private fun concatHexOrNull(datas: Array<String>, from: Int, count: Int): Int? {
        if (from + count > datas.size) return null
        val sb = StringBuilder()
        for (i in from until from + count) sb.append(datas[i])
        return try {
            BigInteger(sb.toString(), 16).toInt()
        } catch (_: Exception) {
            null
        }
    }

    /** 故障码位域 → 索引（BleCmdE/F.getMalfunctionIndex 等价，取最后一个置位） */
    private fun faultIndex(datas: Array<String>): Int {
        if (datas.size < 2) return -1
        val raw = try {
            (datas[0] + datas[1]).toInt(16)
        } catch (_: Exception) {
            return -1
        }
        val bits = Integer.toBinaryString(raw)
        var index = -1
        for (i in bits.indices) {
            if (bits[i] == '1') index = i
        }
        return index
    }

    /** 补齐到 8 位二进制（修复原 App 前导零丢失 bug） */
    private fun padBits8(hexByte: String): String {
        val v = hexByte.toInt(16)
        return String.format("%8s", Integer.toBinaryString(v)).replace(' ', '0')
    }
}

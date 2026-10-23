package com.motohud.ble.data

import com.motohud.ble.ble.BleConstants

/**
 * 故障码知识库。
 *
 * 码表来源（原 App 反编译）：
 *  - ECU 码 14 个：model/BleCmdE.java:5
 *  - OBD 码 49 个：model/BleCmdF.java:5
 * 描述为按标准 OBD-II/ECU 语义补全的中文说明（原 App 仅存码值，描述由服务端下发，
 * 厂家服务器已不可用，此处本地化补全）。
 */
object FaultRepository {

    data class FaultItem(
        val code: String,
        val table: String,      // "ECU" 或 "OBD"
        val name: String,
        val cause: String,
        val suggestion: String
    )

    /** CMD_E：ECU 故障码（BleCmdE） */
    val ECU_FAULTS: List<FaultItem> = listOf(
        FaultItem("0x5055", "ECU", "怠速控制系统故障", "怠速阀卡滞、进气漏气或节气门脏污", "清洁节气门与怠速阀，检查进气密封"),
        FaultItem("0x5019", "ECU", "曲轴位置传感器信号异常", "传感器线路接触不良或信号盘脏污", "检查传感器插接件与线束，清洁信号盘"),
        FaultItem("0x5017", "ECU", "凸轮轴位置传感器信号异常", "传感器线路故障或正时信号偏移", "检查传感器及线路，核对正时"),
        FaultItem("0x5013", "ECU", "氧传感器信号异常", "传感器老化、铅中毒或线路故障", "检查氧传感器及线束"),
        FaultItem("0x5018", "ECU", "进气温度传感器信号异常", "传感器故障或线束短路/断路", "检查传感器电阻与线束"),
        FaultItem("0x5014", "ECU", "发动机冷却液温度传感器异常", "传感器故障或线路接触不良", "检查传感器及插接件"),
        FaultItem("0x5053", "ECU", "燃油蒸发排放系统故障", "碳罐电磁阀卡滞或管路泄漏", "检查碳罐电磁阀及管路"),
        FaultItem("0x5052", "ECU", "系统电压过高", "稳压器故障或蓄电池异常", "检查稳压器与蓄电池"),
        FaultItem("0x5035", "ECU", "油门位置传感器信号异常", "传感器脏污、磨损或线路故障", "检查油门位置传感器"),
        FaultItem("0x5043", "ECU", "喷油器驱动电路故障", "喷油器线圈短路/断路或驱动级损坏", "检查喷油器线圈电阻与线束"),
        FaultItem("0x5045", "ECU", "燃油泵继电器控制故障", "继电器触点烧蚀或驱动电路异常", "检查继电器与控制电路"),
        FaultItem("0x5042", "ECU", "点火线圈初级电路故障", "点火线圈损坏或线路接触不良", "检查点火线圈及线束"),
        FaultItem("0x5044", "ECU", "点火线圈次级电路故障", "高压线/火花塞击穿或线圈老化", "检查高压线与火花塞"),
        FaultItem("0x5025", "ECU", "发电机充电指示灯电路故障", "充电指示灯线路或发电机故障", "检查充电指示电路")
    )

    /** CMD_F：OBD 故障码（BleCmdF，标准 DTC） */
    val OBD_FAULTS: List<FaultItem> = listOf(
        FaultItem("P0503", "OBD", "车速传感器信号间断/不稳定", "传感器线路接触不良或信号盘损坏", "检查车速传感器及线束"),
        FaultItem("C0083", "OBD", "ABS 通信故障", "ABS 模块线路或供电异常", "检查 ABS 模块连接"),
        FaultItem("C0085", "OBD", "ABS 警告灯指示电路故障", "指示灯线路故障", "检查仪表指示灯线路"),
        FaultItem("P0105", "OBD", "进气压力传感器电路故障", "传感器或线路短路/断路", "检查传感器及线束"),
        FaultItem("P0110", "OBD", "进气温度传感器电路故障", "传感器或线路异常", "检查传感器及线束"),
        FaultItem("P0115", "OBD", "冷却液温度传感器电路故障", "传感器或线路异常", "检查传感器及线束"),
        FaultItem("P0120", "OBD", "节气门位置传感器电路故障", "传感器脏污或线路异常", "检查节气门位置传感器"),
        FaultItem("P0130", "OBD", "氧传感器电路故障（1 缸）", "传感器老化或加热器故障", "检查氧传感器及加热电路"),
        FaultItem("P0135", "OBD", "氧传感器加热器电路故障（1 缸）", "加热器线路断路/短路", "检查加热器电路"),
        FaultItem("P0150", "OBD", "氧传感器电路故障（2 缸）", "传感器老化或线路故障", "检查氧传感器及线束"),
        FaultItem("P0155", "OBD", "氧传感器加热器电路故障（2 缸）", "加热器线路异常", "检查加热器电路"),
        FaultItem("P0201", "OBD", "1 缸喷油器电路故障", "喷油器线圈或驱动电路异常", "检查喷油器及线束"),
        FaultItem("P0202", "OBD", "2 缸喷油器电路故障", "喷油器线圈或驱动电路异常", "检查喷油器及线束"),
        FaultItem("P0217", "OBD", "发动机冷却液温度过高", "散热系统故障、水温传感器误报", "检查散热系统与水温传感器"),
        FaultItem("P0230", "OBD", "燃油泵继电器电路故障", "继电器或控制电路异常", "检查继电器及控制电路"),
        FaultItem("P0335", "OBD", "曲轴位置传感器电路故障", "传感器或线路异常", "检查传感器及线束"),
        FaultItem("P0336", "OBD", "曲轴位置传感器信号范围/性能故障", "信号盘缺齿或传感器安装间隙不当", "检查信号盘与传感器"),
        FaultItem("P0351", "OBD", "点火线圈 A 初级/次级电路故障", "点火线圈或高压线异常", "检查点火线圈与高压线"),
        FaultItem("P0352", "OBD", "点火线圈 B 初级/次级电路故障", "点火线圈或高压线异常", "检查点火线圈与高压线"),
        FaultItem("P0410", "OBD", "二次空气喷射系统故障", "喷射泵、管路或电磁阀异常", "检查二次空气喷射系统"),
        FaultItem("P0480", "OBD", "冷却风扇继电器/控制电路故障", "继电器或风扇电机异常", "检查风扇继电器与电机"),
        FaultItem("P0500", "OBD", "车速传感器电路故障", "传感器或线路异常", "检查传感器及线束"),
        FaultItem("P0501", "OBD", "车速传感器信号范围/性能故障", "信号干扰或传感器老化", "检查传感器"),
        FaultItem("P0505", "OBD", "怠速控制系统故障", "怠速阀卡滞或进气漏气", "清洁怠速阀，检查进气密封"),
        FaultItem("P0512", "OBD", "起动机信号电路故障", "起动机开关或线路异常", "检查起动机信号电路"),
        FaultItem("P0560", "OBD", "系统电压异常", "蓄电池、发电机或稳压器故障", "检查充电系统"),
        FaultItem("P0601", "OBD", "控制模块只读存储器错误", "ECU 内部存储器故障", "联系客服处理"),
        FaultItem("P0604", "OBD", "控制模块随机存储器错误", "ECU 内部 RAM 故障", "联系客服处理"),
        FaultItem("P0605", "OBD", "控制模块只读存储器校验和错误", "ECU 程序存储器故障", "联系客服处理"),
        FaultItem("P0606", "OBD", "控制模块处理器故障", "ECU 处理器异常", "联系客服处理"),
        FaultItem("P0620_PIN2", "OBD", "发电机调节器控制电路故障", "调节器线路异常", "检查发电机调节器"),
        FaultItem("P0620_PIN31", "OBD", "发电机磁场控制电路故障", "磁场控制线路异常", "检查发电机磁场电路"),
        FaultItem("P0650", "OBD", "故障指示灯（MIL）控制电路故障", "MIL 指示灯线路异常", "检查 MIL 控制电路"),
        FaultItem("P0655", "OBD", "发动机过热警告灯控制电路故障", "警告灯线路异常", "检查过热警告灯线路"),
        FaultItem("P0A0F", "OBD", "发动机未能启动", "供油/点火/压缩系统故障", "检查供油、点火与压缩系统"),
        FaultItem("P1300", "OBD", "点火系统缺火（综合）", "火花塞、点火线圈或喷油器异常", "检查火花塞与点火线圈"),
        FaultItem("P1310", "OBD", "检测到缺火", "点火或供油不稳定", "检查点火与供油系统"),
        FaultItem("P1536", "OBD", "燃油油位传感器电路故障", "油位传感器或线路异常", "检查油位传感器"),
        FaultItem("P1607", "OBD", "ECU 内部电路故障", "ECU 硬件异常", "联系客服处理"),
        FaultItem("P1800", "OBD", "离合器开关电路故障", "离合器开关或线路异常", "检查离合器开关"),
        FaultItem("P2158", "OBD", "车速传感器信号异常", "传感器或线路干扰", "检查传感器及屏蔽"),
        FaultItem("P2600", "OBD", "发动机冷却液泵控制电路故障", "冷却液泵或控制线路异常", "检查冷却液泵电路"),
        FaultItem("U0001", "OBD", "高速 CAN 通信总线故障", "CAN 总线短路/断路", "检查 CAN 总线线束"),
        FaultItem("U0002", "OBD", "低速 CAN 通信总线故障", "CAN 总线线束故障", "检查 CAN 总线线束"),
        FaultItem("U0121", "OBD", "与 ABS 控制模块通信丢失", "ABS 模块供电或总线故障", "检查 ABS 模块连接"),
        FaultItem("U0122", "OBD", "与变速箱控制模块通信丢失", "TCU 供电或总线故障", "检查 TCU 连接"),
        FaultItem("U0128", "OBD", "与仪表板控制模块通信丢失", "仪表模块供电或总线故障", "检查仪表模块连接"),
        FaultItem("U0140", "OBD", "与车身控制模块通信丢失", "BCM 供电或总线故障", "检查 BCM 连接"),
        FaultItem("U0426", "OBD", "接收到的数据无效（ABS）", "总线干扰或模块故障", "检查总线与模块"),
        FaultItem("U0486", "OBD", "接收到的数据无效（仪表）", "总线干扰或模块故障", "检查总线与模块")
    )

    val ALL: List<FaultItem> = ECU_FAULTS + OBD_FAULTS

    fun byCode(code: String): FaultItem? = ALL.firstOrNull { it.code.equals(code, true) }

    /** 由 CMD_E / CMD_F 位域索引查故障项 */
    fun byIndex(cmd: Int, index: Int): FaultItem? {
        if (index < 0) return null
        return when (cmd) {
            BleConstants.CMD_E -> ECU_FAULTS.getOrNull(index)
            BleConstants.CMD_F -> OBD_FAULTS.getOrNull(index)
            else -> null
        }
    }
}

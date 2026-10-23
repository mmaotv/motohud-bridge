package com.motohud.ble.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * 本地搜索引擎 —— 复刻原 App（com.piaggio.motor）搜索架构的本地版。
 *
 * 原架构（服务端版）见逆向分析报告第 2 章：
 *  - SearchAllFragment.searchInfo(): content/lableId/type + page/size(10)
 *  - SearchBaseFragment: 500ms 防连点、page==1 清空重填、size==pageSize 才 page++
 *  - SearchKeyDao(SEARCH_KEY 表): 历史持久化、按用户隔离、支持清空
 *
 * 本实现将其完整移植到本地：
 *  - 输入防抖（原 App POI 搜索无防抖导致一字符一请求，此处修正为 300ms）
 *  - 分页语义与原文一致（page/size=10，不足一页即无更多）
 *  - 历史写入 Room（对应原 greenDAO SEARCH_KEY 表）
 */
class SearchEngine(private val dao: SearchHistoryDao) {

    enum class Scope(val key: String, val label: String) {
        FAULT("fault", "故障码"),
        COMMAND("command", "指令协议"),
        GLOSSARY("glossary", "词条")
    }

    /** 搜索结果项 */
    data class Item(
        val id: String,
        val scope: Scope,
        val title: String,
        val subtitle: String,
        val detail: String
    )

    // ---------- 搜索语料 ----------

    /** 指令协议手册（从逆向报告第 3 章指令集生成） */
    private val commandCorpus: List<Item> = listOf(
        Item("cmd_hello", Scope.COMMAND, "HELLO :A:<品牌>:", "握手", "BLE 控制器初始化时发送，公告手机品牌（Build.BRAND）"),
        Item("cmd_msg", Scope.COMMAND, "MSG :<类型>:<计数>:", "通知转发", "手机通知/来电推送到头盔；SR 设备码表 WECHAT/LINE/SMS/CALL，BYQ 设备码表 WA/IG/LI/TE/WC/SM/MC"),
        Item("cmd_na", Scope.COMMAND, "NA :<图标>:", "BYQ 导航转向", "高德转向图标类型，0~19，小于 10 补零"),
        Item("cmd_re", Scope.COMMAND, "RE :<米>:", "BYQ 全程剩余", "路径全程剩余距离（米）"),
        Item("cmd_ne", Scope.COMMAND, "NE :<米>:", "BYQ 下段剩余", "当前路段剩余距离（米）"),
        Item("cmd_rem", Scope.COMMAND, "REM :<米>::", "SR 全程剩余", "路径全程剩余距离（米），SR 设备双冒号后缀"),
        Item("cmd_next", Scope.COMMAND, "NEXT :<米>::", "SR 下段剩余", "当前路段剩余距离（米）"),
        Item("cmd_navi", Scope.COMMAND, "NAVI :<图标>::", "SR 导航转向", "高德转向图标类型，0~16"),
        Item("cmd_finish", Scope.COMMAND, "FINISH :::", "到达终点", "到达目的地时发送，两套设备通用"),
        Item("frame", Scope.COMMAND, "下行帧格式", "0001 01 FF", "[0..4)命令号4位hex [4..6)长度 [6..]数据每2字符1字节；命令1~6对应开关/实时/水温/里程/ECU/OBD"),
        Item("encode", Scope.COMMAND, "上行编码规则", "0D..0D / 1E", "每字符转2位hex；':'替换为0x1E；首尾包0x0D(CR)"),
        Item("uuid", Scope.COMMAND, "GATT UUID", "3719/1234/2345", "服务 00003719-0000-1000-8000-00805f9b34fb；通知 00001234；写入 00002345")
    )

    /** 词条语料（从原 App 提取的蓝牙相关文案） */
    private val glossaryCorpus: List<Item> = listOf(
        Item("g_ble", Scope.GLOSSARY, "蓝牙连接", "原 App 文案", "蓝牙连接（ble_connection / str_ble_connection）"),
        Item("g_open", Scope.GLOSSARY, "请先打开蓝牙", "原 App 文案", "please_open_blue"),
        Item("g_none", Scope.GLOSSARY, "无可连接的蓝牙设备", "原 App 文案", "str_no_ble"),
        Item("g_success", Scope.GLOSSARY, "连接成功", "原 App 文案", "connect_success"),
        Item("g_fail", Scope.GLOSSARY, "连接失败", "原 App 文案", "connect_fail"),
        Item("g_exam", Scope.GLOSSARY, "车辆体检", "原 App 文案", "str_examination：ABS/OBD/电瓶电量/前轮胎压/后轮胎压/燃油/机油压力/保养/主控设备"),
        Item("g_speed", Scope.GLOSSARY, "瞬时油耗", "原 App 文案", "str_fuel_consumption，单位 km/L"),
        Item("g_mileage", Scope.GLOSSARY, "智能设备总里程", "原 App 文案", "str_history_device_mileage"),
        Item("g_max300", Scope.GLOSSARY, "SR Max300", "legacy 设备", "15 位名称 860745020 开头，显示为 SR Max300_XXXXXX"),
        Item("g_byq", Scope.GLOSSARY, "BYQ 设备", "FastBLE 栈", "名称前缀 BYQ 或 SR 的目标设备（大小写不敏感）")
    )

    private val allCorpus: List<Item>
        get() = FaultRepository.ALL.map {
            Item(it.code, Scope.FAULT, it.code, "${it.table} 故障码", "${it.name}｜可能原因：${it.cause}｜建议：${it.suggestion}")
        } + commandCorpus + glossaryCorpus

    // ---------- 搜索（防抖 + 分页，复刻原 page/size 语义） ----------

    /**
     * 执行搜索。debounceMs 内仅取最后一次调用（原 App 输入即搜，此处优化为 300ms）。
     * 分页语义复刻 SearchAllFragment：page=1 重填，结果满 size 才允许 page++。
     */
    fun search(keyword: String, scope: Scope?, debounceMs: Long = 300): Flow<Page> = flow {
        if (keyword.isBlank()) {
            emit(Page(emptyList(), page = 1, size = PAGE_SIZE, hasMore = false))
            return@flow
        }
        delay(debounceMs)
        val kw = keyword.trim()
        val matched = allCorpus.filter { item ->
            (scope == null || item.scope == scope) &&
                (item.title.contains(kw, true) ||
                    item.subtitle.contains(kw, true) ||
                    item.detail.contains(kw, true))
        }
        emit(Page(matched.take(PAGE_SIZE), page = 1, size = PAGE_SIZE, hasMore = matched.size > PAGE_SIZE))
    }.flowOn(Dispatchers.Default)

    /** 翻页（复刻原 loadMore：page++ 后取下一页） */
    fun loadMore(keyword: String, scope: Scope?, page: Int): Flow<Page> = flow {
        val kw = keyword.trim()
        val matched = allCorpus.filter { item ->
            (scope == null || item.scope == scope) &&
                (item.title.contains(kw, true) ||
                    item.subtitle.contains(kw, true) ||
                    item.detail.contains(kw, true))
        }
        val from = (page - 1) * PAGE_SIZE
        val chunk = matched.drop(from).take(PAGE_SIZE)
        emit(Page(chunk, page, PAGE_SIZE, hasMore = from + PAGE_SIZE < matched.size))
    }.flowOn(Dispatchers.Default)

    data class Page(val items: List<Item>, val page: Int, val size: Int, val hasMore: Boolean)

    // ---------- 历史（对应原 SEARCH_KEY 表） ----------

    suspend fun saveHistory(keyword: String, scope: Scope) {
        if (keyword.isBlank()) return
        if (dao.countOf(keyword, scope.key) > 0) dao.delete(keyword, scope.key)
        dao.insert(SearchHistoryEntity(keyword = keyword.trim(), scope = scope.key))
    }

    suspend fun recentHistory(limit: Int = 20): List<SearchHistoryEntity> = dao.recent(limit)

    suspend fun clearHistory() = dao.clear()

    companion object {
        const val PAGE_SIZE = 10 // 与原 App size=10 一致（SearchBaseFragment.java:64）
    }
}

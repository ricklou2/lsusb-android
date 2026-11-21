package com.ricklou.lsusb

/**
 * 把 lsusb 的纯文本输出整理成带框线的表格（等宽字体用 ASCII 边框，不会错位）。
 *
 * 原则：**一个字段都不丢**。lsusb -v 里所有「描述符块」（Device / Configuration /
 * Interface / Endpoint / HID / Hub / BOS / 各种 Capability …）全部按原始层级解析，
 * 每块各自一张表，键值原样保留；解析不出来的行也作为备注进表。
 */

/** 表格里用到的文字（由 Activity 按当前语言注入） */
data class TableLabels(
    val pos: String = "位置",
    val vidpid: String = "VID:PID",
    val product: String = "厂商 / 产品",
    val field: String = "字段",
    val value: String = "值",
    val level: String = "层",
    val location: String = "位置",
    val device: String = "设备",
    val iface: String = "接口",
    val cls: String = "类",
    val driver: String = "驱动",
    val speed: String = "速度",
    val ep: String = "端点",
    val dir: String = "方向",
    val type: String = "类型",
    val packet: String = "包大小",
    val interval: String = "间隔",
    val secDevice: String = "设备描述符",
    val secConfig: String = "配置描述符",
    val secInterface: String = "接口描述符",
    val secEndpoint: String = "端点描述符",
    val secHid: String = "HID 描述符",
    val secHub: String = "Hub 描述符",
    val secBos: String = "BOS 描述符",
    val secCap: String = "能力描述符",
    val secIad: String = "接口关联描述符",
    val secPort: String = "端口状态"
)

object LsusbTable {

    var labels: TableLabels = TableLabels()

    private val L get() = labels

    private val DEV_RE = Regex("Bus (\\d+) Device (\\d+): ID ([0-9a-fA-F]{4}):([0-9a-fA-F]{4})(.*)")
    private val TREE_ROOT = Regex("^/:\\s+Bus (\\d+)\\.Port (\\d+): Dev (\\d+), Class=([^,]+), Driver=([^,]+)(?:, (\\S+))?$")
    private val TREE_CHILD = Regex("^\\s*\\|__ Port (\\d+): Dev (\\d+)(?:, If (\\d+))?, Class=([^,]+), Driver=([^,]+)(?:, (\\S+))?$")

    // ---------- 显示宽度 / 对齐 ----------

    private fun w(s: String): Int = s.fold(0) { acc, c ->
        val cp = c.code
        acc + if (cp in 0x1100..0x115F || cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7A3 ||
            cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 ||
            cp in 0xFFE0..0xFFE6 || cp >= 0x20000
        ) 2 else 1
    }

    private fun pad(s: String, width: Int): String {
        val d = width - w(s)
        return if (d <= 0) s else s + " ".repeat(d)
    }

    private fun clip(s: String, max: Int): String {
        if (max <= 1 || w(s) <= max) return s
        var acc = 0
        val sb = StringBuilder()
        for (c in s) {
            val cw = if (w(c.toString()) == 2) 2 else 1
            if (acc + cw > max - 1) break
            sb.append(c); acc += cw
        }
        return sb.toString() + "…"
    }

    private fun widths(rows: List<List<String>>, caps: IntArray? = null): IntArray {
        val n = rows.maxOfOrNull { it.size } ?: 0
        val ws = IntArray(n)
        for (r in rows) for (i in r.indices) {
            val cap = caps?.getOrNull(i) ?: Int.MAX_VALUE
            ws[i] = maxOf(ws[i], minOf(w(r[i]), cap))
        }
        return ws
    }

    private fun boxed(
        header: List<String>?,
        rows: List<List<String>>,
        caps: IntArray? = null
    ): String {
        val all = if (header != null) rows.toMutableList().also { it.add(0, header) } else rows.toMutableList()
        val ws = widths(all, caps)
        val top = "+" + ws.joinToString("+") { "-".repeat(it + 2) } + "+"
        val mid = "+" + ws.joinToString("+") { "=".repeat(it + 2) } + "+"

        fun line(cells: List<String>): String {
            val sb = StringBuilder("|")
            for (i in ws.indices) {
                val raw = cells.getOrElse(i) { "" }
                sb.append(' ').append(pad(clip(raw, ws[i]), ws[i])).append(' ').append('|')
            }
            return sb.toString()
        }

        val sb = StringBuilder(top).append('\n')
        if (header != null) {
            sb.append(line(header)).append('\n').append(mid).append('\n')
        }
        rows.forEach { r -> sb.append(line(r)).append('\n') }
        sb.append(top)
        return sb.toString()
    }

    private fun indent(text: String, prefix: String = "  "): String =
        text.lines().joinToString("\n") { if (it.isEmpty()) it else prefix + it }

    // ---------- 简要 ----------

    data class BriefRow(val bus: String, val dev: String, val vid: String, val pid: String, val name: String)

    fun parseBrief(raw: String): List<BriefRow> =
        raw.lines().mapNotNull { line ->
            val m = DEV_RE.find(line) ?: return@mapNotNull null
            BriefRow(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4], m.groupValues[5].trim())
        }

    fun renderBrief(raw: String): String {
        val rows = parseBrief(raw)
        if (rows.isEmpty()) return raw.trim().ifEmpty { "—" }
        val cells = rows.map { listOf("${it.bus}/${it.dev}", "${it.vid}:${it.pid}", it.name) }
        return boxed(listOf(L.pos, L.vidpid, L.product), cells, intArrayOf(8, 9, 40))
    }

    fun briefAligned(raw: String): Pair<String, List<String>>? {
        val rows = parseBrief(raw)
        if (rows.isEmpty()) return null
        val head = listOf(L.pos, L.vidpid, L.product)
        val cells = rows.map { listOf("${it.bus}/${it.dev}", "${it.vid}:${it.pid}", it.name) }
        return alignedTable(head, cells, intArrayOf(8, 9, 34))
    }

    fun alignedTable(header: List<String>, rows: List<List<String>>, caps: IntArray? = null): Pair<String, List<String>> {
        val ws = widths(rows.toMutableList().also { it.add(0, header) }, caps)
        fun line(c: List<String>): String = c.mapIndexed { i, s ->
            if (i == c.lastIndex) s else pad(clip(s, ws[i]), ws[i])
        }.joinToString("   ")
        return line(header) to rows.map { line(it) }
    }

    // ---------- 树形 ----------

    fun renderTree(raw: String): String {
        val rows = mutableListOf<List<String>>()
        for (line in raw.lines()) {
            val r = TREE_ROOT.find(line)
            if (r != null) {
                rows += listOf(
                    "0", "Bus ${r.groupValues[1]}.Port ${r.groupValues[2]}",
                    "Dev ${r.groupValues[3]}", "-",
                    r.groupValues[4].trim(), r.groupValues[5].trim(),
                    r.groupValues[6].ifEmpty { "-" }
                )
                continue
            }
            val c = TREE_CHILD.find(line) ?: continue
            val level = line.indexOfFirst { it != ' ' }.let { if (it < 0) 0 else it } / 4
            rows += listOf(
                level.toString(), "Port ${c.groupValues[1]}",
                "Dev ${c.groupValues[2]}", c.groupValues[3].ifEmpty { "-" },
                c.groupValues[4].trim(), c.groupValues[5].trim(),
                c.groupValues[6].ifEmpty { "-" }
            )
        }
        if (rows.isEmpty()) return raw.trim()
        return boxed(
            listOf(L.level, L.location, L.device, L.iface, L.cls, L.driver, L.speed),
            rows,
            intArrayOf(2, 17, 8, 4, 22, 14, 5)
        )
    }

    // ---------- 详细（全字段） ----------

    private class Sec(val title: String, val indent: Int) {
        val fields = mutableListOf<Pair<String, String>>()
        val kids = mutableListOf<Sec>()
    }

    private class Dev(val bus: String, val dev: String, val vid: String, val pid: String, val name: String) {
        val sections = mutableListOf<Sec>()
        val notes = mutableListOf<String>()
    }

    private fun parseVerbose(raw: String): List<Dev> {
        val devices = mutableListOf<Dev>()
        var dev: Dev? = null
        val stack = mutableListOf<Sec>()

        for (line0 in raw.lines()) {
            val t = line0.trim()
            if (t.isEmpty()) continue
            val ind = line0.indexOfFirst { it != ' ' }.let { if (it < 0) 0 else it }

            val m = DEV_RE.find(t)
            if (m != null && ind == 0) {
                dev = Dev(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4], m.groupValues[5].trim())
                devices += dev
                stack.clear()
                continue
            }
            val d = dev ?: Dev("", "", "", "", "").also { dev = it; devices += it }

            // 描述符块 / 能力块开头：`Xxx Descriptor:` / `Xxx Capability:` / `Interface Association:`
            if (t.endsWith(":") && !t.contains("  ")) {
                val title = t.dropLast(1).trim()
                while (stack.isNotEmpty() && stack.last().indent >= ind) stack.removeAt(stack.size - 1)
                val sec = Sec(title, ind)
                val parent = stack.lastOrNull()
                if (parent != null) parent.kids += sec else d.sections += sec
                stack += sec
                continue
            }

            while (stack.isNotEmpty() && ind <= stack.last().indent) stack.removeAt(stack.size - 1)
            val cur = stack.lastOrNull()
            val cut = t.indexOf("  ")
            if (cur == null) {
                d.notes += t
            } else if (cut > 0) {
                val key = t.substring(0, cut).trim()
                val value = t.substring(cut).trim().replace(Regex("\\s{2,}"), " ")
                cur.fields += key to value
            } else {
                // 长键名会被 lsusb 压成单空格，例如 `wHubCharacteristic 0x00e9`；
                // 只认「纯标识符 + 0x/数字值」，避免把说明文字误当键值
                val m2 = Regex("^((?:b|i|w|n|id)[A-Za-z0-9_]*)\\s+(0[xX][0-9a-fA-F]+\\b.*|\\d.*)$").find(t)
                if (m2 != null) cur.fields += m2.groupValues[1] to m2.groupValues[2]
                else cur.fields += "" to t     // 括号说明、警告等非键值行
            }
        }
        return devices
    }

    private fun secTitle(title: String): String {
        val t = title.lowercase()
        return when {
            t == "device descriptor" -> L.secDevice
            t == "configuration descriptor" -> L.secConfig
            t == "interface descriptor" -> L.secInterface
            t == "endpoint descriptor" -> L.secEndpoint
            t.startsWith("hid device descriptor") -> L.secHid
            t.startsWith("hub descriptor") -> L.secHub
            t == "binary object store descriptor" || t == "bos descriptor" -> L.secBos
            t == "interface association" -> L.secIad
            t.endsWith("capability") -> L.secCap + " · " + title
            t == "hub port status" -> L.secPort
            else -> title
        }
    }

    private fun renderSection(sb: StringBuilder, sec: Sec, depth: Int) {
        val ind = "  ".repeat(depth)
        sb.append(ind).append(secTitle(sec.title)).append('\n')
        if (sec.fields.isNotEmpty()) {
            val rows = sec.fields.map { listOf(it.first.ifEmpty { "·" }, it.second) }
            sb.append(indent(boxed(listOf(L.field, L.value), rows, intArrayOf(28, 46)), ind)).append('\n')
        }
        for (k in sec.kids) {
            if (k !== sec.kids.first()) sb.append('\n')
            renderSection(sb, k, depth + 1)
        }
    }

    fun renderVerbose(raw: String, header: String? = null): String {
        val devices = parseVerbose(raw)
        if (devices.isEmpty()) return raw.trim()

        val sb = StringBuilder()
        devices.forEachIndexed { di, d ->
            if (di > 0) sb.append('\n')
            val title = header ?: buildString {
                if (d.bus.isNotEmpty()) {
                    append(L.device).append(' ').append(d.bus).append('/').append(d.dev)
                        .append("   ").append(d.vid).append(':').append(d.pid)
                    if (d.name.isNotEmpty()) append("   ").append(d.name)
                }
            }
            if (title.isNotEmpty()) sb.append(title).append('\n')
            if (d.notes.isNotEmpty()) {
                sb.append(boxed(null, d.notes.map { listOf(it) }, intArrayOf(70))).append('\n')
            }
            d.sections.forEachIndexed { si, sec ->
                if (si > 0) sb.append('\n')
                renderSection(sb, sec, 0)
            }
        }
        return sb.toString().trimEnd()
    }
}

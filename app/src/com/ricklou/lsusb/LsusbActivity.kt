package com.ricklou.lsusb

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.io.File

/**
 * lsusb 图形界面。
 *
 * - 4 个静态 lsusb（arm64-v8a / armeabi-v7a / x86 / x86_64）作为 liblsusb.so 打进 APK，
 *   安装时由系统按设备 ABI 解压到 nativeLibraryDir，可直接执行（避开 Android 10+ 的 W^X 限制）。
 * - 所有命令通过 `su -c` 异步执行，输出实时回填，执行前清空缓冲区。
 */
class LsusbActivity : Activity() {

    companion object {
        private const val PREFS = "lsusb_gui"
        private const val KEY_LANG = "lang"
        private const val LANG_SYSTEM = "system"
        private const val KEY_MODE = "view_mode"
        private const val MODE_TABLE = "table"
        private const val MODE_RAW = "raw"
    }

    private val ui = Handler(Looper.getMainLooper())

    // Bus 003 Device 002: ID 046d:c52b Logitech, Inc. Unifying Receiver
    private val DEV_RE = Regex("Bus (\\d+) Device (\\d+): ID ([0-9a-fA-F]{4}):([0-9a-fA-F]{4})(.*)")

    private lateinit var scroll: ScrollView
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var buttons: List<Button>

    private var binPath: String? = null
    private var suPath: String? = null
    private var rooted = false
    private var running = false

    /**
     * 语言：手动选择（简体 / English / 繁体）走这里换 Configuration；
     * 「跟随系统」时按系统语言映射，zh-SG / zh-Hans 一律算简体中文。
     */
    override fun attachBaseContext(newBase: Context) {
        val pref = newBase.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, LANG_SYSTEM)
        val loc = resolveLocale(newBase, pref)
        val cfg = Configuration(newBase.resources.configuration)
        cfg.setLocale(loc)
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }

    private fun resolveLocale(base: Context, pref: String?): Locale = when (pref) {
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "tw" -> Locale.TRADITIONAL_CHINESE
        "en" -> Locale.ENGLISH
        else -> {
            val sys = base.resources.configuration.locales[0]
            if (sys.language == "zh") {
                // 台湾/香港/澳门 或 明确 Hant 才算繁体；zh-SG、zh-Hans、zh-CN 都按简体
                if (sys.country == "TW" || sys.country == "HK" || sys.country == "MO" || sys.script == "Hant")
                    Locale.TRADITIONAL_CHINESE else Locale.SIMPLIFIED_CHINESE
            } else {
                Locale.ENGLISH
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scroll = findViewById(R.id.scroll)
        output = findViewById(R.id.output)
        status = findViewById(R.id.status)
        buttons = listOf(
            findViewById(R.id.btn_brief),
            findViewById(R.id.btn_verbose),
            findViewById(R.id.btn_tree),
            findViewById(R.id.btn_select)
        )
        buttons.forEach { it.isEnabled = false }

        findViewById<Button>(R.id.btn_brief).setOnClickListener { execBrief(inTable) }
        findViewById<Button>(R.id.btn_brief).setOnLongClickListener {
            execBrief(!inTable); toast(viewModeToast(!inTable)); true
        }
        findViewById<Button>(R.id.btn_verbose).setOnClickListener { execVerbose(inTable) }
        findViewById<Button>(R.id.btn_verbose).setOnLongClickListener {
            execVerbose(!inTable); toast(viewModeToast(!inTable)); true
        }
        findViewById<Button>(R.id.btn_tree).setOnClickListener { execTree(inTable) }
        findViewById<Button>(R.id.btn_tree).setOnLongClickListener {
            execTree(!inTable); toast(viewModeToast(!inTable)); true
        }
        findViewById<Button>(R.id.btn_select).setOnClickListener { showSelectDialog() }

        findViewById<ImageButton>(R.id.btn_lang).setOnClickListener { showLangMenu() }
        updateViewButton()
        findViewById<ImageButton>(R.id.btn_copy).setOnClickListener { copyOutput() }
        findViewById<ImageButton>(R.id.btn_view).setOnClickListener {
            val toRaw = inTable
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_MODE, if (toRaw) MODE_RAW else MODE_TABLE).apply()
            updateViewButton()
            toast(viewModeToast(!toRaw))
        }

        applyLabels()
        resolveBinary()
        applyEdgeToEdge()
        checkRoot()
    }

    /**
     * Android 15/16 对 targetSdk >= 35 的应用强制 edge-to-edge，
     * 状态栏/导航栏/输入法不再自动避让，需自己吃 inset。
     * Android 7 - 14 上保持系统默认的 fitSystemWindows 行为。
     */
    private fun applyEdgeToEdge() {
        if (Build.VERSION.SDK_INT < 35) return

        window.setDecorFitsSystemWindows(false)
        // 顶部是绿色标题栏，状态栏图标用浅色（白）
        window.insetsController?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )

        val header = findViewById<View>(R.id.header)
        val root = findViewById<View>(R.id.root)
        val h = intArrayOf(header.paddingLeft, header.paddingTop, header.paddingRight, header.paddingBottom)
        val r = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)

        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            header.setPadding(h[0], h[1] + bars.top, h[2], h[3])
            // 底部同时避让导航栏与输入法（edge-to-edge 下 adjustResize 不再生效）
            v.setPadding(r[0], r[1], r[2], r[3] + maxOf(bars.bottom, ime.bottom))
            insets
        }
        root.requestApplyInsets()
    }

    /** 从 nativeLibraryDir 取安装时解压出来的可执行文件 */
    private fun resolveBinary() {
        val dir = applicationInfo.nativeLibraryDir
        val f = File(dir, "liblsusb.so")
        if (f.exists() && f.canExecute()) {
            binPath = f.absolutePath
            status.text = getString(R.string.ready) + " · " + abi()
        } else {
            status.text = "未找到可执行文件：$dir/liblsusb.so"
        }
    }

    private fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    /**
     * su 探测列表。
     * Magisk 27+ 把 su 挪到 /debug_ramdisk/su，且不再建 /system/bin/su 软链，
     * 所以不能只依赖 PATH 里的 "su"；KernelSU / APatch / SuperSU 路径一并列出。
     */
    private fun suCandidates(): List<String> = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/su/bin/su",
        "/data/adb/magisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/apd/bin/su"
    ).filter { it == "su" || File(it).exists() }

    /** 启动时检查 root，无 root 弹提示 */
    private fun checkRoot() {
        status.text = "检查 root 权限…"
        Thread {
            var ok = false
            var detail = ""
            var found: String? = null
            for (cand in suCandidates()) {
                try {
                    val p = ProcessBuilder(cand, "-c", "id").redirectErrorStream(true).start()
                    detail = p.inputStream.bufferedReader().use { it.readText() }.trim()
                    p.waitFor()
                    if (detail.contains("uid=0")) { ok = true; found = cand; break }
                } catch (e: Exception) {
                    detail = e.message ?: e.toString()
                }
            }
            val finalOk = ok && binPath != null
            val finalDetail = detail
            val finalSu = found
            ui.post {
                rooted = finalOk
                suPath = finalSu
                if (rooted) {
                    buttons.forEach { it.isEnabled = true }
                    status.text = getString(R.string.ready) + " · " + abi() +
                        " · root ✓ (" + (finalSu ?: "su") + ")"
                } else {
                    status.text = getString(R.string.no_root_title)
                    showNoRootDialog(finalDetail)
                }
            }
        }.start()
    }

    private fun showNoRootDialog(detail: String) {
        val msg = if (detail.isEmpty()) getString(R.string.no_root_msg)
        else getString(R.string.no_root_msg) + "\n\nsu 返回：" + detail
        AlertDialog.Builder(this)
            .setTitle(R.string.no_root_title)
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton(R.string.retry) { _, _ -> checkRoot() }
            .setNegativeButton(R.string.quit) { _, _ -> finish() }
            .show()
    }

    /**
     * 异步执行 lsusb。
     * 要点：先清空缓冲区 -> 禁用按钮 -> 子线程跑 su -c -> 逐块回填 -> 结束恢复。
     */
    /**
     * 异步执行 lsusb。
     * transform 为 null 时逐块流式回填（树形/原始输出）；
     * 传入 transform 时先收全文，再统一转成表格一次性渲染（简要/详细）。
     */
    private fun runCommand(args: List<String>, transform: ((String) -> String)? = null) {
        val bin = binPath ?: return
        if (!rooted || running) return

        running = true
        buttons.forEach { it.isEnabled = false }
        output.text = ""                      // 点击按钮即清除缓冲区
        val cmdline = (listOf("'$bin'") + args).joinToString(" ")
        status.text = getString(R.string.running) + "  " + args.joinToString(" ")

        Thread {
            var code = -1
            val all = StringBuilder()
            var streamed = false
            try {
                val pb = ProcessBuilder(suPath ?: "su", "-c", cmdline)
                pb.redirectErrorStream(true)
                val p = pb.start()
                p.outputStream.close()
                p.inputStream.bufferedReader().use { r ->
                    var line = r.readLine()
                    while (line != null) {
                        all.append(line).append('\n')
                        if (transform == null && all.length > 3000) {
                            val chunk = all.toString()
                            all.setLength(0)
                            ui.post { appendText(chunk) }
                            streamed = true
                        }
                        line = r.readLine()
                    }
                }
                code = p.waitFor()
            } catch (e: Exception) {
                val m = e.message ?: e.toString()
                if (transform == null) ui.post { appendText("执行失败: $m\n") }
                else all.append("执行失败: ").append(m).append('\n')
            }
            ui.post {
                if (transform != null) {
                    val text = all.toString()
                    val rendered = try { transform(text) } catch (e: Exception) { text }
                    output.text = rendered.trim()
                } else {
                    if (all.isNotEmpty()) appendText(all.toString())
                    streamed = true
                }
                if (!streamed && output.text.isNullOrBlank()) {
                    output.text = "（无输出：没有匹配的设备，或该设备不存在）"
                }
                if (code != 0) appendText("\n[退出码 $code]\n")
                running = false
                if (rooted) buttons.forEach { it.isEnabled = true }
                status.text = getString(R.string.ready) + " · " + abi() + " · root ✓"
            }
        }.start()
    }

    private fun appendText(s: String) {
        output.append(s)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private data class UsbDev(
        val bus: String,
        val dev: String,
        val vid: String,
        val pid: String,
        val name: String
    )

    /**
     * 选择：先跑一次 lsusb 拿到设备列表，弹窗让用户点选，
     * 再对选中那台执行 lsusb -v -D /dev/bus/usb/<bus>/<dev>。
     * 用 -D 而不是 -d vid:pid，避免同 VID:PID 的多台设备（例如多个 root hub）被一起打印。
     */
    private fun showSelectDialog() {
        val bin = binPath ?: return
        if (!rooted || running) return

        running = true
        buttons.forEach { it.isEnabled = false }
        status.text = getString(R.string.loading_devices)

        Thread {
            val devices = mutableListOf<UsbDev>()
            var err = ""
            try {
                val p = ProcessBuilder(suPath ?: "su", "-c", "'$bin'")
                    .redirectErrorStream(true).start()
                p.outputStream.close()
                val text = p.inputStream.bufferedReader().use { it.readText() }
                p.waitFor()
                for (line in text.lines()) {
                    val m = DEV_RE.find(line) ?: continue
                    val name = m.groupValues[5].trim()
                    devices += UsbDev(
                        m.groupValues[1], m.groupValues[2],
                        m.groupValues[3], m.groupValues[4], name
                    )
                }
                if (devices.isEmpty()) err = text.trim()
            } catch (e: Exception) {
                err = e.message ?: e.toString()
            }
            ui.post {
                running = false
                if (rooted) buttons.forEach { it.isEnabled = true }
                status.text = getString(R.string.ready) + " \u00b7 " + abi() + " \u00b7 root \u2713"

                if (devices.isEmpty()) {
                    output.text = ""
                    appendText(getString(R.string.no_device) + "\n")
                    if (err.isNotEmpty()) appendText(err + "\n")
                    toast(getString(R.string.no_device))
                    return@post
                }

                showPicker(devices)
            }
        }.start()
    }

    /** 带框线的设备选择弹窗：表头 + 可点行 + 1dp 分隔线 */
    private fun showPicker(devices: List<UsbDev>) {
        val (headLine, rowLines) = LsusbTable.alignedTable(
            listOf(
                getString(R.string.lbl_pos),
                getString(R.string.lbl_vidpid),
                getString(R.string.lbl_product)
            ),
            devices.map { listOf("${it.bus}/${it.dev}", "${it.vid}:${it.pid}", it.name) },
            intArrayOf(7, 9, 26)
        )

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_dialog_table)
            val p = dp(8)
            setPadding(p, p, p, p)
        }

        val headView = TextView(this).apply {
            text = headLine
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF16693A.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        container.addView(headView)
        container.addView(hLine())

        val rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dlg: AlertDialog? = null
        val selectableBg = TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId

        devices.forEachIndexed { idx, d ->
            val row = TextView(this).apply {
                text = rowLines[idx]
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(0xFF1F2A24.toInt())
                setPadding(dp(8), dp(11), dp(8), dp(11))
                isClickable = true
                isFocusable = true
                setBackgroundResource(selectableBg)
            }
            row.setOnClickListener {
                dlg?.dismiss()
                val header = "Bus ${d.bus} Device ${d.dev}: ID ${d.vid}:${d.pid}" +
                    (if (d.name.isEmpty()) "" else " " + d.name)
                execVerbose(
                    inTable,
                    listOf("-v", "-D", "/dev/bus/usb/" + d.bus + "/" + d.dev),
                    header
                )
            }
            rowsBox.addView(row)
            if (idx != devices.lastIndex) rowsBox.addView(hLine())
        }

        val scroll = ScrollView(this).apply { addView(rowsBox) }
        container.addView(scroll)

        dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_title, devices.size))
            .setView(container)
            .setNegativeButton(R.string.select_cancel, null)
            .create()
        dlg?.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun hLine(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(0xFFE0E6E1.toInt())
    }

    /** 当前默认视图：表格 / 原始（记住在 SharedPreferences） */
    private val inTable: Boolean
        get() = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_MODE, MODE_TABLE) == MODE_TABLE

    private fun viewModeToast(table: Boolean) =
        getString(if (table) R.string.switch_table else R.string.switch_raw)

    private fun updateViewButton() {
        val b = findViewById<ImageButton>(R.id.btn_view)
        val table = inTable
        b.setImageResource(if (table) R.drawable.ic_table else R.drawable.ic_code)
        b.contentDescription = getString(if (table) R.string.mode_table else R.string.mode_raw)
    }

    private fun execBrief(table: Boolean) {
        if (table) runCommand(listOf()) { LsusbTable.renderBrief(it) } else runCommand(listOf())
    }

    private fun execVerbose(
        table: Boolean,
        args: List<String> = listOf("-v"),
        header: String? = null
    ) {
        if (table) runCommand(args) { LsusbTable.renderVerbose(it, header) } else runCommand(args)
    }

    private fun execTree(table: Boolean) {
        if (table) runCommand(listOf("-t")) { LsusbTable.renderTree(it) } else runCommand(listOf("-t"))
    }

    private fun copyOutput() {
        val text = output.text?.toString().orEmpty()
        if (text.isBlank()) {
            toast(getString(R.string.copy_empty))
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("lsusb", text))
        toast(getString(R.string.copy_toast))
    }

    private fun applyLabels() {
        LsusbTable.labels = TableLabels(
            pos = getString(R.string.lbl_pos),
            vidpid = getString(R.string.lbl_vidpid),
            product = getString(R.string.lbl_product),
            field = getString(R.string.lbl_field),
            value = getString(R.string.lbl_value),
            level = getString(R.string.lbl_level),
            location = getString(R.string.lbl_location),
            device = getString(R.string.lbl_device),
            iface = getString(R.string.lbl_iface),
            cls = getString(R.string.lbl_cls),
            driver = getString(R.string.lbl_driver),
            speed = getString(R.string.lbl_speed),
            ep = getString(R.string.lbl_ep),
            dir = getString(R.string.lbl_dir),
            type = getString(R.string.lbl_type),
            packet = getString(R.string.lbl_packet),
            interval = getString(R.string.lbl_interval),
            secDevice = getString(R.string.sec_device),
            secConfig = getString(R.string.sec_config),
            secInterface = getString(R.string.sec_interface),
            secEndpoint = getString(R.string.sec_endpoint),
            secHid = getString(R.string.sec_hid),
            secHub = getString(R.string.sec_hub),
            secBos = getString(R.string.sec_bos),
            secCap = getString(R.string.sec_cap),
            secIad = getString(R.string.sec_iad),
            secPort = getString(R.string.sec_port)
        )
    }

    private fun showLangMenu() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val cur = prefs.getString(KEY_LANG, LANG_SYSTEM)
        val labels = arrayOf(
            getString(R.string.lang_system),
            "简体中文",
            "English",
            "繁體中文"
        )
        val values = arrayOf(LANG_SYSTEM, "zh", "en", "tw")
        val checked = values.indexOf(cur).let { if (it < 0) 0 else it }

        AlertDialog.Builder(this)
            .setTitle(R.string.lang)
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                val v = values[which]
                if (v != cur) {
                    prefs.edit().putString(KEY_LANG, v).apply()
                    recreate()
                }
            }
            .setNegativeButton(R.string.select_cancel, null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this as Context, msg, Toast.LENGTH_SHORT).show()
}

package com.example.handturn

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var coolLabel: TextView
    private lateinit var coolSeek: SeekBar
    private lateinit var overlayBox: CheckBox
    private lateinit var alphaLabel: TextView
    private lateinit var alphaSeek: SeekBar
    private lateinit var pvSizeLabel: TextView
    private lateinit var pvSizeSeek: SeekBar
    private lateinit var pvAlphaLabel: TextView
    private lateinit var pvAlphaSeek: SeekBar
    private lateinit var appsSummary: TextView
    private lateinit var gestureStatus: TextView
    private lateinit var preview: TextureView
    private lateinit var toggle: Button
    private var testTracker: HandTracker? = null
    private var previewSurface: Surface? = null
    private var testing = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun prefs() = getSharedPreferences(PageTurnService.PREFS, Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PageTurnService.loadPrefs(this)
        val p = prefs()

        coolLabel = TextView(this)
        coolSeek = SeekBar(this).apply {
            max = 29 // 1~30s
            progress = (p.getInt(PageTurnService.KEY_COOLDOWN, 3000) / 1000 - 1).coerceIn(0, 29)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    coolLabel.text = "翻頁冷卻：${v + 1} 秒"
                    save()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        overlayBox = CheckBox(this).apply {
            text = "懸浮球（可拖，點一下點火/熄火，長按回設定）"
            isChecked = p.getBoolean(PageTurnService.KEY_OVERLAY, true)
            setOnCheckedChangeListener { _, _ -> save() }
        }
        alphaLabel = TextView(this)
        alphaSeek = SeekBar(this).apply {
            max = 90 // 10~100
            progress = (p.getInt(PageTurnService.KEY_ALPHA, 50) - 10).coerceIn(0, 90)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    alphaLabel.text = "懸浮球透明度：${v + 10}%"
                    save()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        pvSizeLabel = TextView(this)
        pvSizeSeek = SeekBar(this).apply {
            max = 32 // 80~400dp，每格 10dp；default 200
            progress = ((p.getInt(PageTurnService.KEY_PVW, 200) - 80) / 10).coerceIn(0, 32)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    pvSizeLabel.text = "預覽窗大小：${80 + v * 10}dp"
                    save()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        pvAlphaLabel = TextView(this)
        pvAlphaSeek = SeekBar(this).apply {
            max = 90 // 10~100
            progress = (p.getInt(PageTurnService.KEY_PVALPHA, 100) - 10).coerceIn(0, 90)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    pvAlphaLabel.text = "預覽窗透明度：${v + 10}%"
                    save()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        appsSummary = TextView(this)
        val pickApps = Button(this).apply {
            text = "選擇可翻頁的 App"
            setOnClickListener { pickApps() }
        }
        val accessBtn = Button(this).apply {
            text = "1. 開無障礙權限"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val overlayBtn = Button(this).apply {
            text = "2. 開懸浮窗權限"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        val cameraBtn = Button(this).apply {
            text = "3. 開相機權限"
            setOnClickListener {
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 100)
            }
        }
        status = TextView(this)
        toggle = Button(this).apply { setOnClickListener { toggle() } }
        gestureStatus = TextView(this).apply { text = "手勢：未測試" }
        preview = TextureView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(200), dp(150))
        }
        val gestureBtn = Button(this).apply {
            text = "手勢測試開/關（前鏡頭 30 秒）"
            setOnClickListener { toggleGestureTest() }
        }

        // ponytail: 純程式碼排版，少一堆 xml
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(accessBtn)
            addView(overlayBtn)
            addView(cameraBtn)
            addView(TextView(context).apply { text = "手勢：拳頭=下頁，剪刀（食指+中指）=上頁；同手勢不連翻" })
            addView(coolLabel)
            addView(coolSeek)
            addView(overlayBox)
            addView(alphaLabel)
            addView(alphaSeek)
            addView(pvSizeLabel)
            addView(pvSizeSeek)
            addView(pvAlphaLabel)
            addView(pvAlphaSeek)
            addView(TextView(context).apply { text = "可翻頁的 App：" })
            addView(appsSummary)
            addView(pickApps)
            addView(status)
            addView(toggle)
            addView(preview)
            addView(gestureStatus)
            addView(gestureBtn)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
        coolLabel.text = "翻頁冷卻：${coolSeek.progress + 1} 秒"
        alphaLabel.text = "懸浮球透明度：${alphaSeek.progress + 10}%"
        pvSizeLabel.text = "預覽窗大小：${80 + pvSizeSeek.progress * 10}dp"
        pvAlphaLabel.text = "預覽窗透明度：${pvAlphaSeek.progress + 10}%"
    }

    override fun onResume() {
        super.onResume()
        // ponytail: 回自家=本次閱讀結束，總閘+會話一起關（相機由 stop 釋放，比等事件快且不偶發）
        if (PageTurnService.serviceOn || PageTurnService.running) {
            PageTurnService.serviceOn = false
            PageTurnService.running = false
            PageTurnService.instance?.stop()
            PageTurnService.saveRunning(this)
        }
        save()
        refresh()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onDestroy() {
        stopTest()
        super.onDestroy()
    }

    private fun save() {
        prefs().edit()
            .putInt(PageTurnService.KEY_COOLDOWN, (coolSeek.progress + 1) * 1000)
            .putBoolean(PageTurnService.KEY_OVERLAY, overlayBox.isChecked)
            .putInt(PageTurnService.KEY_ALPHA, alphaSeek.progress + 10)
            .putInt(PageTurnService.KEY_PVW, 80 + pvSizeSeek.progress * 10)
            .putInt(PageTurnService.KEY_PVALPHA, pvAlphaSeek.progress + 10)
            .putStringSet(PageTurnService.KEY_APPS, PageTurnService.allowedApps)
            .apply()
        PageTurnService.loadPrefs(this)
        PageTurnService.instance?.applySettings()
        refreshAppsSummary()
    }

    /** 設定頁內開前鏡頭 30 秒＋小窗預覽：拳頭/剪刀入鏡會 Toast（不翻頁）。 */
    private fun toggleGestureTest() {
        if (testing) { stopTest(); return }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "先按「3. 開相機權限」", Toast.LENGTH_SHORT).show()
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 100)
            return
        }
        testing = true
        preview.visibility = View.VISIBLE
        // ponytail: 轉正＋鏡像跟懸浮窗同一套，橫豎皆通
        preview.post { HandTracker.fitPreview(preview, HandTracker.frontSensorDeg(this), HandTracker.displayDeg(this)) }
        gestureStatus.text = "手勢：等預覽就緒…"
        if (preview.isAvailable) {
            preview.surfaceTexture?.setDefaultBufferSize(HandTracker.W, HandTracker.H)
            beginTest(Surface(preview.surfaceTexture))
        } else {
            preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    st.setDefaultBufferSize(HandTracker.W, HandTracker.H)
                    if (testing && testTracker == null) beginTest(Surface(st))
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
            // ponytail: 表面 2 秒還沒好就無預覽照測，不卡死
            preview.postDelayed({ if (testing && testTracker == null) beginTest(null) }, 2000)
        }
    }

    private fun beginTest(ps: Surface?) {
        if (!testing || testTracker != null) { ps?.release(); return }
        val nt = HandTracker(
            this,
            onNext = { runOnUiThread { Toast.makeText(this, "會翻：下頁", Toast.LENGTH_SHORT).show() } },
            onPrev = { runOnUiThread { Toast.makeText(this, "會翻：上頁", Toast.LENGTH_SHORT).show() } },
            onStatus = { s -> runOnUiThread { gestureStatus.text = "手勢：$s" } },
        )
        nt.cooldownMs = 1000L
        if (!nt.start(ps)) { ps?.release(); stopTest(); return } // 失敗原因已由 onStatus 顯示
        previewSurface = ps
        testTracker = nt
        gestureStatus.text = "手勢：拳頭=下頁，剪刀=上頁；換手勢直翻"
        gestureStatus.postDelayed({ if (testTracker === nt) stopTest("手勢：測試結束") }, 30_000)
    }

    private fun stopTest(msg: String = "手勢：未測試") {
        testing = false
        try { testTracker?.close() } catch (_: Exception) {}
        testTracker = null
        try { previewSurface?.release() } catch (_: Exception) {}
        previewSurface = null
        if (::preview.isInitialized) preview.visibility = View.GONE
        if (::gestureStatus.isInitialized) gestureStatus.text = msg
    }

    private fun appLabel(pkg: String): String {
        return try {
            packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
        } catch (_: Exception) { pkg }
    }

    private fun refreshAppsSummary() {
        val set = PageTurnService.allowedApps
        appsSummary.text = if (set.isEmpty()) "全部允許（都翻）"
            else set.sorted().joinToString("\n") { "• ${appLabel(it)}" }
    }

    /** 白名單改多選清單：勾選才翻，全不勾 = 全部允許 */
    private fun pickApps() {
        val pm = packageManager
        val list = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).sortedBy { it.loadLabel(pm).toString().lowercase() }
        val pkgs = list.map { it.activityInfo.packageName }
        val labels = list.map { "${it.loadLabel(pm)} (${it.activityInfo.packageName})" }.toTypedArray()
        val checked = pkgs.map { PageTurnService.allowedApps.contains(it) }.toBooleanArray()
        val orig = PageTurnService.allowedApps
        AlertDialog.Builder(this)
            .setTitle("可翻頁的 App（全不勾 = 全部允許）")
            .setMultiChoiceItems(labels, checked) { _, which, on ->
                val s = PageTurnService.allowedApps.toMutableSet()
                if (on) s.add(pkgs[which]) else s.remove(pkgs[which])
                PageTurnService.allowedApps = s
            }
            .setPositiveButton("確定") { _, _ -> save() }
            .setNegativeButton("取消") { _, _ -> PageTurnService.allowedApps = orig }
            .show()
    }

    private fun toggle() {
        if (PageTurnService.serviceOn) {
            PageTurnService.serviceOn = false
            PageTurnService.instance?.stop()
            PageTurnService.running = false
            PageTurnService.saveRunning(this)
        } else {
            if (!isServiceOn()) {
                status.text = "狀態：請先開無障礙權限並啟用手勢翻頁"
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return
            }
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "還缺相機權限", Toast.LENGTH_SHORT).show()
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 100)
                return
            }
            PageTurnService.serviceOn = true
            PageTurnService.saveRunning(this)
            PageTurnService.instance?.applySettings()
            Toast.makeText(this, "已開啟：去閱讀頁點 ▶ 開始", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun refresh() {
        val on = isServiceOn()
        val conn = PageTurnService.instance != null
        val cur = PageTurnService.currentPkg.ifEmpty { "?" }
        status.text = "狀態：無障礙=${if (on) "開" else "關"}，連線=${if (conn) "OK" else "無"}" +
            "，服務=${if (PageTurnService.serviceOn) "開" else "關"}" +
            "，翻頁=${if (PageTurnService.running) "跑" else "停"}，當前=${cur}"
        toggle.text = if (PageTurnService.serviceOn) "停止翻頁服務" else "開啟翻頁服務"
        refreshAppsSummary()
    }

    private fun isServiceOn(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return flat.split(':').any { it.contains(packageName, ignoreCase = true) && it.contains("PageTurnService", ignoreCase = true) }
    }
}
